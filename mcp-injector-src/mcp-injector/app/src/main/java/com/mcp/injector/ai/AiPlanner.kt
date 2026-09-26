package com.mcp.injector.ai

import com.mcp.injector.apk.ApkInfo
import com.mcp.injector.apk.DexOps
import com.mcp.injector.data.AiRepository
import com.mcp.injector.data.ApiKeyMissingException
import com.mcp.injector.data.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.util.Locale

/**
 * 注入方案规划器（AI 层）。
 *
 * 容错策略（P0）：
 * 1. 容忍 ```json 代码块包裹、前后缀文本、字段缺失（ignoreUnknownKeys + isLenient，并截取首个 `{` 到末个 `}`）。
 * 2. 请求失败或解析失败时，把错误信息回灌进 prompt 重试，最多 [MAX_RETRIES] 次。
 * 3. 重试仍失败才降级为内置保守方案：此时不再只给通用 open_app，而是用清单组件
 *    （见 [componentTools]）确定性地生成该应用专属的工具集，保证「每个应用都有专属工具」；
 *    错误写入 [InjectionPlan.lastError]、重试次数写入 [InjectionPlan.retryCount]——绝不静默。
 *    模型虽有产出但工具数过少（< [MIN_MODEL_TOOLS]）时，同样用清单组件补齐。
 * 4. 配置类错误（[ApiKeyMissingException]，未填 Key / 填了旧版演示 Key）不属于"解析失败"：
 *    重试与降级均无意义，直接上抛由调用方引导用户到设置页，而不是静默降级。
 * 5. 模型若只返回 tool_calls（content 为空）：规划阶段没有可执行 MCP 工具的运行时
 *    （工具 target 只有注入目标应用后才由 bridge/ToolExecutor 执行），因此不执行这些调用，
 *    仅把工具名与参数序列化为文本回灌进下一次 prompt，引导模型直接产出 JSON（见 describeToolCalls）。
 */
class AiPlanner(private val repo: AiRepository) {

    /** 解析失败后的最大重试次数（<=2，即最多 3 次尝试）。 */
    private val maxRetries = 2

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val toolNameRegex = Regex("^[a-z][a-z0-9_]{1,40}$")

    /**
     * 规划注入方案。
     *
     * @param info 目标 APK 的解析信息（summaryForModel 供给模型）
     * @param dexHints dex 扫描出的候选类 + 方法签名（供模型判断哪些方法可封装成工具）
     * @param settings 用户设置（endpoint/apiKey/model/端口/策略偏好）
     * @param extraNotes 追加进提示词的自由文本（如诊断回传的 errors/recommendations）
     * @return 调整后的 [InjectionPlan]；全部失败时降级并携带 lastError/retryCount
     */
    suspend fun plan(
        info: ApkInfo,
        dexHints: List<DexOps.ClassHint>,
        settings: AppSettings,
        extraNotes: List<String> = emptyList(),
    ): InjectionPlan {
        var lastError: String? = null
        // 上一轮模型请求调用的工具（函数名+参数）序列化文本，回灌进下一次 prompt。
        var toolCallHint: String? = null
        var failedAttempts = 0
        repeat(maxRetries + 1) { attempt ->
            try {
                val result = repo.chat(
                    model = settings.model,
                    system = systemPrompt(settings),
                    user = userPrompt(info, dexHints, settings, lastError, toolCallHint, extraNotes),
                    endpoint = settings.endpoint,
                    apiKey = settings.apiKey,
                )
                val raw = result.content?.takeIf { it.isNotBlank() }
                if (raw == null) {
                    // 规划阶段没有可执行 MCP 工具的运行环境：工具（target）只有在注入目标应用后
                    // 才由 bridge/ToolExecutor 执行，规划时目标应用尚未注入/安装，无法执行 tool_calls。
                    // 因此不臆造执行逻辑，改为把工具调用（函数名+参数）序列化为文本回灌，引导模型产出 JSON。
                    toolCallHint = describeToolCalls(result.toolCalls)
                    throw IllegalStateException(
                        if (toolCallHint != null) {
                            "模型仅返回 tool_calls 未返回规划 JSON（content 为空）"
                        } else {
                            "模型返回的 content 为空"
                        }
                    )
                }
                return parseAndAdjust(raw, info, settings).copy(retryCount = failedAttempts)
            } catch (e: ApiKeyMissingException) {
                // 配置错误（未填/填了旧版演示 Key）：重试与降级均无意义，直接上抛，
                // 由调用方把可读信息交给 UI，按契约引导用户前往设置页填写真实 Key，
                // 而不是静默降级成"仅 open_app"的弱计划。
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                failedAttempts = attempt + 1
            }
        }
        // 全部尝试失败：实际发生的重试次数 = 失败尝试数 - 1（最多 maxRetries 次）
        val retries = (failedAttempts - 1).coerceIn(0, maxRetries)
        return fallbackPlan(info, settings, lastError ?: "未知错误", retries)
    }

