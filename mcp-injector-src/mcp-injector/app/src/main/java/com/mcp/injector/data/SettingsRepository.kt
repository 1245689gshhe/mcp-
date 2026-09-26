package com.mcp.injector.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val DEFAULT_MCP_PORT = 1732

private val Context.settingsStore by preferencesDataStore(name = "settings")

/**
 * 设置持久化仓库：DataStore 偏好存储，对外暴露 [AppSettings] 流与原子更新。
 *
 * **无内置 Key**：api_key 缺省为空串，未填写时由 [AiRepository.chat] 抛
 * [ApiKeyMissingException] 引导用户前往设置页。
 */
class SettingsRepository(private val context: Context) {

    /** 设置流：DataStore 每次变更时映射为 [AppSettings] 快照。 */
    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            endpoint = prefs[Keys.ENDPOINT] ?: BuiltInApi.ENDPOINT,
            apiKey = prefs[Keys.API_KEY] ?: "",
            model = prefs[Keys.MODEL] ?: BuiltInApi.DEFAULT_MODEL,
            mcpPort = prefs[Keys.MCP_PORT] ?: DEFAULT_MCP_PORT,
            preferServiceStrategy = prefs[Keys.PREFER_SERVICE] ?: false,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            contextTokens = prefs[Keys.CONTEXT_TOKENS] ?: AppSettings.DEFAULT_CONTEXT_TOKENS,
        )
    }

    /** 取当前设置快照。 */
    suspend fun current(): AppSettings = settings.first()

    /** 读取当前设置，应用 transform 后整体写回。 */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val next = transform(
                AppSettings(
                    endpoint = prefs[Keys.ENDPOINT] ?: BuiltInApi.ENDPOINT,
                    apiKey = prefs[Keys.API_KEY] ?: "",
                    model = prefs[Keys.MODEL] ?: BuiltInApi.DEFAULT_MODEL,
                    mcpPort = prefs[Keys.MCP_PORT] ?: DEFAULT_MCP_PORT,
                    preferServiceStrategy = prefs[Keys.PREFER_SERVICE] ?: false,
                    dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
                    contextTokens = prefs[Keys.CONTEXT_TOKENS] ?: AppSettings.DEFAULT_CONTEXT_TOKENS,
                ),
            )
            prefs[Keys.ENDPOINT] = next.endpoint
            prefs[Keys.API_KEY] = next.apiKey
            prefs[Keys.MODEL] = next.model
            prefs[Keys.MCP_PORT] = next.mcpPort
            prefs[Keys.PREFER_SERVICE] = next.preferServiceStrategy
            prefs[Keys.DYNAMIC_COLOR] = next.dynamicColor
            prefs[Keys.CONTEXT_TOKENS] = next.contextTokens
        }
    }

    private object Keys {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val MCP_PORT = intPreferencesKey("mcp_port")
        val PREFER_SERVICE = booleanPreferencesKey("prefer_service")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val CONTEXT_TOKENS = intPreferencesKey("context_tokens")
    }
}
