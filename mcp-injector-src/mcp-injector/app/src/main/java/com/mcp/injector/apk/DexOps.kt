package com.mcp.injector.apk

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.HiddenApiRestriction
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedClassDef
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Annotation
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.ExceptionHandler
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.iface.MultiDexContainer
import org.jf.dexlib2.iface.TryBlock
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.TypeReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableExceptionHandler
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableTryBlock
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Dex 工具（任务 C）。
 *
 * 在反编译 DexOps 基础上新增 DEV_PLAN 要求的**依赖预检 + hook 回退**：
 * - [precheck] / [precheckContainer]：注入前预检——目标 dex 是否已有 bridge 类全名冲突、
 *   启动 Activity 是否存在、其类引用能否解析，产出 [DexPrecheck] 报告；
 * - [hookClassInit]：**保留目标 `<clinit>` 原有指令**，仅在其头部插入
 *   `INVOKE_STATIC Bridge.boot()`（若原首指令为 MOVE_EXCEPTION 则保持其在最前）；
 *   类缺失/插桩失败返回 null，由 [ApkInjector] 回退到「注入 AgentReceiver/服务」引导方式；
 * - [extractBridgeDex]：从宿主 APK 提取 `com.mcp.injector.bridge` 下类集为独立 dex。
 */
object DexOps {

    /** 桥接类前缀（dex 类型描述）。 */
    private const val BRIDGE_PREFIX = "Lcom/mcp/injector/bridge/"

    /** 桥接入口类完整类型描述。 */
    const val BRIDGE_CLASS = "Lcom/mcp/injector/bridge/Bridge;"

    /**
     * kotlin-stdlib 类前缀（dex 类型描述）。
     *
     * bridge 是 Kotlin 编译产物，运行期需要 stdlib；注入 dex 必须一并带上闭包，
     * 否则纯 Java 目标进程会因 `NoClassDefFoundError` 在框架回调序言处闪退。
     * 宿主 release 未开启混淆（isMinifyEnabled=false），故按包名前缀筛选即可。
     */
    private const val STDLIB_TYPE_PREFIX = "Lkotlin/"

    /** dexlib2 Opcodes：对齐反编译产物 Opcodes.forApi(35)。 */
    private val OPCODES by lazy { Opcodes.forApi(35) }

    /** dex 扫描关键词（对齐反编译产物 HINT_KEYWORDS）。 */
    private val HINT_KEYWORDS = listOf(
        "api", "service", "control", "command", "bridge",
        "handler", "manager", "client", "util", "task", "cmd", "rpc", "server",
    )

    /** 扫描时跳过的框架/第三方前缀。 */
    private val SKIP_PREFIXES = listOf(
        "androidx/", "android/", "kotlin/", "kotlinx/", "com/google/", "java/",
    )

    /** 预检中视为“框架内置、无需在本 dex 解析”的类型前缀（dex 描述形式）。 */
    private val FRAMEWORK_TYPE_PREFIXES = listOf(
        "Ljava/", "Ljavax/", "Landroid/", "Landroidx/", "Lkotlin/", "Lkotlinx/",
        "Lcom/google/", "Lorg/", "Ldalvik/", "Llibcore/", "Lsun/", "Lcom/android/",
        "Ljunit/", "Lokhttp3/", "Lokio/", "Lorg/json/",
    )

    /** 单 dex 依赖预检结果。 */
    data class DexPrecheck(
        /** 对应 dex 条目名，如 classes.dex / classes2.dex。 */
        val dexName: String,
        /** 启动 Activity 是否存在于该 dex。 */
        val launcherPresent: Boolean,
        /** 该 dex 是否已包含 bridge 类（全名冲突，重复注入风险）。 */
        val bridgeConflict: Boolean,
        /** 启动类引用的、非框架前缀且不在本 dex 中的类型（类引用不可解析候选）。 */
        val unresolvedTypes: List<String>,
        /** 可 hook = 启动类存在 && 无 bridge 冲突 && 无不可解析引用。 */
        val hookable: Boolean,
        /** 人类可读摘要。 */
        val message: String,
    )

    /** dex 扫描出的候选类及其可暴露的方法签名（供 AI 规划 method 类型工具）。 */
    data class ClassHint(
        /** 完整类名（点号分隔，如 com.example.Foo）。 */
        val className: String,
        /** 可读方法签名，如 `bar(int, java.lang.String) -> void`；无可用方法时为空。 */
        val methods: List<String>,
    )