    /**
     * 把模型返回的 tool_calls 序列化为可读文本（函数名 + 原始参数 JSON）。
     * 无调用时返回 null。仅作为提示回灌，不代表这些工具真的被执行过。
     */
    private fun describeToolCalls(calls: List<ToolCall>): String? {
        if (calls.isEmpty()) return null
        return calls.joinToString("\n") { call ->
            val name = call.name.ifBlank { "(未命名工具)" }
            val args = call.argsJson.ifBlank { "{}" }
            "- 工具 $name，参数：$args"
        }
    }

    /** 剥离代码块/前后缀文本，截取首个 `{` 到末个 `}` 后解码并做防御性调整。 */
    private fun parseAndAdjust(raw: String, info: ApkInfo, settings: AppSettings): InjectionPlan {
        val cleaned = raw
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalArgumentException("没有找到 JSON 内容：${raw.take(200)}")
        }
        val plan = try {
            json.decodeFromString<InjectionPlan>(cleaned.substring(start, end + 1))
        } catch (e: Exception) {
            throw IllegalArgumentException("规划 JSON 解析失败：${e.message}", e)
        }
        // 策略：仅当模型选择 service 且用户偏好 service 时才用 service，否则统一 hook
        val strategy = if (plan.strategy == "service" && settings.preferServiceStrategy) "service" else "hook"
        val modelTools = plan.tools
            .filter { toolNameRegex.matches(it.name) }
            .take(MAX_TOOLS)
        if (modelTools.isEmpty()) {
            throw IllegalArgumentException("工具列表为空或工具名不合法")
        }
        // 模型产出的工具过少时，用清单组件做确定性补齐：保证「每个应用都有专属工具」，
        // 不因模型能力或上下文波动退化成只有通用工具。同名以模型为准。
        val tools = if (modelTools.size < MIN_MODEL_TOOLS) {
            val seen = modelTools.mapTo(HashSet()) { it.name }
            (modelTools + componentTools(info).filter { it.name !in seen }).take(MAX_TOOLS)
        } else {
            modelTools
        }
        val wire = ConnectionMode.fromWire(plan.connectionMode).wire
        val port = if (plan.port in 1024..9999) plan.port else settings.mcpPort
        return plan.copy(strategy = strategy, connectionMode = wire, port = port, tools = tools)
    }

    /** 降级方案：用清单组件自动生成专属工具；一个都生成不出来才退回 open_app。绝不静默。 */
    private fun fallbackPlan(info: ApkInfo, settings: AppSettings, lastError: String, retryCount: Int): InjectionPlan {
        val launcherActivity = info.launcherActivity ?: "${info.packageName}.MainActivity"
        val openApp = McpToolDef(
            name = "open_app",
            description = "打开 ${info.packageName} 的主界面",
            inputSchema = simpleSchema(),
            target = ToolTarget(kind = "start_activity", component = launcherActivity),
        )
        val byComponent = componentTools(info)
        val tools = when {
            byComponent.isEmpty() -> listOf(openApp)
            byComponent.none { it.name == "open_app" } -> (listOf(openApp) + byComponent).take(MAX_TOOLS)
            else -> byComponent
        }
        return InjectionPlan(
            strategy = if (settings.preferServiceStrategy) "service" else "hook",
            reason = "模型规划不可用，已降级为按清单组件自动生成的保守工具集（仅启动与只读类操作）。",
            connectionMode = ConnectionMode.StreamableHttp.wire,
            port = settings.mcpPort,
            tools = tools,
            lastError = lastError,
            retryCount = retryCount,
        )
    }

    /**
     * 确定性工具生成：直接用清单组件造工具，**完全不依赖模型**。
     *
     * 模型不可用（未配 Key 之外的原因：网络、返回非法 JSON、工具名全不合法）时，
     * 由它保证「每个应用都有专属工具列表」，而不是只剩一条通用 open_app。
     *
     * 只生成启动类与只读类动作，不生成任何写操作：
     * - 启动器 → `open_app`；其余 activity → `open_<组件名>`（start_activity）；
     * - exported service → `start_<组件名>`（start_service）；
     * - exported receiver → `send_<组件名>`（broadcast，带上其声明的首个 action）；
     * - 有 authorities 的 provider → `query_<组件名>`（content_call，固定 contentMethod=query，
     *   刻意不生成 insert/update/delete，避免 AI 判断失误时改坏用户数据）。
     *
     * 工具名按组件简单类名转下划线；总数受 [MAX_TOOLS] 限制；名字不合法或不满足
     * [toolNameRegex] 的组件直接丢弃。
     */
    private fun componentTools(info: ApkInfo): List<McpToolDef> {
        val out = linkedMapOf<String, McpToolDef>()
        val label = info.appLabel?.takeIf { it.isNotBlank() } ?: info.packageName
        val launcher = info.launcherActivity

        if (launcher != null) {
            addComponentTool(
                out,
                McpToolDef(
                    name = "open_app",
                    description = "打开 $label 的主界面",
                    inputSchema = simpleSchema(),
                    target = ToolTarget(kind = "start_activity", component = launcher),
                ),
            )
        }

        for (component in info.activities) {
            if (component.kind != "activity" && component.kind != "activity-alias") continue
            // activity-alias 实际启动的是 targetActivity
            val target = component.targetActivity ?: component.name
            if (target == launcher) continue
            addComponentTool(
                out,
                McpToolDef(
                    name = "open_${snakeName(target)}",
                    description = "打开 $label 的 ${shortName(target)} 页面",
                    inputSchema = simpleSchema(),
                    target = ToolTarget(kind = "start_activity", component = target),
                ),
            )
        }

        for (component in info.services) {
            if (component.exported != true) continue
            addComponentTool(
                out,
                McpToolDef(
                    name = "start_${snakeName(component.name)}",
                    description = "启动 $label 的服务 ${shortName(component.name)}",
                    inputSchema = simpleSchema(),
                    target = ToolTarget(kind = "start_service", component = component.name),
                ),
            )
        }

        for (component in info.receivers) {
            if (component.exported != true) continue
            addComponentTool(
                out,
                McpToolDef(
                    name = "send_${snakeName(component.name)}",
                    description = "向 $label 的接收器 ${shortName(component.name)} 发送广播",
                    inputSchema = simpleSchema(),
                    target = ToolTarget(
                        kind = "broadcast",
                        component = component.name,
                        action = component.actions.firstOrNull(),
                    ),
                ),
            )
        }

        for (component in info.providers) {
            if (component.authorities.isNullOrBlank()) continue
            addComponentTool(
                out,
                McpToolDef(
                    name = "query_${snakeName(component.name)}",
                    description = "读取 $label 的数据提供者 ${shortName(component.name)}",
                    inputSchema = simpleSchema(),
                    target = ToolTarget(
                        kind = "content_call",
                        contentMethod = "query",
                        authorities = component.authorities,
                    ),
                ),
            )
        }

        return out.values.toList()
    }

    /** 收下一条确定性工具：总数受 [MAX_TOOLS] 限制，名字不合法则丢弃，同名只留先到的。 */
    private fun addComponentTool(out: MutableMap<String, McpToolDef>, tool: McpToolDef) {
        if (out.size >= MAX_TOOLS) return
        if (!toolNameRegex.matches(tool.name)) return
        out.putIfAbsent(tool.name, tool)
    }

    /** 组件完整类名 → 工具名片段（小写下划线，截断到 28 字符以留出前缀余量）。 */
    private fun snakeName(className: String): String = className
        .substringAfterLast('.')
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .lowercase(Locale.ROOT)
        .trim('_')
        .take(28)

    /** 组件简单类名（用于工具描述）。 */
    private fun shortName(className: String): String = className.substringAfterLast('.')

    private fun systemPrompt(settings: AppSettings): String = """
        |
        |你是安卓应用集成专家，任务是把一个安卓安装包的能力接入 MCP（Model Context Protocol）。
        |你会收到安装包的清单摘要，以及 dex 扫描出的候选类及其方法签名，请规划注入方案并产出工具注册表。
        |
        |只输出一个 JSON 对象，不要输出任何解释、markdown 或多余文本。结构如下：
        |{
        |  "strategy": "hook 或 service",
        |  "reason": "一句话说明为什么选这个连接思路（中文）",
        |  "connectionMode": "streamable-http 或 sse",
        |  "port": ${settings.mcpPort},
        |  "tools": [
        |    {
        |      "name": "小写下划线工具名，2 到 40 个字符",
        |      "description": "一句中文说明这个工具做什么",
        |      "inputSchema": {"type":"object","properties":{...},"required":[...]},
        |      "target": {
        |        "kind": "start_activity | broadcast | start_service | content_call | method",
        |        "action": "可选，intent action",
        |        "component": "可选，完整类名",
        |        "uri": "可选，data uri",
        |        "contentMethod": "content_call 时: call/insert/query/update/delete",
        |        "methodClass": "method 时的类名",
        |        "methodName": "method 时的方法名",
        |        "extras": {"参数名": "string|int|long|float|bool"},
        |        "methodArgTypes": ["string","int"]
        |      }
        |    }
        |  ]
        |}
        |
        |映射规则：
        |1. 带显式 action 的 activity 优先用 start_activity，把 action 填进 target.action；没有 action 的用 component 指向完整类名。
        |2. exported 的 receiver 映射为 broadcast，exported 的 service 映射为 start_service。
        |3. provider 的 authorities 映射为 content_call。
        |4. method 只在候选类的方法签名里有明显线索时使用：把类名填进 target.methodClass、方法名填进
        |   target.methodName、参数类型按顺序填进 target.methodArgTypes（用 int/long/float/bool/string 表示）。
        |   同时 inputSchema 里的参数名必须按顺序命名为 p0、p1…（第一个参数也可直接叫方法名），
        |   因为执行器按 p0/p1 取值；参数个数要与方法签名一致。
        |   优先选无参或只有 1 到 2 个简单参数的 public 方法；宁可少暴露，也不要把看不懂语义的方法当工具。
        |5. 每个工具参数不超过 6 个，类型用 JSON Schema 标准类型。
        |6. 工具数量控制在 3 到 8 个，优先覆盖高频能力，不要把所有组件都塞进来。
        |7. 涉及支付、隐私、删除数据等敏感能力的组件一律不要暴露。
        |8. connectionMode 优先 streamable-http；只有当目标应用明确有 SSE 需求时才用 sse。
        """.trimMargin()

    private fun userPrompt(
        info: ApkInfo,
        dexHints: List<DexOps.ClassHint>,
        settings: AppSettings,
        lastError: String?,
        toolCallHint: String?,
        extraNotes: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append("安装包分析结果：\n")
        sb.append(info.summaryForModel()).append('\n')
        if (dexHints.isNotEmpty()) {
            sb.append("候选类与方法签名（来自 dex 扫描，可能值得封装成工具）：\n")
            // 规模已由 DexOps 按模型上下文预算裁剪（见 DexHintBudget），此处不再二次截断。
            dexHints.forEach { hint ->
                sb.append("  - ").append(hint.className).append('\n')
                hint.methods.forEach { sb.append("      ").append(it).append('\n') }
            }
        }
        if (extraNotes.isNotEmpty()) {
            sb.append("补充诊断信息：\n")
            extraNotes.forEach { sb.append("  - ").append(it).append('\n') }
        }
        sb.append('\n')
        sb.append("注入偏好端口：").append(settings.mcpPort).append('\n')
        if (settings.preferServiceStrategy) {
            sb.append("用户偏好 service 声明式注入，请优先考虑 strategy=service。\n")
        }
        if (lastError != null) {
            sb.append("上一次规划失败，错误信息如下，请修正后重新输出完整 JSON：\n")
            sb.append(lastError).append('\n')
        }
        if (toolCallHint != null) {
            sb.append("你上一轮请求调用了以下工具，但当前规划阶段没有可执行这些工具的运行环境")
            sb.append("（工具只有在方案注入目标应用后才可用）：\n")
            sb.append(toolCallHint).append('\n')
            sb.append("请不要再请求调用工具，直接据此输出完整的规划 JSON。\n")
        }
        sb.append("请输出 JSON。\n")
        return sb.toString()
    }

    /** 降级 open_app 工具的极简 JSON Schema。 */
    private fun simpleSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    }

    /**
     * 依据用户反馈的问题（可带目标侧崩溃栈与诊断回传）让 AI 产出**注入策略补丁**。
     *
     * 与 [plan] 同样容忍代码块包裹/前后缀文本；解析失败把错误回灌进 prompt 重试。
     * 配置类错误（[ApiKeyMissingException]）直接上抛，由调用方引导到设置页。
     * 全部失败时抛异常（不静默降级）：改策略是用户显式发起的操作，必须给出明确结果。
     */
    suspend fun reviseStrategy(
        packageName: String,
        appVersion: String?,
        current: CurrentStrategy,
        problem: String,
        crashLog: String?,
        diagnoseJson: JSONObject?,
        history: List<ChatTurn>,
        settings: AppSettings,
    ): StrategyRevision {
        var lastError: String? = null
        repeat(maxRetries + 1) {
            try {
                val result = repo.chat(
                    model = settings.model,
                    system = revisionSystemPrompt(),
                    user = revisionUserPrompt(
                        packageName = packageName,
                        appVersion = appVersion,
                        current = current,
                        problem = problem,
                        crashLog = crashLog,
                        diagnoseJson = diagnoseJson,
                        history = history,
                        lastError = lastError,
                    ),
                    endpoint = settings.endpoint,
                    apiKey = settings.apiKey,
                )
                val raw = result.content?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("模型未返回内容（content 为空）")
                return parseRevision(raw)
            } catch (e: ApiKeyMissingException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException("AI 策略调整失败：$lastError")
    }

    /** 解析补丁回复：剥离 ```json 包裹/前后缀文本后截取 JSON 对象。 */
    private fun parseRevision(raw: String): StrategyRevision {
        val cleaned = raw
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalArgumentException("没有找到 JSON 内容：${raw.take(200)}")
        }
        val env = try {
            json.decodeFromString<RevisionEnvelope>(cleaned.substring(start, end + 1))
        } catch (e: Exception) {
            throw IllegalArgumentException("补丁 JSON 解析失败：${e.message}", e)
        }
        return StrategyRevision(reply = env.reply, patch = env.patch)
    }

    private fun revisionSystemPrompt(): String = """
        |
        |你是安卓 MCP 注入工具的调试顾问。用户注入某个应用后遇到问题（如目标应用闪退、MCP 服务起不来、
        |工具缺失），你需要在「最小改动」原则下给出**注入策略补丁**，把问题解决掉。
        |
        |可调整的策略字段（只填需要改的；不需要改的字段请不要出现在 patch 里）：
        |- entryPoint: "provider"（默认；注入 ContentProvider，在 Application.onCreate 之前启动注入体，
        |              完全不改写目标 dex）或 "clinit"（改写启动类 <clinit> 插桩，有校验器风险）
        |- toastOnBoot: true / false  启动时是否弹 2 秒「模块已加载」提示
        |- strategy: "hook" / "service"  声明式注入策略
        |- connectionMode: "streamable-http" / "sse"
        |- port: 1024-9999
        |
        |判断规则：
        |1. 崩溃栈出现 VerifyError / ExceptionInInitializerError，或栈顶指向注入体
        |   （com.mcp.injector.bridge.*），或问题恰好发生在启动瞬间：把 entryPoint 改成 "provider"
        |   （最稳入口，不改目标 dex）；必要时把 toastOnBoot 设为 false 以排除提示干扰。
        |   反之，若当前 entryPoint 是 "clinit" 且出现启动闪退，几乎一定是它导致的。
        |2. 服务未监听 / 端口冲突 / 连不上：调整 port，或把 strategy 改成 "service"。
        |3. 工具缺失 / 工具列表为空 / 工具调用失败：这不是入口策略能解决的。**不要**在 patch 里填策略
|   字段，也不要在 reply 里让用户自己去改；在 reply 里说明你判断的原因，并写清「需要重新规划工具集」。
|   宿主会在「应用并重新注入」时用完整规划（带目标 dex 候选方法）自动重建工具集，用户只需点一下。
|   若同时怀疑入口/服务有问题，可一并给出策略字段。
|4. 判断与注入无关（目标应用自身 bug / 权限 / 网络）：patch 留空，在 reply 里明确说明，并给出
|   用户可以自行验证的操作步骤（不要只说「请调整注入策略」）。
        |
        |只输出一个 JSON 对象，不要输出 markdown 或任何多余文本：
        |{"reply": "中文说明（原因 + 预期效果 + 用户接下来要做什么）", "patch": {"entryPoint": "provider"}}
        """.trimMargin()

    private fun revisionUserPrompt(
        packageName: String,
        appVersion: String?,
        current: CurrentStrategy,
        problem: String,
        crashLog: String?,
        diagnoseJson: JSONObject?,
        history: List<ChatTurn>,
        lastError: String?,
    ): String {
        val sb = StringBuilder()
        sb.append("目标应用：").append(packageName)
        if (!appVersion.isNullOrBlank()) sb.append("（版本 ").append(appVersion).append("）")
        sb.append('\n')
        sb.append("当前注入策略：\n")
        sb.append("  - entryPoint=").append(current.entryPoint).append('\n')
        sb.append("  - toastOnBoot=").append(current.toastOnBoot).append('\n')
        sb.append("  - strategy=").append(current.strategy).append('\n')
        sb.append("  - connectionMode=").append(current.connectionMode).append('\n')
        sb.append("  - port=").append(current.port).append('\n')
        sb.append("用户反馈的问题：\n").append(problem.trim()).append('\n')
        if (!crashLog.isNullOrBlank()) {
            sb.append("目标应用崩溃日志（注入体在目标进程内采集的最后一段）：\n")
            sb.append(crashLog.takeLast(CRASH_HINT_LIMIT)).append('\n')
        }
        diagnoseJson?.let { diag ->
            val errors = diag.optJSONArray("errors")
            val recs = diag.optJSONArray("recommendations")
            if ((errors?.length() ?: 0) > 0 || (recs?.length() ?: 0) > 0) {
                sb.append("目标侧诊断：\n")
                if (errors != null) {
                    for (i in 0 until errors.length()) {
                        sb.append("  - 错误: ").append(errors.optString(i)).append('\n')
                    }
                }
                if (recs != null) {
                    for (i in 0 until recs.length()) {
                        sb.append("  - 建议: ").append(recs.optString(i)).append('\n')
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            sb.append("此前对话（从旧到新）：\n")
            history.takeLast(HISTORY_TURNS).forEach { turn ->
                sb.append("  ").append(if (turn.role == "user") "用户" else "你").append("：")
                    .append(turn.text.take(TURN_HINT_LIMIT)).append('\n')
            }
        }
        if (lastError != null) {
            sb.append("上一次回复解析失败：").append(lastError).append("。请修正后重新输出完整 JSON。\n")
        }
        sb.append("请输出 JSON。\n")
        return sb.toString()
    }

    private companion object {
        const val MAX_TOOLS = 16

        /**
         * 模型工具数低于该值时，用清单组件做确定性补齐。
         * 与提示词里「工具数量控制在 3 到 8 个」的下限对齐。
         */
        const val MIN_MODEL_TOOLS = 3

        /** 崩溃栈回灌上限（字符）：只取尾部，避免撑爆小上下文模型。 */
        const val CRASH_HINT_LIMIT = 4000

        /** 回灌的历史轮数与单轮截断长度。 */
        const val HISTORY_TURNS = 8
        const val TURN_HINT_LIMIT = 300
    }
}

/** 补丁回复的 JSON 外壳：{"reply": "...", "patch": {...}}。 */
@Serializable
private data class RevisionEnvelope(
    val reply: String = "",
    val patch: StrategyPatch = StrategyPatch(),
)
