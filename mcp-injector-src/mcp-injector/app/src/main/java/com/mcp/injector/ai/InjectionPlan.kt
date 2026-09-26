package com.mcp.injector.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * AI 规划产物模型（原 Plan.kt）。
 *
 * 全部 @Serializable 以支持 AiPlanner 用 lenient Json 直接解码模型输出的 JSON；
 * 新增 [InjectionPlan.lastError] / [InjectionPlan.retryCount] 用于记录重试与降级原因，
 * 两者均有默认值，缺失时（模型 JSON 不含这些字段）可正常解码。
 */

/** MCP 连接方式（线格式）。 */
@Serializable
enum class ConnectionMode(val wire: String) {
    @SerialName("streamable-http")
    StreamableHttp("streamable-http"),

    @SerialName("sse")
    Sse("sse");

    companion object {
        /** 按线格式解析，未知值回退为 [StreamableHttp]。 */
        fun fromWire(value: String?): ConnectionMode =
            entries.firstOrNull { it.wire == value } ?: StreamableHttp
    }
}

/** 工具目标：描述一次工具调用将触发的安卓侧操作。 */
@Serializable
data class ToolTarget(
    val kind: String,
    val action: String? = null,
    val component: String? = null,
    val uri: String? = null,
    val contentMethod: String? = null,
    /**
     * content_call 的 provider authorities。
     *
     * 必须显式声明：AiPlanner 用 ignoreUnknownKeys 解码模型输出，模型按提示词填的
     * `authorities` 若不在模型里会被静默丢弃，config 序列化后 ToolExecutor 读不到，
     * content_call 工具一律失败于「缺少 authorities」。
     */
    val authorities: String? = null,
    val methodClass: String? = null,
    val methodName: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val methodArgTypes: List<String> = emptyList(),
)

/** MCP 工具定义：名称/描述/入参 JSON Schema/目标。 */
@Serializable
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val target: ToolTarget,
)

/** 一次注入方案。 */
@Serializable
data class InjectionPlan(
    val strategy: String = "hook",
    val reason: String = "",
    val connectionMode: String = "streamable-http",
    val port: Int = 1732,
    val tools: List<McpToolDef> = emptyList(),
    /** 降级/重试时记录的错误信息；正常规划成功为 null。 */
    val lastError: String? = null,
    /** 成功前失败的重试次数（0..2），由 AiPlanner 写入。 */
    val retryCount: Int = 0,
    /**
     * 注入入口方式：
     * - `provider`（默认）：注入 BridgeInitProvider，在 Application.onCreate 之前启动桥接，
     *   **完全不改写目标 dex**；
     * - `clinit`：改写启动类 <clinit> 插桩（兼容旧产物，存在校验器风险，仅在明确指定时使用）。
     */
    val entryPoint: String = "provider",
    /** 目标进程启动时是否弹「模块已加载」提示（约 2 秒自动消失）。 */
    val toastOnBoot: Boolean = true,
)
