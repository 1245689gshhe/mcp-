package com.mcp.injector.data

/**
 * 应用设置快照。
 *
 * 默认值说明：endpoint/model 沿用 [BuiltInApi] 模板；**apiKey 默认空串**——
 * 工程不再内置任何 Key，未填写时 [AiRepository.chat] 抛 [ApiKeyMissingException]。
 */
data class AppSettings(
    val endpoint: String = BuiltInApi.ENDPOINT,
    val apiKey: String = "",
    val model: String = BuiltInApi.DEFAULT_MODEL,
    val mcpPort: Int = 1732,
    val preferServiceStrategy: Boolean = false,
    // 默认关闭动态取色：dynamicLightColorScheme 在部分精简 ROM/旧款设备上可能引发首帧渲染异常，静态色板更稳。
    val dynamicColor: Boolean = false,
    /**
     * 模型上下文窗口（token 数）。决定 dex 提示词能塞多少候选类与方法签名：
     * 窗口小的模型收敛、窗口大的模型放开，避免提示词把上下文挤爆。
     * 换算规则见 [com.mcp.injector.apk.DexOps.DexHintBudget.forContext]。
     */
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
) {
    companion object {
        /** 默认按 512k 上下文（常用的中间档）。 */
        const val DEFAULT_CONTEXT_TOKENS = 512_000
    }
}
