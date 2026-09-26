package com.mcp.injector.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcp.injector.InjectorApp
import com.mcp.injector.data.AiRepository
import com.mcp.injector.data.AppSettings
import com.mcp.injector.data.BuiltInApi
import com.mcp.injector.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 ViewModel（支撑 glue，对齐反编译 SettingsViewModel 的公开契约）。
 *
 * 契约：UiState(settings, models, loadingModels)；fetchModels / updateEndpoint /
 * updateApiKey / updateModel / updatePort / updatePreferService / updateDynamicColor /
 * updateContextTokens / resetToBuiltIn / update(transform)。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: SettingsRepository = (app as InjectorApp).settingsRepository
    private val ai = AiRepository()

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    /** 自动拉取去重：上一次请求所用的 (endpoint, apiKey) 组合。 */
    private var lastAutoFetchKey: String? = null

    /** 自动拉取防抖任务：合并逐字符输入触发的连续设置流发射。 */
    private var autoFetchJob: Job? = null

    /** 设置页 UI 状态。 */
    data class UiState(
        val settings: AppSettings = AppSettings(),
        val models: List<String> = emptyList(),
        val loadingModels: Boolean = false,
    )

    init {
        viewModelScope.launch {
            repo.settings.collect { s ->
                _state.update { it.copy(settings = s) }
                // 去重 + 防抖：API Key/Endpoint 逐字符写 DataStore 会连续发流，
                // 仅当 (endpoint, apiKey) 组合真正变化时才排队请求，并等待输入停顿
                // 后再触发一次，避免每次按键都打一次模型列表（限流/风控）。
                val key = s.endpoint + "\u0000" + s.apiKey
                if (key == lastAutoFetchKey) return@collect
                lastAutoFetchKey = key
                autoFetchJob?.cancel()
                if (s.apiKey.isBlank()) return@collect
                autoFetchJob = viewModelScope.launch {
                    delay(600)
                    if (!_state.value.loadingModels) fetchModels(loading = false)
                }
            }
        }
    }

    /** 拉取端点支持的模型列表（失败时事件提示，不阻塞设置保存）。 */
    fun fetchModels(loading: Boolean = true) {
        val current = _state.value.settings
        if (current.apiKey.isBlank()) {
            _events.tryEmit("请先填写 API Key 再获取模型列表")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingModels = true) }
            try {
                val models = withContext(Dispatchers.IO) {
                    ai.listModels(current.endpoint, current.apiKey)
                }
                _state.update { it.copy(models = models, loadingModels = false) }
                if (models.isEmpty()) _events.tryEmit("端点未返回模型列表")
            } catch (t: Throwable) {
                _state.update { it.copy(loadingModels = false) }
                _events.tryEmit("获取模型失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun updateEndpoint(value: String) = update { it.copy(endpoint = value) }

    fun updateApiKey(value: String) = update { it.copy(apiKey = value) }

    fun updateModel(value: String) = update { it.copy(model = value) }

    fun updatePort(value: String) {
        val port = value.toIntOrNull() ?: return
        if (port in 1024..9999) update { it.copy(mcpPort = port) }
    }

    fun updatePreferService(value: Boolean) = update { it.copy(preferServiceStrategy = value) }

    fun updateDynamicColor(value: Boolean) = update { it.copy(dynamicColor = value) }

    /** 模型上下文窗口（token 数）；决定 dex 提示词能塞多少类与方法签名。 */
    fun updateContextTokens(value: Int) = update { it.copy(contextTokens = value) }

    fun resetToBuiltIn() = update {
        it.copy(endpoint = BuiltInApi.ENDPOINT, model = BuiltInApi.DEFAULT_MODEL)
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            repo.update(transform)
        }
    }
}
