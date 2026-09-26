package com.mcp.injector.data

import com.mcp.injector.ai.ChatResult
import com.mcp.injector.ai.McpToolDef
import com.mcp.injector.ai.ToolCall
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** API Key 未填写（或仍是旧版占位演示 Key）时抛出；UI 捕获后应引导用户前往设置页填写真实 Key。 */
class ApiKeyMissingException(message: String) : IllegalStateException(message)

/**
 * OpenAI 兼容 chat.completions 客户端（P0）。
 *
 * - 请求可按需携带 `tools` 声明；响应同时解析 `message.content` 与 `message.tool_calls`
 *   （每项含 `id/function.name/function.arguments`），返回 [ChatResult]。
 * - **本类只解析 tool_calls，不执行任何工具**：规划阶段没有可执行的 MCP 工具运行时
 *   （工具 target 只有注入目标应用后才由 bridge/ToolExecutor 执行），因此不存在"执行结果"回灌；
 *   调用方（AiPlanner）只能在 content 为空时把工具名/参数作为文本提示追加进下一次请求。
 * - **无内置 Key**：[BuiltInApi] 不含任何 Key；apiKey 空白或等于旧版演示 Key 时抛 [ApiKeyMissingException]，
 *   绝不"必然可用"。用户必须自行在设置页填写 API Key / BaseURL / 模型。
 * - HTTP 非 2xx 抛含状态码的可读 [IOException]；响应 JSON 解析失败抛 [IllegalStateException]。
 */
class AiRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 拉取端点支持的模型 id 列表。 */
    suspend fun listModels(endpoint: String, apiKey: String): List<String> {
        requireValidKey(apiKey)
        val request = Request.Builder()
            .url(joinUrl(endpoint, "models"))
            .header("Authorization", "Bearer $apiKey")
            .build()
        val body = awaitBody(request)
        val data = try {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
        } catch (e: Exception) {
            throw IllegalStateException("模型列表响应解析失败：${e.message}", e)
        } ?: throw IllegalStateException("模型列表响应缺少 data 字段：${body.take(300)}")
        return data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    }

    /**
     * 调用 OpenAI 风格 chat.completions：请求携带 `tools` 声明，响应同时解析
     * `message.content` 与 `message.tool_calls`（数组，每项取 id/function.name/function.arguments）。
     *
     * @param model 模型名
     * @param system 系统提示
     * @param user 用户消息
     * @param tools 工具声明列表（OpenAI tools 数组），null/空时不携带 tools 字段
     * @param temperature 采样温度
     * @param maxTokens 最大输出 token 数
     * @param endpoint API 端点（如 https://api.deepseek.com/v1），必填具名参数
     * @param apiKey API Key，必填具名参数；空白或旧版演示 Key 抛 [ApiKeyMissingException]
     * @throws ApiKeyMissingException Key 未配置
     * @throws IOException HTTP 非 2xx（消息含 HTTP 状态码与响应片段）
     * @throws IllegalStateException 响应 JSON 解析失败或缺 choices/message
     */
    suspend fun chat(
        model: String,
        system: String,
        user: String,
        tools: List<McpToolDef>? = null,
        temperature: Double = 0.7,
        maxTokens: Int = 2048,
        endpoint: String,
        apiKey: String,
    ): ChatResult {
        requireValidKey(apiKey)
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(system))
                }
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(user))
                }
            }
            if (!tools.isNullOrEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", JsonPrimitive("function"))
                            putJsonObject("function") {
                                put("name", JsonPrimitive(tool.name))
                                put("description", JsonPrimitive(tool.description))
                                put("parameters", tool.inputSchema)
                            }
                        }
                    }
                }
            }
            put("temperature", JsonPrimitive(temperature))
            put("max_tokens", JsonPrimitive(maxTokens))
        }
        val request = Request.Builder()
            .url(joinUrl(endpoint, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return parseChatResponse(awaitBody(request))
    }

    /** 解析 chat.completions 响应：content 可为 null（如仅返回 tool_calls）。 */
    private fun parseChatResponse(body: String): ChatResult {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("AI 响应不是合法 JSON（${e.message}）。原始响应：${body.take(300)}", e)
        }
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw IllegalStateException("AI 响应缺少 choices[0].message。原始响应：${body.take(300)}")
        val content = extractContent(message["content"])
        val toolCalls = (message["tool_calls"] as? JsonArray)?.mapNotNull { item ->
            // 单条 tool_call 畸形（非对象 / 缺 function / arguments 非字符串）不应拖垮整次解析，
            // 用 runCatching 跳过坏条目，保留其余合法调用。
            runCatching {
                val call = item.jsonObject
                val fn = call["function"]?.jsonObject
                ToolCall(
                    id = call["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = fn?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    argsJson = extractArgs(fn?.get("arguments")),
                )
            }.getOrNull()
        } ?: emptyList()
        return ChatResult(content = content, toolCalls = toolCalls)
    }

    /** 容错提取 message.content：标准为字符串（可为 null）；兼容缺失字段与部分提供商的 content-block 数组。 */
    private fun extractContent(el: JsonElement?): String? = when (el) {
        null, is JsonNull -> null
        is JsonArray -> el.mapNotNull { block -> (block as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
            .ifBlank { null }
        is JsonPrimitive -> el.contentOrNull
        else -> null
    }

    /** 容错提取 function.arguments：标准为 JSON 字符串；兼容厂商直接把对象/数组放进来，返回其 JSON 文本。 */
    private fun extractArgs(el: JsonElement?): String = when (el) {
        null, is JsonNull -> "{}"
        is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}"
        is JsonArray, is JsonObject -> el.toString()
        else -> "{}"
    }

    /**
     * Key 校验：空白即抛 [ApiKeyMissingException]；同时防御性拒绝旧版占位演示 Key
     * （部分用户 DataStore 可能残留该值），确保演示 Key 必然不可用。
     */
    private fun requireValidKey(apiKey: String) {
        if (apiKey.isBlank() || apiKey == LEGACY_DEMO_KEY) {
            throw ApiKeyMissingException("未配置有效的 API Key，请前往设置页填写真实 Key（BaseURL/模型/Key）")
        }
    }

    /** 以可取消协程方式执行请求；非 2xx 抛含 HTTP 状态码的可读异常。 */
    private suspend fun awaitBody(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        cont.resumeWith(Result.failure(IOException("HTTP ${resp.code}: ${body.take(300)}")))
                    } else {
                        cont.resumeWith(Result.success(body))
                    }
                }
            }
        })
    }

    private fun joinUrl(endpoint: String, path: String): String {
        val base = endpoint.trim().trimEnd('/')
        return if (base.endsWith("/$path")) base else "$base/$path"
    }

    private companion object {
        /** 旧版占位演示 Key——仅用于拒绝残留值，不再是内置可用 Key。 */
        const val LEGACY_DEMO_KEY = "sk-injector-built-in-demo-key"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
