package com.mcp.injector.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桥接配置（任务 B）。
 *
 * 契约：`mcp_bridge.json` → `{port, host, modules: [...]}`，无内置密钥/鉴权（authToken 已移除）；
 * 缺省用内置默认端口 1732；端口校验 1024-9999（四位数）。
 * 同时兼容旧版嵌套结构 `connection: {mode, host, port}` 与 `tools` 数组（向后兼容历史配置）。
 * 模块作用域：`enabledModules` 为启用的模块 id 列表（scope 概念，默认空=按注入标记），
 * 与 marker 中模块的工具声明分离（见 OfficialModule.expectedTools）。
 */
class BridgeConfig(
    val packageName: String,
    val mode: String,
    val host: String,
    val port: Int,
    val modules: List<String>,
    val enabledModules: List<String>? = null,
    /** 目标进程启动时是否弹「模块已加载」提示（2 秒自动消失）；缺失默认 true。 */
    val toastOnBoot: Boolean = true,
) {

    fun withPort(newPort: Int): BridgeConfig =
        BridgeConfig(packageName, mode, host, newPort, modules, enabledModules, toastOnBoot)

    /** 序列化为配置 JSON（扁平字段 + 兼容嵌套 connection）。 */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("version", 1)
        json.put("packageName", packageName)
        json.put("port", port)
        json.put("host", host)
        json.put("mode", mode)
        json.put("modules", JSONArray(modules))
        json.put("toastOnBoot", toastOnBoot)
        if (!enabledModules.isNullOrEmpty()) {
            json.put("enabledModules", JSONArray(enabledModules))
        }
        val conn = JSONObject()
        conn.put("mode", mode)
        conn.put("host", host)
        conn.put("port", port)
        json.put("connection", conn)
        return json
    }

    companion object {

        /** 端口合法区间：四位数（1024-9999），越界回退默认端口。 */
        private const val PORT_MIN = 1024
        private const val PORT_MAX = 9999

        /** 读取 mcp_bridge.json（filesDir 优先，其次打包 asset）；无配置返回 null。 */
        fun load(context: Context): BridgeConfig? {
            val json = Bridge.readConfigJson(context) ?: return null
            return try {
                parse(json)
            } catch (t: Throwable) {
                null
            }
        }

        fun parse(json: JSONObject): BridgeConfig {
            val conn = json.optJSONObject("connection")

            // mode：新扁平字段优先，其次旧版 connection.mode
            var mode = json.optString("mode", "")
            if (mode.isEmpty()) mode = conn?.optString("mode", "") ?: ""
            if (mode.isEmpty()) mode = "streamable-http"
            if (mode != "sse") mode = "streamable-http"

            // host：新扁平字段优先，其次旧版 connection.host
            var host = json.optString("host", "")
            if (host.isEmpty()) host = conn?.optString("host", "") ?: ""
            if (host.isEmpty()) host = "127.0.0.1"

            // port：缺省用内置默认端口 1732，校验 1024-9999（四位数）
            var port = if (json.has("port")) {
                json.optInt("port", Bridge.DEFAULT_PORT)
            } else {
                conn?.optInt("port", Bridge.DEFAULT_PORT) ?: Bridge.DEFAULT_PORT
            }
            if (port < PORT_MIN || port > PORT_MAX) port = Bridge.DEFAULT_PORT

            val packageName = json.optString("packageName", "")
            val modules = parseModules(json.optJSONArray("modules"))
            val enabledModules = if (json.has("enabledModules")) {
                parseModules(json.optJSONArray("enabledModules"))
            } else {
                null
            }
            // 旧配置无此字段时默认开启提示（向后兼容）
            val toastOnBoot = json.optBoolean("toastOnBoot", true)
            return BridgeConfig(packageName, mode, host, port, modules, enabledModules, toastOnBoot)
        }

        private fun parseModules(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val el = arr.opt(i)
                when (el) {
                    is String -> if (el.isNotEmpty()) out.add(el)
                    is JSONObject -> el.optString("id", "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            }
            return out
        }
    }
}
