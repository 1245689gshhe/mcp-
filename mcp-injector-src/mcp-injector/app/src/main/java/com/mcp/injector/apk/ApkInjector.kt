package com.mcp.injector.apk

import android.content.Context
import com.mcp.injector.ai.InjectionPlan
import com.mcp.injector.agent.InjectedApp
import com.mcp.injector.agent.InjectedAppsStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 注入器（任务 C 核心）。
 *
 * 契约签名保持反编译产物：`inject(sourceApk: File, info: ApkInfo, plan: InjectionPlan, output: File): File`。
 *
 * 相对原版（反编译）的增强（DEV_PLAN 任务 C）：
 * 1. **依赖预检**：注入前 [DexOps.precheckContainer] 产出预检报告——启动类是否存在、
 *    bridge 类全名冲突（重复注入防护）、启动类引用能否解析；
 * 2. **hook 回退**：仅当存在可 hook 的 dex 且 [DexOps.hookClassInit] 成功时才插桩；
 *    否则回退到「注入 AgentReceiver + BridgeService」的广播/服务引导方式（不抛异常）；
 * 3. **一次注入** = bridge dex（含 OfficialModule/AgentReceiver 等全部 bridge 包类）
 *    + `assets/mcp_bridge.json` 配置 + `assets/.mcp_injector_marker` 指纹标记
 *    + manifest 注册 AgentReceiver（总是）与 BridgeService（service 策略或回退时）；
 * 4. **历史记录**：成功后以包名、签名指纹、installId、端口、模块写入 [InjectedAppsStore]。
 *
 * 说明：标记文件以 `assets/.mcp_injector_marker` 打入 APK；目标侧首次启动时由注入的
 * agent（任务 B 的 Bridge/OfficialModule 自洽路径）将其物化到 filesDir 同名文件——
 * 注入器侧已完成标记内容生成与打包。
 */
class ApkInjector(private val context: Context) {

    /** 最近一次成功注入的记录（供 UI/事件提示；未注入过为 null）。 */
    @Volatile
    var lastRecord: InjectedApp? = null
        private set

