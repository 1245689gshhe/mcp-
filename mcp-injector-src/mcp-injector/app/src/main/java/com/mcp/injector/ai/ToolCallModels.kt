package com.mcp.injector.ai

/** OpenAI 风格函数调用：chat.completions 响应 `message.tool_calls` 的简化投影。 */
data class ToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

/** 工具执行结果，供调用方回灌给模型继续对话（含错误标记）。 */
data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
)

/** 一次 chat 调用的结构化结果：文本内容（可为 null，如仅返回 tool_calls）与工具调用列表。 */
data class ChatResult(
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
)