    /**
     * 依赖模型上下文窗口的 dex 提示词预算。
     *
     * 提示词要和清单摘要、系统提示、工具 Schema 一起塞进模型上下文：上下文小的模型
     * （如 128k）若按大模型的规模塞类名 + 方法签名，会挤占甚至撑爆窗口。这里按上下文
     * 线性收敛——窗口越小，允许的候选类与每类方法数越少。
     */
    data class DexHintBudget(
        /** 允许列出的候选类上限。 */
        val maxClasses: Int,
        /** 每个类允许列出的方法签名上限。 */
        val maxMethodsPerClass: Int,
    ) {
        /** 供 UI 展示的一句话预算说明。 */
        fun describe(): String = "$maxClasses 个候选类 × 每类 $maxMethodsPerClass 个方法签名"

        companion object {
            /** 收敛下限参考值（128k 及以下都按此下限处理）。 */
            private const val MIN_TOKENS = 128_000
            /** 放开上限参考值（1M 及以上都按此上限处理）。 */
            private const val MAX_TOKENS = 1_000_000
            private const val MIN_CLASSES = 12
            private const val MAX_CLASSES = 80
            private const val MIN_METHODS = 3
            private const val MAX_METHODS = 12

            /**
             * 按上下文窗口（token 数）推导预算，中间档位线性插值，超出区间则夹紧到端点：
             * - 128k → 12 类 / 3 方法（很省）
             * - 512k → 41 类 / 6 方法（默认档）
             * - 1M   → 80 类 / 12 方法（放开）
             */
            fun forContext(contextTokens: Int): DexHintBudget {
                val t = contextTokens.coerceIn(MIN_TOKENS, MAX_TOKENS)
                val ratio = (t - MIN_TOKENS).toDouble() / (MAX_TOKENS - MIN_TOKENS)
                return DexHintBudget(
                    maxClasses = (MIN_CLASSES + ratio * (MAX_CLASSES - MIN_CLASSES)).toInt(),
                    maxMethodsPerClass = (MIN_METHODS + ratio * (MAX_METHODS - MIN_METHODS)).toInt(),
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 加载
    // ---------------------------------------------------------------------

    fun loadContainer(apk: File): MultiDexContainer<out DexBackedDexFile> =
        DexFileFactory.loadDexContainer(apk, OPCODES)

    fun loadDexBytes(bytes: ByteArray): DexBackedDexFile =
        DexBackedDexFile.fromInputStream(OPCODES, ByteArrayInputStream(bytes))

    // ---------------------------------------------------------------------
    // 依赖预检（任务 C 新增）
    // ---------------------------------------------------------------------

    /**
     * 对单个 dex 字节做注入前预检。
     *
     * @param allTypes 整个 APK 的全部 dex 类型集（用于跨 dex 引用解析）；为 null 时退化为仅本 dex 类型集。
     */
    fun precheck(
        dexBytes: ByteArray,
        dexName: String,
        launcherActivity: String,
        allTypes: Set<String>? = null,
    ): DexPrecheck {
        val dex = runCatching { loadDexBytes(dexBytes) }.getOrNull()
        return precheckDex(dex, dexName, launcherActivity, allTypes)
    }

    private fun precheckDex(
        dex: DexBackedDexFile?,
        dexName: String,
        launcherActivity: String,
        allTypes: Set<String>?,
    ): DexPrecheck {
        val target = typeOf(launcherActivity)
        if (dex == null) {
            return DexPrecheck(dexName, false, false, emptyList(), false, "dex 解析失败")
        }
        val types = dex.classes.map { it.type }.toSet()
        // 解析集合优先使用「全 APK 类型集」，避免 classes2.dex/classes3.dex 里的跨 dex 引用
        // 被误判为不可解析；未传入时（单 dex 场景）退回本 dex 类型集。
        val resolutionTypes = allTypes ?: types
        val launcherPresent = target in types
        val bridgeConflict = types.any { it.startsWith(BRIDGE_PREFIX) }
        val unresolved = if (launcherPresent) {
            val clazz = dex.classes.firstOrNull { it.type == target }
            collectUnresolvedTypes(clazz, resolutionTypes)
        } else {
            emptyList()
        }
        val hookable = launcherPresent && !bridgeConflict && unresolved.isEmpty()
        val msg = buildString {
            append(dexName)
            append(": 启动类")
            append(if (launcherPresent) "存在" else "缺失")
            append(", bridge 冲突=").append(bridgeConflict)
            if (unresolved.isNotEmpty()) append(", 不可解析引用=").append(unresolved.size)
            append(if (hookable) " → 可 hook" else " → 需回退")
        }
        return DexPrecheck(dexName, launcherPresent, bridgeConflict, unresolved, hookable, msg)
    }

    /** 对整个 APK 的每个 dex 做预检，返回按 dex 名排序的报告列表。 */
    fun precheckContainer(apk: File, launcherActivity: String): List<DexPrecheck> {
        return runCatching {
            ZipFile(apk).use { zip ->
                val dexNames = zip.entries()
                    .asSequence()
                    .map { it.name }
                    .filter { DEX_ENTRY.matches(it) }
                    .sorted()
                    .toList()
                // 先解析全部 dex 并缓存：① 汇总「全 APK 类型集」，供跨 dex 引用解析使用；
                // ② 复用解析结果，避免下面逐 dex 预检时重复解析。
                val parsed = HashMap<String, DexBackedDexFile?>()
                val allTypes = HashSet<String>()
                for (name in dexNames) {
                    val entry = zip.getEntry(name) ?: continue
                    val dex = runCatching {
                        loadDexBytes(zip.getInputStream(entry).readBytes())
                    }.getOrNull()
                    parsed[name] = dex
                    dex?.classes?.forEach { allTypes.add(it.type) }
                }
                dexNames.map { name -> precheckDex(parsed[name], name, launcherActivity, allTypes) }
            }
        }.getOrDefault(emptyList())
    }

    /** 目标 dex 是否已含 bridge 入口类（用于“已注入”判断与重复注入防护）。 */
    fun containsBridge(dexBytes: ByteArray): Boolean =
        runCatching { loadDexBytes(dexBytes).classes.any { it.type == BRIDGE_CLASS } }
            .getOrDefault(false)

    // ---------------------------------------------------------------------
    // 类名 + 方法签名扫描（对齐反编译 scanHints 并补方法签名）
    // ---------------------------------------------------------------------

    /**
     * 扫描可能值得封装成工具的候选类，并附带其方法签名（供 AI 规划 `method` 类型工具）。
     *
     * 只喂「类名」时模型无法判断类里有什么可调用的方法；补上方法签名后，模型才能在
     * 清单组件（activity/receiver/service/provider）之外，把明确的业务方法也映射成工具。
     *
     * 扫描规模由 [budget] 决定（源自模型上下文窗口），避免小上下文模型的提示词被撑爆。
     */
    fun scanHints(apk: File, budget: DexHintBudget): List<ClassHint> {
        return runCatching {
            val out = LinkedHashMap<String, List<String>>()
            ZipFile(apk).use { zip ->
                val dexNames = zip.entries()
                    .asSequence()
                    .map { it.name }
                    .filter { DEX_ENTRY.matches(it) }
                    .sorted()
                for (name in dexNames) {
                    if (out.size >= budget.maxClasses) break
                    val entry = zip.getEntry(name) ?: continue
                    val bytes = zip.getInputStream(entry).readBytes()
                    // 单个 dex 解析失败仅跳过该 dex，不让整个 APK 扫描失败
                    val dexFile = runCatching { loadDexBytes(bytes) }.getOrNull() ?: continue
                    for (clazz in dexFile.classes) {
                        if (out.size >= budget.maxClasses) break
                        val t = clazz.type.removePrefix("L").removeSuffix(";")
                        if (SKIP_PREFIXES.any { t.startsWith(it) }) continue
                        val lower = t.lowercase(Locale.ROOT)
                        if (!HINT_KEYWORDS.any { lower.contains(it) }) continue
                        if (out.containsKey(t)) continue
                        out[t] = methodSignatures(clazz, budget.maxMethodsPerClass)
                    }
                }
            }
            out.map { (cls, methods) -> ClassHint(cls, methods) }
        }.getOrDefault(emptyList())
    }

    /**
     * 提取可读方法签名：排除构造器与编译器合成成员（`<init>`/`<clinit>`/synthetic）。
     * 优先列 public 方法；若一个都没有（例如内部工具类），退回列其余方法，避免信息全空。
     */
    private fun methodSignatures(clazz: ClassDef, max: Int): List<String> {
        val usable = clazz.methods.filter { m ->
            m.name != "<init>" && m.name != "<clinit>" && !AccessFlags.SYNTHETIC.isSet(m.accessFlags)
        }
        val picked = usable.filter { AccessFlags.PUBLIC.isSet(it.accessFlags) }.ifEmpty { usable }
        return picked.take(max).map { m ->
            val params = m.parameters.joinToString(", ") { readableType(it.type) }
            "${m.name}($params) -> ${readableType(m.returnType)}"
        }
    }

    /** dex 类型描述符转可读名：Ljava/lang/String; → java.lang.String，I → int，V → void。 */
    private fun readableType(desc: String): String = when {
        desc.startsWith("[") -> readableType(desc.substring(1)) + "[]"
        desc.startsWith("L") && desc.endsWith(";") -> desc.substring(1, desc.length - 1).replace('/', '.')
        else -> PRIMITIVES[desc] ?: desc
    }

    // ---------------------------------------------------------------------
    // hook <clinit>（保留原指令，失败返回 null 由注入器回退）
    // ---------------------------------------------------------------------

    fun hookClassInit(dexBytes: ByteArray, className: String): ByteArray? {
        return runCatching {
            val target = typeOf(className)
            val dex = loadDexBytes(dexBytes)
            var replaced = false
            val classes = dex.classes.map { clazz ->
                if (clazz.type == target) {
                    replaced = true
                    rebuildWithBootCall(clazz)
                } else {
                    clazz
                }
            }
            if (!replaced) {
                null
            } else {
                val out = ImmutableDexFile(OPCODES, classes)
                val tmp = File.createTempFile("patched", ".dex")
                try {
                    DexFileFactory.writeDexFile(tmp.absolutePath, out)
                    tmp.readBytes()
                } finally {
                    tmp.delete()
                }
            }
        }.getOrNull()
    }

    /** 重建类：去掉原 `<clinit>` 后用「boot 调用 + 原指令」的新 `<clinit>` 替换。 */
    private fun rebuildWithBootCall(clazz: ClassDef): ClassDef {
        val others = clazz.directMethods.filterNot { it.name == "<clinit>" }
        val original = clazz.directMethods.firstOrNull { it.name == "<clinit>" }
        // 只调用一次 patchMethod（纯函数）：原 <clinit> 缺失或插桩失败时退回仅含 boot 调用的新 <clinit>。
        val patched = original?.let { patchMethod(it) }
        val bootClinit = patched ?: createClinit(clazz.type)
        return ImmutableClassDef(
            clazz.type,
            clazz.accessFlags,
            clazz.superclass,
            clazz.interfaces,
            clazz.sourceFile,
            clazz.annotations,
            clazz.staticFields,
            clazz.instanceFields,
            others + bootClinit,
            clazz.virtualMethods,
        )
    }

    /** 在 `<clinit>` 指令头部插入 boot 调用，**保留**原指令（MOVE_EXCEPTION 保持在最前）。 */
    private fun patchMethod(method: Method): Method? {
        val impl = method.implementation ?: return null
        val list = impl.instructions.toList()
        val boot = bootInstruction()
        // INVOKE_STATIC 35c = 3 个 code unit；boot 插入后，其后所有指令/异常表的
        // 代码偏移都要整体右移 3 个单位，否则写 dex 时报 "Bad position"。
        // 依据 dexlib2 语义（见 iface/TryBlock.getStartCodeAddress 与
        // iface/ExceptionHandler.getHandlerCodeAddress 的 javadoc）：这两个偏移都是
        // 「相对方法字节码起始的 16-bit code unit 偏移」，故此处统一 +shift 是正确的。
        // 若原首指令为 MOVE_EXCEPTION，它保留在偏移 0，boot 插在其后（偏移 1..3），
        // 其余指令与 try 块的起始偏移（均 > 0）仍需整体 +shift。
        val shift = boot.codeUnits
        val keep = if (list.firstOrNull()?.opcode == Opcode.MOVE_EXCEPTION) 1 else 0
        val head = list.take(keep).map { ImmutableInstruction.of(it) }
        val tail = list.drop(keep).map { ImmutableInstruction.of(it) }
        // 异常表整体平移；debug（行号/局部变量）偏移已失效，直接丢弃避免写出错误。
        // 注：dexlib2 2.5.2 中 catch-all 异常处理器的 exceptionType 为 null，
        // ImmutableExceptionHandler(String?, int) 两个构造形参都传（type 可为 null）。
        val shiftedTries = impl.tryBlocks.map { tb ->
            ImmutableTryBlock(
                tb.startCodeAddress + shift,
                tb.codeUnitCount,
                tb.exceptionHandlers.map { eh ->
                    ImmutableExceptionHandler(eh.exceptionType, eh.handlerCodeAddress + shift)
                },
            )
        }
        return ImmutableMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            ImmutableMethodImplementation(
                impl.registerCount,
                head + boot + tail,
                shiftedTries,
                emptyList(),
            ),
        )
    }

    /** 原 `<clinit>` 缺失（或 patch 失败）时新建：仅含 boot 调用。 */
    private fun createClinit(definingClass: String): Method = ImmutableMethod(
        definingClass,
        "<clinit>",
        emptyList(),
        "V",
        0x10008, // static | constructor
        emptySet<Annotation>(),
        emptySet<HiddenApiRestriction>(),
        ImmutableMethodImplementation(1, listOf(bootInstruction()), emptyList(), emptyList()),
    )

    private fun bootInstruction(): Instruction = ImmutableInstruction35c(
        Opcode.INVOKE_STATIC,
        0, 0, 0, 0, 0, 0,
        ImmutableMethodReference(BRIDGE_CLASS, "boot", emptyList(), "V"),
    )

    // ---------------------------------------------------------------------
    // bridge dex 提取（对齐反编译 extractBridgeDex）
    // ---------------------------------------------------------------------

    /**
     * 收集 bridge 类依赖的 kotlin-stdlib 类闭包（取自宿主 APK 的各 dex）。
     *
     * 为什么必须带：bridge 是 Kotlin 编译产物，运行期需要 kotlin-stdlib。目标 App 若是
     * **纯 Java 应用**（进程里没有 stdlib），后果是致命的：
     *  - `AgentReceiver.onReceive(context, intent)` 这类框架回调，**参数非空检查（Intrinsics）
     *    在方法序言里**执行，方法体内的 try/catch 挡不住 → 异常直接抛在目标 App 主线程
     *    → 进程被杀。表现为「已注入的 App 一点宿主重试就闪退」；
     *  - 启动期因 BridgeInitProvider 侧有 try/catch 才没崩，但桥接全程失效，表现为
     *    「打开软件后拿不到工具」。
     * Kotlin 写的 App 自带 stdlib，所以一切正常——这正是「部分软件能成功、部分不行」的原因。
     *
     * 只取**被引用到的闭包**，不是全部 kotlin 类：体积可控，不会撑爆单 dex 方法数上限
     * （dex 的 method id 上限 65536，全量 stdlib 接近该上限，闭包只占极小一部分）。
     *
     * @param seeds bridge 类；它们自身不入闭包，只有它们引用到的 kotlin 类才入。
     */
    private fun collectStdlibClosure(
        container: MultiDexContainer<out DexBackedDexFile>,
        seeds: List<ClassDef>,
    ): List<ClassDef> {
        // 宿主各 dex 里的 kotlin 类（type → 原始 ClassDef），供闭包查找
        val available = HashMap<String, ClassDef>()
        for (name in container.dexEntryNames) {
            val dexFile = container.getEntry(name)?.dexFile as? DexBackedDexFile ?: continue
            for (clazz in dexFile.classes) {
                if (clazz.type.startsWith(STDLIB_TYPE_PREFIX)) available.putIfAbsent(clazz.type, clazz)
            }
        }
        if (available.isEmpty()) return emptyList()

        val collected = LinkedHashMap<String, ClassDef>()
        val queued = HashSet<String>()
        val queue = ArrayDeque<String>()
        fun enqueue(type: String) {
            if (type.startsWith(STDLIB_TYPE_PREFIX) && !collected.containsKey(type) && queued.add(type)) {
                queue.add(type)
            }
        }

        for (seed in seeds) referencedTypes(seed).forEach { enqueue(it) }
        while (queue.isNotEmpty()) {
            val clazz = available[queue.removeFirst()] ?: continue
            collected[clazz.type] = ImmutableClassDef.of(clazz)
            referencedTypes(clazz).forEach { enqueue(it) }
        }
        return collected.values.toList()
    }

    /** 一个类引用的全部类型描述符（父类/接口/字段类型/方法签名/指令引用），用于闭包遍历。 */
    private fun referencedTypes(clazz: ClassDef): Set<String> {
        val out = LinkedHashSet<String>()
        clazz.superclass?.let { out.add(it) }
        clazz.interfaces.forEach { out.add(it) }
        for (f in clazz.fields) out.add(f.type.toString())
        for (m in clazz.methods) {
            out.add(m.returnType.toString())
            m.parameterTypes.forEach { out.add(it.toString()) }
            val impl = m.implementation ?: continue
            for (insn in impl.instructions) {
                if (insn !is ReferenceInstruction) continue
                when (val ref = insn.reference) {
                    is TypeReference -> out.add(ref.type)
                    is MethodReference -> out.add(ref.definingClass)
                    is FieldReference -> out.add(ref.definingClass)
                    else -> {}
                }
            }
        }
        return out
    }

    fun extractBridgeDex(hostApk: File, output: File): File {
        try {
            val container = loadContainer(hostApk)
            val bridgeClasses = ArrayList<ClassDef>()
            for (name in container.dexEntryNames) {
                val entry = container.getEntry(name) ?: continue
                val dexFile = entry.dexFile as? DexBackedDexFile ?: continue
                for (clazz in dexFile.classes) {
                    if (clazz.type.startsWith(BRIDGE_PREFIX)) {
                        bridgeClasses.add(ImmutableClassDef.of(clazz))
                    }
                }
            }
            if (bridgeClasses.isEmpty()) {
                throw IllegalStateException("宿主 APK 中找不到桥接类，请检查混淆配置")
            }
            if (bridgeClasses.none { it.type == BRIDGE_CLASS }) {
                throw IllegalStateException("缺少 Bridge 入口类")
            }
            // 必须连同 kotlin-stdlib 闭包一起打进注入 dex：目标进程不保证有 stdlib，
            // 缺失时的后果见 [collectStdlibClosure]。
            val stdlib = collectStdlibClosure(container, bridgeClasses)
            DexFileFactory.writeDexFile(
                output.absolutePath,
                ImmutableDexFile(OPCODES, bridgeClasses + stdlib),
            )
            return output
        } catch (e: Exception) {
            // 包装底层异常（如 loadDexContainer / writeDexFile 抛出的库异常），给出明确中文说明并保留 cause。
            throw IllegalStateException("从宿主 APK 提取 bridge dex 失败: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------------
    // 其他
    // ---------------------------------------------------------------------

    fun containsClass(dexBytes: ByteArray, className: String): Boolean =
        runCatching { loadDexBytes(dexBytes).classes.any { it.type == typeOf(className) } }
            .getOrDefault(false)

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    private fun typeOf(className: String): String = "L" + className.replace('.', '/') + ";"

    /** 收集启动类方法体引用的、非框架前缀且不在本 dex 的类型（类引用可解析性预检）。 */
    private fun collectUnresolvedTypes(clazz: ClassDef?, dexTypes: Set<String>): List<String> {
        if (clazz == null) return emptyList()
        val referenced = LinkedHashSet<String>()
        // 父类/接口也必须可解析
        clazz.superclass?.let { referenced.add(it) }
        clazz.interfaces.forEach { referenced.add(it) }
        for (m in clazz.methods) {
            val impl = m.implementation ?: continue
            for (insn in impl.instructions) {
                if (insn !is ReferenceInstruction) continue
                val ref = insn.reference
                when (ref) {
                    is TypeReference -> referenced.add(ref.type)
                    is MethodReference -> referenced.add(ref.definingClass)
                    is FieldReference -> referenced.add(ref.definingClass)
                    else -> {}
                }
            }
        }
        return referenced
            .filter { it !in dexTypes && !isFrameworkType(it) && it != clazz.type }
            .sorted()
    }

    private fun isFrameworkType(type: String): Boolean =
        FRAMEWORK_TYPE_PREFIXES.any { type.startsWith(it) } || !type.startsWith("L")

    /** classes*.dex 条目匹配（含单 classes.dex）。 */
    private val DEX_ENTRY = Regex("^classes\\d*\\.dex$")

    /** dex 原始类型描述符 → 可读名（供 [readableType] 使用）。 */
    private val PRIMITIVES = mapOf(
        "V" to "void",
        "Z" to "boolean",
        "B" to "byte",
        "S" to "short",
        "C" to "char",
        "I" to "int",
        "J" to "long",
        "F" to "float",
        "D" to "double",
    )
}