    private val signer = ApkSigner(context)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行一次完整注入。
     *
     * @param sourceApk 目标 APK
     * @param info 目标 APK 解析信息（[ApkParser.parse] 产物）
     * @param plan AI 规划方案
     * @param output 输出（已重签名）APK 路径
     * @return 注入并重签名后的 output
     */
    fun inject(sourceApk: File, info: ApkInfo, plan: InjectionPlan, output: File): File {
        output.parentFile?.mkdirs()
        val launcherActivity = info.launcherActivity
            ?: throw IllegalStateException("未找到启动 Activity，无法植入入口")

        // ---- 1. 依赖预检（任务 C 新增）----
        val prechecks = DexOps.precheckContainer(sourceApk, launcherActivity)
        val hookable = prechecks.firstOrNull { it.hookable }
        val alreadyInjected = prechecks.any { it.bridgeConflict }

        // ---- 2. bridge dex：宿主一次提取并缓存（bridge + OfficialModule + AgentReceiver 同包）----
        val bridgeDex = ensureBridgeDex(File(context.applicationInfo.sourceDir))
        val installId = generateToken()

        // ---- 3. 读取源 APK 条目（剔除旧签名文件，排序保证确定性）----
        val entries = LinkedHashMap<String, Pair<Int, ByteArray>>()
        ZipFile(sourceApk).use { zip ->
            for (entry in zip.entries().asSequence().sortedBy { it.name }) {
                if (isOldSignature(entry.name)) continue
                entries[entry.name] = entry.method to zip.getInputStream(entry).readBytes()
            }
        }

        val manifest = entries[MANIFEST_ENTRY]?.second
            ?: throw IllegalStateException("安装包缺少 AndroidManifest.xml")

        // ---- 4. manifest：INTERNET 权限 + AgentReceiver（总是） + BridgeService（按需） + 引导 Provider ----
        // 注入入口：默认 provider —— 只加一条 provider 声明，完全不改写目标 dex；
        // 只有显式指定 clinit 时才走旧的 <clinit> 插桩路径（兼容旧产物）。
        val entryPoint = resolveEntryPoint(plan)
        var manifestBytes = AxmlEditor.ensureInternetPermission(manifest)
        manifestBytes = AxmlEditor.injectReceiver(manifestBytes)
        val fallbackToService = hookable == null || alreadyInjected
        if (plan.strategy == "service" || fallbackToService) {
            manifestBytes = AxmlEditor.injectService(manifestBytes)
        }
        if (entryPoint == ENTRY_PROVIDER) {
            // ContentProvider.onCreate 在 Application.onCreate 之前执行 → 注入体优先加载。
            // authority 必须设备内唯一，否则安装时报 INSTALL_FAILED_CONFLICTING_PROVIDER。
            manifestBytes = AxmlEditor.injectProvider(
                manifestBytes,
                "${info.packageName}.mcp-injector.bridge.init",
            )
        }

        // ---- 5. hook 主 dex（仅 clinit 策略；provider 策略完全不碰目标字节码）----
        if (entryPoint == ENTRY_CLINIT && hookable != null && !alreadyInjected) {
            val dexBytes = entries[hookable.dexName]?.second
            if (dexBytes != null) {
                val patched = DexOps.hookClassInit(dexBytes, launcherActivity)
                if (patched != null) {
                    entries[hookable.dexName] = entries.getValue(hookable.dexName).first to patched
                }
            }
        }

        // ---- 6. bridge dex：重复注入时先移除旧 bridge dex，再追加当前版本 ----
        // 必须移除旧的：旧产物里的 bridge dex 可能是不含 BridgeInitProvider 的旧版本，
        // 而 manifest 已被加上 provider 声明 —— 若沿用旧 dex，目标 App 启动时会在安装
        // provider 阶段抛 ClassNotFoundException 直接闪退。
        // 守卫：只移除桥接类所在的附加 dex，绝不触碰目标自身的 classes.dex（多 dex 场景下
        // precheck 可能把 classes.dex 也标为冲突，删除它会毁掉目标 App）。
        if (alreadyInjected) {
            prechecks.filter { it.bridgeConflict && it.dexName != PRIMARY_DEX }
                .forEach { entries.remove(it.dexName) }
        }
        // DEX_ENTRY 必须带捕获组才能取序号；classes.dex 无数字，按 1 计。
        val maxIdx = entries.keys
            .mapNotNull { name ->
                DEX_ENTRY.matchEntire(name)
                    ?.groupValues?.getOrNull(1).orEmpty()
                    .ifEmpty { "1" }
                    .toIntOrNull()
            }
            .maxOrNull() ?: 1
        entries["classes${maxIdx + 1}.dex"] = METHOD_DEFLATED to bridgeDex.readBytes()

        // ---- 7. 配置 + 指纹标记 ----
        entries["assets/$CONFIG_ASSET"] = METHOD_DEFLATED to
            bridgeConfigJson(plan, info.packageName).toByteArray(Charsets.UTF_8)
        entries["assets/$MARKER_ASSET"] = METHOD_DEFLATED to
            markerJson(plan, info, installId, entryPoint).toString().toByteArray(Charsets.UTF_8)
        entries[MANIFEST_ENTRY] = METHOD_DEFLATED to manifestBytes

        // ---- 8. 对齐写出 + 重签名 ----
        val unsigned = File(output.parentFile, output.nameWithoutExtension + ".unsigned.apk")
        FileOutputStream(unsigned).use { fos ->
            val writer = AlignedZipWriter(fos)
            for ((name, pair) in entries) {
                val (method, bytes) = pair
                if (name == "resources.arsc" || method == METHOD_STORED) {
                    writer.addStored(name, bytes, 4)
                } else {
                    writer.addDeflated(name, bytes)
                }
            }
            writer.close()
        }
        try {
            signer.sign(unsigned, output)
        } finally {
            unsigned.delete()
        }

        // ---- 9. 记录历史（包名、签名指纹、installId、端口、模块）----
        val store = InjectedAppsStore(context)
        val record = InjectedApp(
            installId = installId,
            packageName = info.packageName,
            appName = info.appLabel ?: info.packageName,
            versionName = info.versionName,
            signatureFingerprint = store.sigFingerprint() ?: "",
            port = plan.port.coerceIn(PORT_MIN, PORT_MAX),
            modules = listOf(OFFICIAL_MODULE_ID),
            injectedAt = System.currentTimeMillis(),
            source = "history",
            // output 由调用方传入，未必位于 filesDir 之下；relativeTo 在两个路径无法构成
            // 相对关系时会抛 IllegalArgumentException，而此时签名已成功——会导致「注入成功
            // 却报失败」且历史记录丢失。与 sourceRelative 保持一致的守卫：仅当 output 确实
            // 位于 filesDir 之下才取相对路径，否则回退为绝对路径（不返回 null，保证记录仍
            // 可用于安装/复检）。
            outputRelative = output.absolutePath
                .takeIf { it.startsWith(context.filesDir.absolutePath) }
                ?.let { output.relativeTo(context.filesDir).path }
                ?: output.absolutePath,
            sourceRelative = sourceApk.absolutePath
                .takeIf { it.startsWith(context.filesDir.absolutePath) }
                ?.let { sourceApk.relativeTo(context.filesDir).path },
            strategy = plan.strategy,
            hookable = hookable != null && !alreadyInjected && entryPoint == ENTRY_CLINIT,
            entryPoint = entryPoint,
            toastOnBoot = plan.toastOnBoot,
        )
        store.upsert(record)
        lastRecord = record
        return output
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /**
     * 从宿主 APK 提取 bridge 包类为独立 dex，缓存到 `filesDir/payload/bridge-v<N>.dex`。
     *
     * 缓存文件名带 [BRIDGE_PAYLOAD_VERSION]：bridge 包新增/修改类后必须递增版本号，
     * 否则会命中旧缓存 —— 例如新增 [AxmlEditor.BRIDGE_INIT_PROVIDER] 后沿用旧 dex，
     * manifest 却已声明该 provider，目标 App 启动时会因类不存在直接闪退。
     */
    private fun ensureBridgeDex(hostApk: File): File {
        val file = File(context.filesDir, "payload/bridge-v$BRIDGE_PAYLOAD_VERSION.dex")
        if (file.exists() && file.length() > 0) return file
        file.parentFile?.mkdirs()
        return DexOps.extractBridgeDex(hostApk, file)
    }

    /** 解析注入入口：仅显式 "clinit" 才走 <clinit> 插桩，其余一律 provider。 */
    private fun resolveEntryPoint(plan: InjectionPlan): String =
        if (plan.entryPoint.equals(ENTRY_CLINIT, ignoreCase = true)) ENTRY_CLINIT else ENTRY_PROVIDER

    /** mcp_bridge.json：扁平字段 + 兼容嵌套 connection；无任何鉴权字段。 */
    private fun bridgeConfigJson(plan: InjectionPlan, packageName: String): String {
        val port = plan.port.coerceIn(PORT_MIN, PORT_MAX)
        val json = JSONObject()
        json.put("version", 1)
        json.put("packageName", packageName)
        json.put("port", port)
        json.put("host", "127.0.0.1")
        json.put("mode", plan.connectionMode)
        json.put("modules", JSONArray(listOf(OFFICIAL_MODULE_ID)))
        // 目标进程启动提示开关（Bridge.notifyBootDone 读取）
        json.put("toastOnBoot", plan.toastOnBoot)
        json.put(
            "connection",
            JSONObject()
                .put("mode", plan.connectionMode)
                .put("host", "127.0.0.1")
                .put("port", port),
        )
        json.put("tools", JSONArray(this.json.encodeToString(plan.tools)))
        return json.toString()
    }

    /** .mcp_injector_marker：{installId, appVersion, port, injectedAt, modules, 注入策略}。 */
    private fun markerJson(
        plan: InjectionPlan,
        info: ApkInfo,
        installId: String,
        entryPoint: String,
    ): JSONObject {
        val official = JSONObject()
            .put("id", OFFICIAL_MODULE_ID)
            .put("name", "官方调试模块")
            .put("tools", JSONArray(plan.tools.map { it.name }))
        return JSONObject()
            .put("installId", installId)
            .put("appVersion", info.versionName)
            .put("port", plan.port.coerceIn(PORT_MIN, PORT_MAX))
            .put("injectedAt", System.currentTimeMillis())
            // 注入策略落进标记：诊断页/AI 改策略时据此判断当前用的哪种入口
            .put("strategy", plan.strategy)
            .put("entryPoint", entryPoint)
            .put("toastOnBoot", plan.toastOnBoot)
            .put("modules", JSONArray(listOf<Any>(official)))
    }

    private fun isOldSignature(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase(Locale.ROOT)
        return upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC") ||
            upper.endsWith("MANIFEST.MF")
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        const val CONFIG_ASSET = "mcp_bridge.json"
        const val MARKER_ASSET = ".mcp_injector_marker"
        const val OFFICIAL_MODULE_ID = "official"

        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        /** 目标 App 自身的主 dex：重复注入清理旧 bridge dex 时必须避开它。 */
        const val PRIMARY_DEX = "classes.dex"

        /** 注入入口常量。 */
        const val ENTRY_PROVIDER = "provider"
        const val ENTRY_CLINIT = "clinit"

        /**
         * bridge payload 版本：**bridge 包内容（类/方法）变动时必须递增**。
         * 它体现在缓存 dex 文件名上，避免复用旧 dex。
         *
         * v3：BridgeInitProvider 的崩溃采集与桥接启动改为各自独立 try，修复
         *     「崩溃采集抛异常导致注入体完全不启动」。
         * v4：注入 dex 一并打包 kotlin-stdlib 闭包，修复纯 Java 目标应用因缺 stdlib
         *     在 AgentReceiver 回调序言处闪退（宿主「重试」时目标进程被杀）。
         */
        const val BRIDGE_PAYLOAD_VERSION = 4

        const val PORT_MIN = 1024
        const val PORT_MAX = 9999

        val DEX_ENTRY = Regex("^classes(\\d*)\\.dex$")
    }
}
