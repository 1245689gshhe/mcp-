package com.mcp.injector.ui.manager

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcp.injector.InjectorApp
import com.mcp.injector.agent.AgentProbe
import com.mcp.injector.agent.InjectedApp
import com.mcp.injector.agent.InjectedAppScanner
import com.mcp.injector.agent.InjectedAppsStore
import com.mcp.injector.agent.ModuleScope
import com.mcp.injector.ai.AiPlanner
import com.mcp.injector.apk.ApkInjector
import com.mcp.injector.apk.ApkParser
import com.mcp.injector.apk.DexOps
import com.mcp.injector.data.AiRepository
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manager 详情 ViewModel（任务 C 核心，新增）。
 *
 * DEV_PLAN 任务 C 第 4 点：详情（状态、工具清单、完整度比对）、操作（改端口→广播下发、
 * 重新注入、提取诊断→生成再注入请求→AiPlanner→ApkInjector 闭环）、模块作用域开关。
 *
 * - 状态/工具/诊断经 [AgentProbe.probe]（有序广播 cmd=status / cmd=diagnose）提取；
 *   目标未响应（未运行/未安装/未注入 AgentReceiver）时对应 JSON 为 null；
 * - 改端口：校验四位数(1024-9999) → 广播 cmd=config → 更新宿主历史记录 → 重新探测确认；
 * - 重新注入：优先复用原始导入源（sourceRelative），其次注入产物（outputRelative，
 *   ApkInjector 内部有重复注入防护）；diagnose=true 时把诊断错误/建议拼入规划提示词；
 * - 模块作用域：ModuleScope 落盘后经广播 cmd=scope 下发目标（NPatch manager 交互）。
 */
class ManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val injectedStore: InjectedAppsStore = (app as InjectorApp).injectedAppsStore
    private val moduleScope: ModuleScope = (app as InjectorApp).moduleScope
    private val settingsRepo: SettingsRepository = (app as InjectorApp).settingsRepository
    private val projectsStore: ProjectsStore = (app as InjectorApp).projectsStore

    private val ai = AiRepository()
    private val planner = AiPlanner(ai)
    private val injector = ApkInjector(app)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    /** Manager 详情 UI 状态。 */
    data class UiState(
        /** 当前管理的已注入应用（历史优先，扫描兜底）。 */
        val app: InjectedApp? = null,
        val busy: Boolean = false,
        /** cmd=status 广播回执（JSON）；null=目标未响应。 */
        val statusJson: JSONObject? = null,
        /** cmd=diagnose 广播回执（JSON，含 tools/errors/recommendations）；null=未响应。 */
        val diagnoseJson: JSONObject? = null,
        /** 端口输入框内容。 */
        val portInput: String = "",
        /** 模块作用域开关状态（NPatch 式）。 */
        val scopes: List<Pair<ModuleScope.ModuleInfo, Boolean>> = emptyList(),
    )

    /**
     * 加载目标应用详情：历史记录 → 同签名扫描兜底 → 并行探测 status/diagnose。
     */
    fun load(packageName: String) {
        viewModelScope.launch {
            val appCtx = getApplication<Application>()
            val record = injectedStore.find(packageName)
                ?: InjectedAppScanner.scan(appCtx, injectedStore)
                    .firstOrNull { it.packageName == packageName }
            _state.update {
                it.copy(
                    app = record,
                    portInput = record?.port?.takeIf { p -> p > 0 }?.toString()
                        ?: DEFAULT_PORT.toString(),
                    scopes = record?.let { moduleScope.scopeState(it.packageName) }
                        ?: emptyList(),
                )
            }
            if (record != null) {
                refreshStatus()
                refreshDiagnose()
            }
        }
    }

    /** 广播探测运行状态（cmd=status）。 */
    fun refreshStatus() {
        val current = _state.value.app ?: return
        viewModelScope.launch {
            val json = probeJson(current.packageName, "status")
            _state.update {
                it.copy(
                    statusJson = json,
                    // 目标响应后，用真实 configPort 校正输入框
                    portInput = json?.optInt("configPort", 0)?.takeIf { p -> p > 0 }
                        ?.toString() ?: it.portInput,
                )
            }
        }
    }

    /** 广播提取诊断（cmd=diagnose，含 server/tools/errors/recommendations）。 */
    fun refreshDiagnose() {
        val current = _state.value.app ?: return
        viewModelScope.launch {
            _state.update { it.copy(diagnoseJson = probeJson(current.packageName, "diagnose")) }
        }
    }

    /** 端口输入框内容更新（UI 直接回写）。 */
    fun updatePortInput(value: String) {
        _state.update { it.copy(portInput = value) }
    }

    /** 改端口：校验 → 广播 cmd=config 下发 → 更新宿主历史 → 重新探测确认。 */
    fun applyPort() {
        val current = _state.value.app ?: return
        val port = _state.value.portInput.toIntOrNull()
        if (port == null || port !in PORT_MIN..PORT_MAX) {
            _events.tryEmit("端口必须是四位数（1024-9999），收到: ${_state.value.portInput}")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) {
                    AgentProbe.send(getApplication(), current.packageName, "config", port = port)
                }
                val updated = current.copy(port = port)
                injectedStore.upsert(updated)
                _state.update { it.copy(app = updated) }
                _events.tryEmit("已下发端口 $port（目标侧 mcp_bridge.json 已持久化并重启服务）")
                refreshStatus()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * 模块作用域开关：ModuleScope 落盘 → 广播 cmd=scope 下发目标。
     * 关闭最后一个启用模块时由 ModuleScope 强制保留 official（避免目标失联）。
     */
    fun setModuleEnabled(moduleId: String, enabled: Boolean) {
        val current = _state.value.app ?: return
        val enabledList = moduleScope.setModuleEnabled(current.packageName, moduleId, enabled)
        val modulesJson = JSONArray(enabledList).toString()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AgentProbe.send(getApplication(), current.packageName, "scope", modulesJson = modulesJson)
            }
            _state.update { it.copy(scopes = moduleScope.scopeState(current.packageName)) }
            _events.tryEmit("模块作用域已更新并下发：${enabledList.joinToString(", ")}")
        }
    }

    /**
     * 重新注入（诊断回传再注入闭环入口）。
     *
     * @param useDiagnose true 时先取已提取的诊断 JSON，把 errors/recommendations
     *                   作为 extraNotes 传给 AiPlanner，再走规划→注入→更新历史。
     */
    fun reinject(useDiagnose: Boolean = false) {
        val current = _state.value.app ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val appCtx = getApplication<Application>()
                val source = resolveSourceApk(appCtx, current)
                if (source == null) {
                    _events.tryEmit("缺少源 APK / 注入产物，无法重新注入（同签名扫描来源无副本）")
                    return@launch
                }
                val info = withContext(Dispatchers.IO) { ApkParser.parse(source) }
                // 先取设置：dex 扫描规模由模型上下文窗口决定（小窗口别把提示词撑爆）
                val settings = settingsRepo.current()
                val hints = withContext(Dispatchers.IO) {
                    DexOps.scanHints(source, DexOps.DexHintBudget.forContext(settings.contextTokens))
                }
                val diagnoseNotes =
                    if (useDiagnose) buildDiagnoseHints(_state.value.diagnoseJson) else emptyList()
                val plan = planner.plan(info, hints, settings, diagnoseNotes)

                val output = File(File(appCtx.filesDir, "outputs"), sanitize(info.packageName) + "-mcp.apk")
                // 与首页「应用并重新注入」一致：先留一份调整前的原始备份，再覆盖主产物，
                // 使工程列表由一份变两份（新注入版本 + 名称右侧带「备份」标签的原始版本）。
                val existingProject = projectsStore.loadAll()
                    .firstOrNull { it.packageName == current.packageName && !it.isBackup }
                val template = existingProject ?: Project(
                    appName = current.appName,
                    packageName = current.packageName,
                    versionName = current.versionName,
                )
                val backupFile = withContext(Dispatchers.IO) {
                    projectsStore.backupOutput(
                        packageName = current.packageName,
                        template = template,
                        previousOutput = current.outputRelative?.let { File(appCtx.filesDir, it) },
                    )
                }
                withContext(Dispatchers.IO) { injector.inject(source, info, plan, output) }
                // 主工程更新为新的注入版本（备份那条已由 backupOutput 落盘）
                projectsStore.upsert(
                    template.copy(
                        status = Project.Status.DONE,
                        outputRelative = output.relativeTo(appCtx.filesDir).path,
                        plan = plan,
                        error = null,
                        isBackup = false,
                    ),
                )

                // 注入器内部已写入历史（新 installId/端口/模块）；此处重载并刷新
                val fresh = injectedStore.find(current.packageName)
                _state.update {
                    it.copy(
                        app = fresh ?: it.app,
                        portInput = fresh?.port?.takeIf { p -> p > 0 }?.toString()
                            ?: it.portInput,
                        scopes = fresh?.let { moduleScope.scopeState(it.packageName) }
                            ?: it.scopes,
                    )
                }
                refreshStatus()
                refreshDiagnose()
                _events.tryEmit(
                    buildString {
                        append(if (useDiagnose) "诊断回传再注入完成：" else "重新注入完成：")
                        append(info.appLabel ?: info.packageName)
                        append(
                            if (backupFile != null) {
                                "（已生成原始备份，可在首页工程列表回退安装）"
                            } else {
                                "（原工程无产物可备份，只生成了注入版本）"
                            },
                        )
                    },
                )
            } catch (t: Throwable) {
                _events.tryEmit("重新注入失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** 从宿主历史移除记录（不卸载目标应用）。 */
    fun removeFromHistory() {
        val current = _state.value.app ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { injectedStore.remove(current.packageName) }
            _state.update { it.copy(app = null, statusJson = null, diagnoseJson = null) }
            _events.tryEmit("已从历史移除 ${current.appName}")
        }
    }

    /** 安装当前注入产物（系统安装器）；无产物时给出可读提示。 */
    fun installOutput() {
        val current = _state.value.app ?: return
        val appCtx = getApplication<Application>()
        val file = current.outputRelative?.let { File(appCtx.filesDir, it) }
        if (file == null || !file.exists()) {
            _events.tryEmit("暂无可安装的注入产物：请先完成一次注入或重新注入")
            return
        }
        val result = runCatching {
            val uri = FileProvider.getUriForFile(appCtx, appCtx.packageName + ".fileprovider", file)
            appCtx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        _events.tryEmit(
            result.fold(
                onSuccess = { "已拉起系统安装器：${file.name}" },
                onFailure = { "安装失败：${it.message ?: it.javaClass.simpleName}" },
            ),
        )
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /** 有序广播探测并解析回执 JSON；目标未响应/回执损坏返回 null。 */
    private suspend fun probeJson(packageName: String, cmd: String): JSONObject? {
        val raw = withContext(Dispatchers.IO) {
            runCatching { AgentProbe.probe(getApplication(), packageName, cmd) }.getOrNull()
        } ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /** 重新注入源 APK：原始导入副本优先，注入产物兜底（重复注入有防护）。 */
    private fun resolveSourceApk(appCtx: Application, current: InjectedApp): File? {
        current.sourceRelative
            ?.let { File(appCtx.filesDir, it) }
            ?.takeIf { it.exists() }
            ?.let { return it }
        return current.outputRelative
            ?.let { File(appCtx.filesDir, it) }
            ?.takeIf { it.exists() }
    }

    /** 把诊断 JSON 的 errors/recommendations 转为规划提示（回传 AI 的载体）。 */
    private fun buildDiagnoseHints(diag: JSONObject?): List<String> {
        diag ?: return emptyList()
        val out = ArrayList<String>()
        val errors = diag.optJSONArray("errors")
        if (errors != null) {
            for (i in 0 until errors.length()) {
                errors.optString(i).takeIf { it.isNotEmpty() }?.let { out.add("诊断错误: $it") }
            }
        }
        val recs = diag.optJSONArray("recommendations")
        if (recs != null) {
            for (i in 0 until recs.length()) {
                recs.optString(i).takeIf { it.isNotEmpty() }?.let { out.add("诊断建议: $it") }
            }
        }
        return out
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_\\-]"), "_")

    private companion object {
        const val DEFAULT_PORT = 1732
        const val PORT_MIN = 1024
        const val PORT_MAX = 9999
    }
}
