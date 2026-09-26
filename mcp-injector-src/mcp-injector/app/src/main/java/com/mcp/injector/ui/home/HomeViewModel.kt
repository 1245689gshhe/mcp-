package com.mcp.injector.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcp.injector.InjectorApp
import com.mcp.injector.agent.AgentProbe
import com.mcp.injector.agent.InjectedApp
import com.mcp.injector.agent.InjectedAppScanner
import com.mcp.injector.agent.InjectedAppsStore
import com.mcp.injector.ai.AiPlanner
import com.mcp.injector.ai.ChatTurn
import com.mcp.injector.ai.CurrentStrategy
import com.mcp.injector.ai.StrategyPatch
import com.mcp.injector.apk.ApkInjector
import com.mcp.injector.apk.ApkParser
import com.mcp.injector.apk.DexOps
import com.mcp.injector.data.AiRepository
import com.mcp.injector.data.ApiKeyMissingException
import com.mcp.injector.data.ChatHistoryStore
import com.mcp.injector.data.ChatSession
import com.mcp.injector.data.Project
import com.mcp.injector.data.ProjectsStore
import com.mcp.injector.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 主页 ViewModel（任务 C 核心）。
 *
 * 对齐反编译 HomeViewModel 的公开契约（AndroidViewModel + state/events + refresh/importApk/
 * deleteProject/outputFor/iconOf），并新增任务 C 的「已注入应用」：
 * - [UiState.injectedApps] = 注入历史（[InjectedAppsStore]）+ 同签名扫描（[InjectedAppScanner]）；
 * - [importApk] 全流程：复制 → 解析 → 预检规划 → 一次注入 → 更新工程状态 → 刷新已注入列表。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val store: ProjectsStore = (app as InjectorApp).projectsStore
    private val settingsRepo: SettingsRepository = (app as InjectorApp).settingsRepository
    private val injectedStore: InjectedAppsStore = (app as InjectorApp).injectedAppsStore
    private val chatStore: ChatHistoryStore = (app as InjectorApp).chatHistoryStore

    private val ai = AiRepository()
    private val planner = AiPlanner(ai)
    private val injector = ApkInjector(app)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    /** 主页 UI 状态：原工程列表 + 已注入应用（历史+扫描）+ 当前模型 + 忙碌标记 + 策略对话。 */
    data class UiState(
        val projects: List<Project> = emptyList(),
        val injectedApps: List<InjectedApp> = emptyList(),
        val model: String = "",
        val busy: Boolean = false,
        /** 非 null 表示策略对话弹窗打开中。 */
        val strategyChat: StrategyChatState? = null,
    )

    /**
     * AI 策略对话状态（长按工程/已注入卡片打开）。
     *
     * 用户描述问题 → [sendChatProblem] 让 AI 产出策略补丁 → [applyChatPatchAndReinject]
     * 把补丁合并进方案并重新注入。目标侧诊断/崩溃栈在 [openStrategyChat] 时并行取回，
     * 作为 AI 判断依据（目标未响应时仅凭用户描述）。
     */
    data class StrategyChatState(
        val packageName: String,
        val appName: String,
        /** 当前会话 id（历史记录的主键）。 */
        val sessionId: String = UUID.randomUUID().toString(),
        val turns: List<ChatTurn> = emptyList(),
        val input: String = "",
        /** AI 请求进行中。 */
        val thinking: Boolean = false,
        /** 重新注入进行中。 */
        val applying: Boolean = false,
        /** 已累积的策略补丁（多次对话的并集，新值覆盖旧值）。 */
        val pendingPatch: StrategyPatch = StrategyPatch(),
        /**
         * 判定为「工具缺失/工具列表为空」类问题：应用时会用完整规划重建工具集。
         * 这类问题 AI 无法仅凭策略字段解决，必须有这个显式标记，否则「应用并重新注入」会被
         * 「无改动」挡住，表现为用户点不动、AI 只会让人手动改。
         */
        val needsToolReplan: Boolean = false,
        /** 待应用的改动点（中文），空表示 AI 未给出可执行改动。 */
        val changes: List<String> = emptyList(),
        /** 目标侧上下文说明（诊断/崩溃取回情况）。 */
        val contextNote: String = "",
        val error: String? = null,
        /** cmd=diagnose 回执（供 AI 提示词）；null=未响应。 */
        val diagnoseJson: JSONObject? = null,
        /** cmd=crash 回执中的堆栈文本；null=未取到。 */
        val crashLog: String? = null,
        /** 是否正在展示历史记录面板。 */
        val historyOpen: Boolean = false,
        /** 该包名的历史会话（按最近更新倒序）。 */
        val sessions: List<ChatSession> = emptyList(),
    )

    init {
        refresh()
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _state.update { it.copy(model = s.model) }
            }
        }
    }

    /** 刷新工程列表与已注入应用（历史 + 同签名扫描）。 */
    fun refresh() {
        val app = getApplication<Application>()
        val scanned = InjectedAppScanner.scan(app, injectedStore)
        _state.update {
            it.copy(
                projects = store.loadAll(),
                injectedApps = injectedStore.loadAll() + scanned,
            )
        }
    }

    /** 导入并注入 APK：复制 → 解析 → 扫描提示 → AI 规划 → 一次注入 → 记录历史。 */
    fun importApk(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            var proj: Project? = null
            try {
                val app = getApplication<Application>()

                val copied = withContext(Dispatchers.IO) { copyToImports(app, uri) }
                val info = withContext(Dispatchers.IO) { ApkParser.parse(copied) }
                val iconRel = withContext(Dispatchers.IO) { extractIcon(copied) }

                proj = Project(
                    appName = info.appLabel ?: info.packageName,
                    packageName = info.packageName,
                    versionName = info.versionName,
                    iconFile = iconRel,
                )
                store.upsert(proj)
                refresh()

                proj = proj.copy(status = Project.Status.ANALYZING)
                store.upsert(proj)
                refresh()
                // 先取设置：dex 扫描规模由模型上下文窗口决定（小窗口别把提示词撑爆）
                val settings = settingsRepo.current()
                val hints = withContext(Dispatchers.IO) {
                    DexOps.scanHints(copied, DexOps.DexHintBudget.forContext(settings.contextTokens))
                }

                proj = proj.copy(status = Project.Status.PLANNING)
                store.upsert(proj)
                refresh()
                val plan = planner.plan(info, hints, settings)

                proj = proj.copy(status = Project.Status.INJECTING)
                store.upsert(proj)
                refresh()
                val output = File(store.outputDir(), sanitize(info.packageName) + "-mcp.apk")
                withContext(Dispatchers.IO) { injector.inject(copied, info, plan, output) }

                proj = proj.copy(
                    status = Project.Status.DONE,
                    outputRelative = output.relativeTo(app.filesDir).path,
                    plan = plan,
                )
                store.upsert(proj)
                refresh()
                _events.tryEmit("注入完成：${proj.appName}")
            } catch (t: Throwable) {
                // 注入失败的异常往往是"裸 message"，缺环因；这里把完整堆栈落盘到崩溃日志，
                // 供诊断。同时拼接 cause 摘要展示给用户。
                runCatching {
                    com.mcp.injector.crash.CrashLogger.save(
                        getApplication(), Thread.currentThread(), t,
                    )
                }
                val detail = buildString {
                    append(t.message ?: t.javaClass.simpleName)
                    var c = t.cause
                    var depth = 0
                    while (c != null && depth < 4) {
                        append(" <- ")
                        append(c.message ?: c.javaClass.simpleName)
                        c = c.cause
                        depth++
                    }
                }
                if (proj != null) {
                    store.upsert(proj.copy(status = Project.Status.FAILED, error = detail))
                    refresh()
                }
                _events.tryEmit("注入失败：$detail")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun deleteProject(id: String) {
        val project = store.loadAll().firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(id) }
            refresh()
            _events.tryEmit("已删除 ${project.appName}")
        }
    }

    /** 注入产物 APK 文件（filesDir 相对路径解析）。 */
    fun outputFor(project: Project): File? =
        project.outputRelative?.let { File(getApplication<Application>().filesDir, it) }

    /** 工程图标文件。 */
    fun iconOf(project: Project): File? =
        project.iconFile?.let { File(getApplication<Application>().filesDir, it) }

    // ---------------------------------------------------------------------
    // AI 策略对话（长按工程 / 已注入卡片打开）
    // ---------------------------------------------------------------------

    /** 打开策略对话并异步取回目标侧诊断/崩溃栈，作为 AI 判断现状的依据。 */
    fun openStrategyChat(packageName: String) {
        if (packageName.isEmpty()) return
        val record = injectedStore.find(packageName)
        val project = store.loadAll().firstOrNull { it.packageName == packageName }
        val appName = record?.appName ?: project?.appName ?: packageName
        // 续上最近一次会话（历史记录第一等公民），没有则新开一条
        val sessions = chatStore.listFor(packageName)
        val latest = sessions.firstOrNull()
        _state.update {
            it.copy(
                strategyChat = StrategyChatState(
                    packageName = packageName,
                    appName = appName,
                    sessionId = latest?.id ?: UUID.randomUUID().toString(),
                    turns = latest?.turns ?: emptyList(),
                    contextNote = "正在取回目标侧诊断与崩溃栈…",
                    sessions = sessions,
                ),
            )
        }
        viewModelScope.launch {
            val appCtx = getApplication<Application>()
            val diagnose = probeJson(appCtx, packageName, "diagnose")
            val crashLog = probeJson(appCtx, packageName, "crash")
                ?.optString("log")?.takeIf { s -> s.isNotBlank() }
            val note = buildContextNote(diagnose, crashLog)
            _state.update { state ->
                val chat = state.strategyChat ?: return@update state
                if (chat.packageName != packageName) return@update state
                state.copy(
                    strategyChat = chat.copy(
                        contextNote = note,
                        diagnoseJson = diagnose,
                        crashLog = crashLog,
                    ),
                )
            }
        }
    }

    fun dismissStrategyChat() {
        _state.update { it.copy(strategyChat = null) }
    }

    // ---- 对话历史记录 ----

    /** 打开/关闭历史记录面板（打开时刷新列表）。 */
    fun toggleChatHistory() {
        _state.update { state ->
            val c = state.strategyChat ?: return@update state
            val open = !c.historyOpen
            state.copy(
                strategyChat = c.copy(
                    historyOpen = open,
                    sessions = if (open) chatStore.listFor(c.packageName) else c.sessions,
                    error = null,
                ),
            )
        }
    }

    /** 打开某条历史会话，作为「当前对话」继续（AI 只回灌这一条会话的上下文）。 */
    fun openChatSession(sessionId: String) {
        val session = chatStore.find(sessionId) ?: return
        _state.update { state ->
            val c = state.strategyChat ?: return@update state
            if (c.packageName != session.packageName) return@update state
            state.copy(
                strategyChat = c.copy(
                    sessionId = session.id,
                    turns = session.turns,
                    input = "",
                    historyOpen = false,
                    // 换了会话，上一会话累积的补丁不能带过来
                    pendingPatch = StrategyPatch(),
                    changes = emptyList(),
                    needsToolReplan = false,
                    error = null,
                ),
            )
        }
    }

    /** 新开一条会话（当前对话不再续写历史）。 */
    fun newChatSession() {
        _state.update { state ->
            val c = state.strategyChat ?: return@update state
            state.copy(
                strategyChat = c.copy(
                    sessionId = UUID.randomUUID().toString(),
                    turns = emptyList(),
                    input = "",
                    historyOpen = false,
                    pendingPatch = StrategyPatch(),
                    changes = emptyList(),
                    needsToolReplan = false,
                    error = null,
                ),
            )
        }
    }

    /** 删除一条历史会话；删的是当前会话时顺带新开一条空会话。 */
    fun deleteChatSession(sessionId: String) {
        chatStore.remove(sessionId)
        _state.update { state ->
            val c = state.strategyChat ?: return@update state
            val currentDeleted = c.sessionId == sessionId
            state.copy(
                strategyChat = c.copy(
                    sessions = chatStore.listFor(c.packageName),
                    sessionId = if (currentDeleted) UUID.randomUUID().toString() else c.sessionId,
                    turns = if (currentDeleted) emptyList() else c.turns,
                    pendingPatch = if (currentDeleted) StrategyPatch() else c.pendingPatch,
                    changes = if (currentDeleted) emptyList() else c.changes,
                    needsToolReplan = if (currentDeleted) false else c.needsToolReplan,
                ),
            )
        }
    }

    /** 把当前会话落盘（标题取首条用户反馈，便于在历史列表里辨认）。 */
    private fun persistChat(sessionId: String, packageName: String, turns: List<ChatTurn>) {
        if (turns.isEmpty()) return
        val title = turns.firstOrNull { it.role == ROLE_USER }?.text
            ?.replace('\n', ' ')?.trim()?.take(40)?.ifBlank { "新对话" } ?: "新对话"
        val session = ChatSession(
            id = sessionId,
            packageName = packageName,
            title = title,
            turns = turns,
        )
        viewModelScope.launch(Dispatchers.IO) { chatStore.upsert(session) }
    }

    /** 对话输入框内容更新（UI 直接回写）。 */
    fun updateChatInput(value: String) {
        _state.update { it.copy(strategyChat = it.strategyChat?.copy(input = value)) }
    }

    /**
     * 发送问题：追加用户消息 → AiPlanner.reviseStrategy → 追加 AI 回复并累积策略补丁。
     *
     * AI 之外还叠了一层**本地判定**（[detectToolIssue] / [fallbackPatch]）：用户反馈「工具列表
     * 没有出现工具」时，无论 AI 是否给出补丁，都会把这次调整标记为「需要重建工具集」，
     * 保证「应用并重新注入」始终可点、且真的会改动产物；AI 若一个字段都没给，本地兜底补丁顶上，
     * 避免出现「AI 压根不调整策略」的死路。
     */
    fun sendChatProblem() {
        val chat = _state.value.strategyChat ?: return
        val problem = chat.input.trim()
        if (problem.isEmpty() || chat.thinking || chat.applying) return
        val packageName = chat.packageName
        val sessionId = chat.sessionId
        // 先留存历史（不含本条），问题本身由 reviseStrategy 的 problem 参数单独回灌，
        // 避免同一句话在提示词里出现两次。
        val history = chat.turns
        val pendingPatch = chat.pendingPatch
        val userTurns = chat.turns + ChatTurn(ROLE_USER, problem)
        _state.update { state ->
            val c = state.strategyChat ?: return@update state
            state.copy(
                strategyChat = c.copy(input = "", thinking = true, error = null, turns = userTurns),
            )
        }
        persistChat(sessionId, packageName, userTurns)
        viewModelScope.launch {
            try {
                val record = injectedStore.find(packageName)
                val project = store.loadAll().firstOrNull { it.packageName == packageName }
                val current = buildCurrentStrategy(record, project)
                val revision = planner.reviseStrategy(
                    packageName = packageName,
                    appVersion = record?.versionName ?: project?.versionName,
                    current = current,
                    problem = problem,
                    crashLog = _state.value.strategyChat?.crashLog,
                    diagnoseJson = _state.value.strategyChat?.diagnoseJson,
                    history = history,
                    settings = settingsRepo.current(),
                )
                // —— 本地判定：AI 之外的第二道保险 ——
                val toolIssue = detectToolIssue(
                    problem = problem,
                    diagnoseJson = _state.value.strategyChat?.diagnoseJson,
                    toolsCount = current.toolsCount,
                )
                val aiPatch = mergePatch(pendingPatch, revision.patch)
                // AI 一个字段都没给时用内置判定兜底（否则用户点不出任何改动）
                val fallback = if (aiPatch.isEmpty()) {
                    fallbackPatch(problem, _state.value.strategyChat?.crashLog, current)
                } else {
                    null
                }
                val finalPatch = fallback?.let { mergePatch(aiPatch, it) } ?: aiPatch
                val changes = ArrayList<String>()
                changes.addAll(finalPatch.describeChanges(current))
                if (toolIssue) {
                    changes.add("工具集：应用时自动重新规划（当前 ${current.toolsCount} 个）")
                }
                val reply = buildString {
                    append(revision.reply.trim())
                    if (fallback != null && !fallback.isEmpty()) {
                        append("\n\n（AI 未给出具体改动，已按内置判定补充：")
                        append(fallback.describeChanges(current).joinToString("；"))
                        append("）")
                    }
                    if (toolIssue) {
                        append("\n\n已判定为工具列表问题：点「应用并重新注入」会按目标应用重新规划工具集，")
                        append("生成新的注入版本（原版本自动留一份「备份」可回退），无需你手动改。")
                    }
                    if (changes.isEmpty()) {
                        append("\n\n这次没有判断出需要调整的注入策略。请补充关键信息再发一次：")
                        append("① 问题出现的时机（一打开就崩 / 用着才崩 / 只是工具列表为空）；")
                        append("② 目标应用里是否弹出过「模块已加载」提示；③ 管理页「运行状态」里的诊断结果。")
                    }
                }
                val assistantTurns = userTurns + ChatTurn(ROLE_ASSISTANT, reply)
                _state.update { state ->
                    val c = state.strategyChat ?: return@update state
                    if (c.packageName != packageName || c.sessionId != sessionId) return@update state
                    state.copy(
                        strategyChat = c.copy(
                            thinking = false,
                            pendingPatch = finalPatch,
                            needsToolReplan = toolIssue,
                            changes = changes,
                            turns = assistantTurns,
                        ),
                    )
                }
                persistChat(sessionId, packageName, assistantTurns)
            } catch (e: ApiKeyMissingException) {
                failChat(packageName, e.message ?: "未配置 API Key，请先到设置页填写")
            } catch (t: Throwable) {
                failChat(packageName, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * 应用补丁并重新注入。
     *
     * 一次动作产出**两份**记录，保证「改动可见、失败可退」：
     * - 原注入产物先复制成 `*-backup-mcp.apk`，并落一条 [Project.isBackup]=true 的工程
     *   （卡片名称右侧带「备份」标签），用来回退安装；
     * - 主工程被更新为**新注入版本**（本次分析出的策略 + 必要时重建的工具集）。
     *
     * 工具列表类问题（[StrategyChatState.needsToolReplan]）会先用完整规划（带目标 dex 候选方法）
     * 重建工具集，再合并策略补丁——这是 AI 在对话里给不出的部分。
     */
    fun applyChatPatchAndReinject() {
        val chat = _state.value.strategyChat ?: return
        if (chat.thinking || chat.applying) return
        if (chat.pendingPatch.isEmpty() && !chat.needsToolReplan) {
            _state.update {
                it.copy(strategyChat = it.strategyChat?.copy(error = "这次没有可执行的改动，请补充问题信息后再试"))
            }
            return
        }
        val packageName = chat.packageName
        val sessionId = chat.sessionId
        val patch = chat.pendingPatch
        val needsToolReplan = chat.needsToolReplan
        val lastProblem = chat.turns.lastOrNull { it.role == ROLE_USER }?.text.orEmpty()
        _state.update { it.copy(strategyChat = it.strategyChat?.copy(applying = true, error = null)) }
        viewModelScope.launch {
            try {
                val appCtx = getApplication<Application>()
                val project = store.loadAll().firstOrNull { it.packageName == packageName }
                val source = resolveSourceApk(appCtx, packageName)
                    ?: throw IllegalStateException("缺少源 APK / 注入产物，无法重新注入")
                val info = withContext(Dispatchers.IO) { ApkParser.parse(source) }
                val settings = settingsRepo.current()
                // dex 扫描较慢：只在真正需要（方案缺失 / 要重建工具集）时才做
                val hints = if (project?.plan == null || needsToolReplan) {
                    withContext(Dispatchers.IO) {
                        DexOps.scanHints(source, DexOps.DexHintBudget.forContext(settings.contextTokens))
                    }
                } else {
                    emptyList()
                }
                var basePlan = project?.plan ?: planner.plan(info, hints, settings)

                // 工具列表问题：重新规划工具集（AI 在对话里拿不到 dex 提示，只能在这里做）
                var toolNote = ""
                if (needsToolReplan) {
                    val replanned = planner.plan(
                        info = info,
                        dexHints = hints,
                        settings = settings,
                        extraNotes = listOf(
                            "本次是「工具缺失」修复：必须产出可用的 MCP 工具集（tools 不能为空）。",
                            "用户反馈：$lastProblem",
                        ),
                    )
                    if (replanned.tools.isEmpty()) {
                        throw IllegalStateException(
                            "重新规划后仍未产出可用工具；请确认模型能力或到设置页更换模型后重试",
                        )
                    }
                    basePlan = basePlan.copy(tools = replanned.tools)
                    toolNote = "；工具集已重建为 ${replanned.tools.size} 个"
                }
                val patched = patch.applyTo(basePlan)

                // 先备份当前产物：调整失败可回退安装（这一步必须在覆盖 output 之前）
                val backupFile = project?.let { proj ->
                    withContext(Dispatchers.IO) {
                        store.backupOutput(
                            packageName = packageName,
                            template = proj,
                            previousOutput = proj.outputRelative?.let { File(appCtx.filesDir, it) },
                        )
                    }
                }
                val backedUp = backupFile != null

                val output = File(store.outputDir(), sanitize(info.packageName) + "-mcp.apk")
                withContext(Dispatchers.IO) { injector.inject(source, info, patched, output) }

                if (project != null) {
                    // 备份工程（isBackup=true）已由 ProjectsStore.backupOutput 落盘，这里只更新注入版本
                    store.upsert(
                        project.copy(
                            status = Project.Status.DONE,
                            outputRelative = output.relativeTo(appCtx.filesDir).path,
                            plan = patched,
                            error = null,
                            isBackup = false,
                        ),
                    )
                }
                refresh()
                val doneTurns = _state.value.strategyChat?.turns ?: chat.turns
                val reply = buildString {
                    append("已按调整重新注入：策略=").append(patched.strategy)
                    append("，入口=").append(patched.entryPoint)
                    append("，连接=").append(patched.connectionMode)
                    append("，端口=").append(patched.port)
                    append("，工具=").append(patched.tools.size).append(" 个")
                    append(toolNote).append("。")
                    if (backedUp) {
                        append("\n工程列表已变成两份：新的「注入版本」可直接展开点「安装产物」；")
                        append("另一条带「备份」标签的是调整前的版本，新版本不可用时装它即可回退。")
                    } else {
                        append("\n原工程没有可备份的产物，只生成了新的注入版本。")
                    }
                }
                val finalTurns = doneTurns + ChatTurn(ROLE_ASSISTANT, reply)
                _state.update { state ->
                    val c = state.strategyChat ?: return@update state
                    if (c.packageName != packageName || c.sessionId != sessionId) return@update state
                    state.copy(
                        strategyChat = c.copy(
                            applying = false,
                            pendingPatch = StrategyPatch(),
                            needsToolReplan = false,
                            changes = emptyList(),
                            turns = finalTurns,
                        ),
                    )
                }
                persistChat(sessionId, packageName, finalTurns)
                _events.tryEmit("已重新注入：${info.appLabel ?: info.packageName}（已生成备份，可回退安装）")
            } catch (t: Throwable) {
                failChat(packageName, "重新注入失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /** 广播探测并解析回执 JSON；目标未响应/回执损坏返回 null。 */
    private suspend fun probeJson(appCtx: Application, packageName: String, cmd: String): JSONObject? {
        val raw = withContext(Dispatchers.IO) {
            runCatching { AgentProbe.probe(appCtx, packageName, cmd) }.getOrNull()
        } ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /** 目标侧上下文说明：诊断/崩溃取回情况，供弹窗顶部提示。 */
    private fun buildContextNote(diagnose: JSONObject?, crashLog: String?): String {
        val parts = ArrayList<String>()
        if (diagnose != null) {
            val errs = diagnose.optJSONArray("errors")?.length() ?: 0
            val recs = diagnose.optJSONArray("recommendations")?.length() ?: 0
            parts.add("已取回目标诊断（$errs 项问题 / $recs 条建议）")
        } else {
            parts.add("目标未响应，AI 将仅依据你的描述调整")
        }
        parts.add(
            if (!crashLog.isNullOrBlank()) {
                "已取回目标崩溃栈（${crashLog.length} 字符）"
            } else {
                "未取到目标崩溃栈"
            },
        )
        return parts.joinToString("；")
    }

    /** 还原目标当前策略：注入历史优先，工程方案兜底。 */
    private fun buildCurrentStrategy(record: InjectedApp?, project: Project?): CurrentStrategy {
        val plan = project?.plan
        return CurrentStrategy.from(
            entryPoint = record?.entryPoint ?: plan?.entryPoint,
            toastOnBoot = record?.toastOnBoot ?: plan?.toastOnBoot ?: true,
            strategy = record?.strategy ?: plan?.strategy,
            connectionMode = plan?.connectionMode,
            port = record?.port?.takeIf { p -> p > 0 } ?: plan?.port ?: DEFAULT_PORT,
            toolsCount = plan?.tools?.size ?: 0,
        )
    }

    /**
     * 本地判定「工具列表问题」（不依赖 AI，避免 AI 不表态就卡死）。
     *
     * 两条依据：
     * 1. 用户描述里同时出现「工具/注册表/tool」与否定词（没有 / 看不到 / 缺失 …）；
     * 2. 目标侧诊断回传里 `tools.registered` 为空或 `missing` 非空。
     * 另外：当前方案本来就 0 个工具、用户又在提工具，直接判为工具问题（最常见成因）。
     */
    private fun detectToolIssue(
        problem: String,
        diagnoseJson: JSONObject?,
        toolsCount: Int,
    ): Boolean {
        val text = problem.lowercase()
        val mentionsTools = text.contains("工具") || text.contains("tool") || text.contains("注册表")
        val negative = NEGATIVE_WORDS.any { text.contains(it) }
        if (mentionsTools && (negative || toolsCount == 0)) return true
        val tools = diagnoseJson?.optJSONObject("tools") ?: return false
        val registered = tools.optJSONArray("registered")?.length() ?: 0
        val missing = tools.optJSONArray("missing")?.length() ?: 0
        return registered == 0 || missing > 0
    }

    /**
     * AI 一个字段都没给时的兜底补丁（内置判定，全部有迹可循）：
     * - 崩溃栈出现校验器/初始化异常，或问题发生在启动瞬间 → 回退最稳的 provider 入口并关闭启动提示；
     * - 服务连不上 / 端口 / 未监听 → 改用 service 策略；
     * - 都不匹配 → 空补丁（此时由 [sendChatProblem] 引导用户补充信息，而不是给个假动作）。
     */
    private fun fallbackPatch(
        problem: String,
        crashLog: String?,
        current: CurrentStrategy,
    ): StrategyPatch {
        val text = (problem + "\n" + (crashLog ?: "")).lowercase()
        val crashish = CRASH_WORDS.any { text.contains(it) }
        return when {
            crashish && current.entryPoint != "provider" -> StrategyPatch(
                entryPoint = "provider",
                toastOnBoot = false,
                explanation = "内置判定：启动期崩溃 → 改用最稳的 provider 入口并关闭启动提示",
            )

            crashish && current.toastOnBoot -> StrategyPatch(
                toastOnBoot = false,
                explanation = "内置判定：启动期崩溃 → 关闭启动提示，排除提示干扰",
            )

            SERVICE_WORDS.any { text.contains(it) } && current.strategy != "service" -> StrategyPatch(
                strategy = "service",
                explanation = "内置判定：服务未监听/连不上 → 改用 service 声明式策略",
            )

            else -> StrategyPatch()
        }
    }

    /** 重新注入源 APK：原始导入副本优先，注入产物兜底。 */
    private fun resolveSourceApk(appCtx: Application, packageName: String): File? {
        val record = injectedStore.find(packageName)
        record?.sourceRelative?.let { File(appCtx.filesDir, it) }
            ?.takeIf { it.exists() }?.let { return it }
        return record?.outputRelative?.let { File(appCtx.filesDir, it) }?.takeIf { it.exists() }
    }

    /** 补丁并集：新值为 null 时保留旧值，非 null 覆盖旧值。 */
    private fun mergePatch(base: StrategyPatch, next: StrategyPatch): StrategyPatch = base.copy(
        entryPoint = next.entryPoint ?: base.entryPoint,
        toastOnBoot = next.toastOnBoot ?: base.toastOnBoot,
        strategy = next.strategy ?: base.strategy,
        connectionMode = next.connectionMode ?: base.connectionMode,
        port = next.port ?: base.port,
        explanation = next.explanation.ifBlank { base.explanation },
    )

    /** 标记对话出错：停止忙碌态并记录可读原因。 */
    private fun failChat(packageName: String, message: String) {
        _state.update { state ->
            val c = state.strategyChat ?: return@update state
            if (c.packageName != packageName) return@update state
            state.copy(strategyChat = c.copy(thinking = false, applying = false, error = message))
        }
    }

    /** 导入源复制到 filesDir/imports（持久保存，供 Manager 重新注入复用）。 */
    private fun copyToImports(app: Application, uri: Uri): File {
        val dir = File(app.filesDir, "imports").apply { mkdirs() }
        val file = File(dir, "import-${System.currentTimeMillis()}.apk")
        val input = app.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件")
        input.use { it.copyTo(file.outputStream()) }
        return file
    }

    /** 提取 APK 图标为 filesDir/icons/<name>.png，返回相对路径；失败返回 null。 */
    private fun extractIcon(apk: File): String? = runCatching {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = apk.absolutePath
        ai.publicSourceDir = apk.absolutePath
        val drawable = ai.loadIcon(pm) ?: return null
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(canvas)
        val dir = File(app.filesDir, "icons").apply { mkdirs() }
        val name = apk.nameWithoutExtension + ".png"
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        "icons/$name"
    }.getOrNull()

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_\\-]"), "_")

    private companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 与 AiPlanner 缺省端口对齐（注入历史无端口时展示/回灌用）。 */
        const val DEFAULT_PORT = 1732

        /** 工具类问题的否定词（与「工具/注册表」同时出现才判定）。 */
        val NEGATIVE_WORDS = listOf(
            "没有", "看不到", "不显示", "没显", "缺失", "为空", "获取不到", "找不到",
            "没出现", "没有出现", "失败", "不全", "不完整", "空",
        )

        /** 启动期崩溃特征词（含校验器/初始化异常与目标侧注入体栈）。 */
        val CRASH_WORDS = listOf(
            "verifyerror", "exceptionininitializer", "com.mcp.injector.bridge",
            "闪退", "崩溃", "crash", "启动就", "一打开",
        )

        /** 服务/连接类特征词。 */
        val SERVICE_WORDS = listOf(
            "连不上", "连接失败", "无法连接", "端口", "未监听", "没监听",
            "服务没有", "服务未", "超时", "sse", "拿不到工具", "拉不到",
        )
    }
}
