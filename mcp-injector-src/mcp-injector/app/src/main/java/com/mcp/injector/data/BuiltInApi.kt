package com.mcp.injector.data

/**
 * 内置 API 模板常量。
 *
 * 注意：**不内置任何演示 Key / 内置 Key**（原版 `sk-injector-built-in-demo-key` 已彻底移除）。
 * 用户必须自行在设置页填写 API Key / BaseURL / 模型；未填写时由 [AiRepository] 抛
 * [ApiKeyMissingException] 并引导到设置页。这里仅保留非敏感的默认端点与默认模型模板。
 */
object BuiltInApi {
    /** 默认端点模板（可改）。 */
    const val ENDPOINT = "https://api.deepseek.com/v1"

    /** 默认模型模板（可改）。 */
    const val DEFAULT_MODEL = "deepseek-chat"
}
