package com.mcp.injector.ai

import kotlinx.serialization.Serializable

/**
 * 目标应用当前的注入策略快照（来自注入历史/目标侧标记）。
 * 供 AI 判断「现状 → 目标」以及 UI 展示改动点。
 */
data class CurrentStrategy(
    val entryPoint: String,
    val toastOnBoot: Boolean,
    val strategy: String,
    val connectionMode: String,
    val port: Int,
    /** 当前已注册工具数（用于展示「工具集 N → M」的改动点）。 */
    val toolsCount: Int = 0,
) {
    companion object {
        /** 从注入历史记录的字段还原（缺省按 provider 策略的默认值）。 */
        fun from(
            entryPoint: String?,
            toastOnBoot: Boolean,
            strategy: String?,
            connectionMode: String?,
            port: Int,
            toolsCount: Int = 0,
        ): CurrentStrategy = CurrentStrategy(
            entryPoint = StrategyPatch.normalizeEntryPoint(entryPoint ?: "provider"),
            toastOnBoot = toastOnBoot,
            strategy = if (strategy == "service") "service" else "hook",
            connectionMode = ConnectionMode.fromWire(connectionMode).wire,
            port = port,
            toolsCount = toolsCount,
        )
    }
}

/**
 * 注入策略补丁：AI 依据用户反馈的问题（可带目标侧崩溃栈/诊断）产出的**结构化改动**。
 *
 * 每个字段为 null 表示「不改该项」。UI 用 [describeChanges] 展示改动点，
 * 用户确认「应用并重新注入」后才由 [applyTo] 合并进 [InjectionPlan] ——
 * 保证每次调整都可见、可回退，AI 不会偷偷改掉注入行为。
 */
@Serializable
data class StrategyPatch(
    /**
     * 注入入口：
     * - `provider`（默认，最稳）：注入 ContentProvider，在 Application.onCreate 之前拉起注入体，
     *   **完全不改写目标 dex**；
     * - `clinit`：改写启动类 `<clinit>` 插桩（兼容旧产物，有校验器风险）。
     */
    val entryPoint: String? = null,
    /** 启动时是否弹 2 秒「模块已加载」提示。 */
    val toastOnBoot: Boolean? = null,
    /** 声明式注入策略：hook / service。 */
    val strategy: String? = null,
    /** 连接方式：streamable-http / sse。 */
    val connectionMode: String? = null,
    /** 监听端口（1024-9999）。 */
    val port: Int? = null,
    /**
     * 工具集整体替换（null = 不改）。
     *
     * 工具不是「策略微调」能解决的：模型在 [reviseStrategy] 里拿不到 dex 候选方法，
     * 无法凭空设计工具，所以这里通常由宿主在「应用并重新注入」时用完整规划（带 dex 提示）
     * 重新规划后回填，形成**可执行、可见、可备份**的改动。
     */
    val tools: List<McpToolDef>? = null,
    /** 中文说明：为什么这样调、预期解决什么。 */
    val explanation: String = "",
) {

    /** 是否没有任何实际改动（AI 可能只给文字建议）。 */
    fun isEmpty(): Boolean =
        entryPoint == null && toastOnBoot == null && strategy == null &&
            connectionMode == null && port == null && tools == null

    /** 生成中文「改动点」列表；非法/越界值不列出（它们会被 [applyTo] 忽略）。 */
    fun describeChanges(current: CurrentStrategy): List<String> {
        val out = ArrayList<String>()
        entryPoint?.let {
            val v = normalizeEntryPoint(it)
            if (v != current.entryPoint) out.add("注入入口：${current.entryPoint} → $v")
        }
        toastOnBoot?.let {
            if (it != current.toastOnBoot) out.add("启动提示：${if (it) "开启" else "关闭"}")
        }
        strategy?.let {
            val v = if (it == "service") "service" else "hook"
            if (v != current.strategy) out.add("注入策略：${current.strategy} → $v")
        }
        connectionMode?.let {
            val v = ConnectionMode.fromWire(it).wire
            if (v != current.connectionMode) out.add("连接方式：${current.connectionMode} → $v")
        }
        port?.let { p ->
            if (p in PORT_MIN..PORT_MAX && p != current.port) out.add("端口：${current.port} → $p")
        }
        tools?.let { t ->
            val names = t.take(6).joinToString("、") { it.name }
            val more = if (t.size > 6) "…" else ""
            out.add(
                if (t.isEmpty()) {
                    "工具集：清空（当前 ${current.toolsCount} 个）"
                } else {
                    "工具集：重新规划（当前 ${current.toolsCount} 个 → ${t.size} 个：$names$more）"
                },
            )
        }
        return out
    }

    /** 合并进一次新规划出的 [InjectionPlan]；非法值一律忽略，保证注入参数始终合法。 */
    fun applyTo(plan: InjectionPlan): InjectionPlan = plan.copy(
        entryPoint = entryPoint?.let { normalizeEntryPoint(it) } ?: plan.entryPoint,
        toastOnBoot = toastOnBoot ?: plan.toastOnBoot,
        strategy = strategy?.let { if (it == "service") "service" else "hook" } ?: plan.strategy,
        connectionMode = connectionMode?.let { ConnectionMode.fromWire(it).wire } ?: plan.connectionMode,
        port = port?.takeIf { it in PORT_MIN..PORT_MAX } ?: plan.port,
        tools = tools ?: plan.tools,
    )

    companion object {
        private const val PORT_MIN = 1024
        private const val PORT_MAX = 9999

        /** 入口名归一：只有显式 clinit 才走插桩，其余一律 provider。 */
        fun normalizeEntryPoint(raw: String): String =
            if (raw.equals("clinit", ignoreCase = true)) "clinit" else "provider"
    }
}

/** AI 对一次问题反馈的回复：自然语言说明 + 结构化补丁。 */
data class StrategyRevision(
    val reply: String,
    val patch: StrategyPatch,
)

/** 对话历史中的一条消息（宿主侧展示 + 回灌进提示词）。 */
@Serializable
data class ChatTurn(
    /** `user` 或 `assistant`。 */
    val role: String,
    val text: String,
)