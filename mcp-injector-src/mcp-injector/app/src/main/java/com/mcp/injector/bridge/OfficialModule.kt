package com.mcp.injector.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter

/**
 * 官方调试模块（任务 B，新增）。
 *
 * 挂载于 McpHttpServer 的 /mcp 控制端点（/mcp 下各路径）：
 *  - GET  /mcp/status   → {running, port, configPort, agentVersion, targetPkg, uptime}
 *  - GET  /mcp/tools    → {registered, expected, missing, complete}
 *  - GET  /mcp/diagnose → {timestamp, server, tools, config, errors, recommendations}
 *  - POST /mcp/config   {"port": N} → 校验四位数(1024-9999)并持久化 + 重启 HTTP 服务
 *
 * 自包含：仅依赖 framework + 同包注入类；运行状态实时取自 Bridge
 * （避免 POST /mcp/config 重启后持有过期 registry/config 引用）。
 */
class OfficialModule(context: Context) {

    companion object {
        const val MODULE_ID_OFFICIAL = "official"

        /** 指纹标记文件（目标 App 私有目录 filesDir），注入时由 ApkInjector 写入。 */
        const val MARKER_FILE = ".mcp_injector_marker"

        private const val PORT_MIN = 1024
        private const val PORT_MAX = 9999

        /** 崩溃日志响应体截断上限（字符）：只回传尾部。 */
        private const val CRASH_TAIL_LIMIT = 16 * 1024
    }

    private val appContext: Context = context.applicationContext

    // ---- GET /mcp/status ----
    fun status(): JSONObject {
        val cfg = Bridge.currentConfig()
        val running = Bridge.isRunning()
        return JSONObject()
            .put("running", running)
            .put("port", if (running) Bridge.currentServer()?.port() ?: 0 else 0)
            .put("configPort", cfg?.port ?: 0)
            .put("agentVersion", Bridge.AGENT_VERSION)
            .put("targetPkg", appContext.packageName)
            .put("uptime", Bridge.uptimeMillis())
            // 注入策略：入口方式（provider/clinit）与启动提示开关，供诊断与 AI 改策略判断现状
            .put("entryPoint", markerString("entryPoint") ?: "")
            .put("toastOnBoot", cfg?.toastOnBoot ?: true)
            .put("enabledModules", JSONArray(enabledModules()))
    }

    // ---- GET /mcp/tools ----
    fun toolsStatus(): JSONObject {
        val registered = Bridge.currentRegistry()?.list()?.map { it.name } ?: emptyList()
        val expected = expectedTools()
        val missing = expected.filter { it !in registered }
        return JSONObject()
            .put("registered", JSONArray(registered))
            .put("expected", JSONArray(expected))
            .put("missing", JSONArray(missing))
            .put("complete", missing.isEmpty())
    }

    // ---- GET /mcp/diagnose ----
    fun diagnose(): JSONObject {
        val errors = JSONArray()
        val recs = JSONArray()

        if (!Bridge.isRunning()) {
            errors.put("HTTP 服务未监听")
            recs.put("检查端口占用或配置，通过 AgentReceiver 下发 config 或重新注入")
        }
        if (Bridge.currentConfig() == null) {
            errors.put("配置缺失：未读取到 mcp_bridge.json")
            recs.put("写入 mcp_bridge.json 或重新注入")
        }
        if (readMarker() == null) {
            errors.put("标记文件缺失：未找到 $MARKER_FILE")
            recs.put("重新注入以生成标记文件")
        }

        val tools = toolsStatus()
        val missing = tools.optJSONArray("missing")
        if (missing != null && missing.length() > 0) {
            val names = (0 until missing.length()).map { missing.optString(it) }
            errors.put("缺少预期工具: ${names.joinToString(",")}")
            recs.put("通过 registry/update 补齐或重新注入")
        }

        // 目标 App 崩溃记录：注入后闪退的关键证据，喂给 AI 改注入策略的依据
        val crash = TargetCrashLogger.read(appContext)
        if (crash.isNotBlank()) {
            errors.put("检测到目标 App 崩溃记录（${crash.length} 字符）")
            recs.put("取回 /mcp/crash 堆栈排查；必要时切换注入入口 entryPoint 或关闭启动提示 toastOnBoot")
        }

        return JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("server", status())
            .put("tools", tools)
            .put("config", configSummary())
            .put("crash", crashSummary())
            .put("errors", errors)
            .put("recommendations", recs)
    }

    // ---- GET /mcp/crash ----
    /**
     * 目标 App 崩溃日志（注入后闪退排查的核心证据）。
     * 截取尾部 [CRASH_TAIL_LIMIT] 字符：崩溃栈最有价值的部分在末尾，且避免响应体过大。
     */
    fun crashInfo(): JSONObject {
        val text = TargetCrashLogger.read(appContext)
        val tail = if (text.length > CRASH_TAIL_LIMIT) text.substring(text.length - CRASH_TAIL_LIMIT) else text
        return JSONObject()
            .put("ok", true)
            .put("hasCrash", text.isNotBlank())
            .put("length", text.length)
            .put("truncated", text.length > CRASH_TAIL_LIMIT)
            .put("entryPoint", markerString("entryPoint") ?: "")
            .put("log", tail)
    }

    /** 清空崩溃日志（问题确认解决后调用）。 */
    fun clearCrash(): JSONObject {
        TargetCrashLogger.clear(appContext)
        return JSONObject().put("ok", true)
    }

    // ---- POST /mcp/config ----
    fun applyConfigBody(body: String): JSONObject {
        return try {
            val json = JSONObject(body)
            val port = json.optInt("port", -1)
            if (port < 0) {
                errorJson("缺少 port 参数（四位数 1024-9999）")
            } else {
                applyConfig(port)
            }
        } catch (t: Throwable) {
            errorJson("配置解析失败: $t")
        }
    }

    /** 校验 port（1024-9999 四位数）→ 持久化 mcp_bridge.json → 重启 HTTP 服务。 */
    fun applyConfig(port: Int): JSONObject {
        if (port < PORT_MIN || port > PORT_MAX) {
            return errorJson("端口必须是四位数(1024-9999)，收到: $port")
        }
        return try {
            val file = File(appContext.filesDir, Bridge.CONFIG_ASSET)
            val json = if (file.exists() && file.length() > 0) {
                JSONObject(Bridge.readFully(FileInputStream(file)))
            } else {
                Bridge.currentConfig()?.toJson() ?: JSONObject()
            }
            json.put("port", port)
            val conn = json.optJSONObject("connection")
                ?: JSONObject().also { json.put("connection", it) }
            conn.put("port", port)
            // 原子写入，避免覆盖写中途被杀留下半截/空配置（会导致 Bridge 永久无法启动且无自愈路径）
            writeAtomically(file, json.toString())

            val oldPort = Bridge.currentServer()?.port() ?: Bridge.currentConfig()?.port ?: 0
            Bridge.restart(appContext)
            JSONObject()
                .put("ok", true)
                .put("oldPort", oldPort)
                .put("newPort", port)
                .put("restarting", true)
        } catch (t: Throwable) {
            errorJson("配置写入失败: $t")
        }
    }

    /** cmd=scope：查询 / 更新模块作用域。模块开关是 scope 概念，只改「启用的模块 id 列表」，
     *  不改官方模块的工具声明（tools 的唯一权威来源是 marker，见 expectedTools）。 */
    fun scopeInfo(modulesJson: String?): JSONObject {
        return try {
            val marker = readMarker()
            if (modulesJson == null) {
                val cfg = Bridge.currentConfig()
                // 启用的模块 id：优先 config.enabledModules，其次 marker.modules[].id，再回退 [official]
                val enabled: List<String> = cfg?.enabledModules ?: (
                    marker?.optJSONArray("modules")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.optString("id", "")?.takeIf { it.isNotEmpty() }
                        }.ifEmpty { listOf(MODULE_ID_OFFICIAL) }
                    } ?: listOf(MODULE_ID_OFFICIAL)
                    )
                JSONObject()
                    .put("ok", true)
                    .put("modules", JSONArray(enabled))
                    .put("markerModules", marker?.optJSONArray("modules") ?: JSONArray())
            } else {
                val ids = (0 until JSONArray(modulesJson).length()).map {
                    JSONArray(modulesJson).optString(it)
                }.filter { it.isNotEmpty() }
                val file = File(appContext.filesDir, Bridge.CONFIG_ASSET)
                val json = if (file.exists() && file.length() > 0) {
                    JSONObject(Bridge.readFully(FileInputStream(file)))
                } else {
                    Bridge.currentConfig()?.toJson() ?: JSONObject()
                }
                json.put("enabledModules", JSONArray(ids))
                // 原子写入，避免覆盖写中途被杀留下半截/空配置（会导致 Bridge 永久无法启动且无自愈路径）
                writeAtomically(file, json.toString())
                Bridge.restart(appContext)
                JSONObject().put("ok", true).put("modules", JSONArray(ids))
            }
        } catch (t: Throwable) {
            errorJson("scope 处理失败: $t")
        }
    }

    fun errorJson(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    // ---- helpers ----

    /**
     * 预期工具：取自标记文件 modules 记录（official 模块条目声明的 tools）；
     * 若标记文件未记录工具名（如旧版纯 id 列表），回退为配置 mcp_bridge.json 声明的工具名。
     * 标记文件 modules 推荐格式：[{"id": "official", "tools": ["open_app", ...]}, ...]
     */
    private fun expectedTools(): List<String> {
        val out = LinkedHashSet<String>()
        val marker = readMarker()
        val modules = marker?.optJSONArray("modules")
        if (modules != null) {
            for (i in 0 until modules.length()) {
                val el = modules.opt(i)
                if (el is JSONObject && el.optString("id", "") == MODULE_ID_OFFICIAL) {
                    val tools = el.optJSONArray("tools")
                    if (tools != null) {
                        for (j in 0 until tools.length()) {
                            tools.optString(j).takeIf { it.isNotEmpty() }?.let { out.add(it) }
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) {
            val cfgJson = Bridge.readConfigJson(appContext)
            val tools = cfgJson?.optJSONArray("tools")
            if (tools != null) {
                for (i in 0 until tools.length()) {
                    tools.optJSONObject(i)?.optString("name", "")?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            }
        }
        return out.toList()
    }

    private fun readMarker(): JSONObject? {
        return try {
            val f = File(appContext.filesDir, MARKER_FILE)
            if (f.exists() && f.length() > 0) {
                JSONObject(Bridge.readFully(FileInputStream(f)))
            } else {
                // 注入时标记被打入 assets，首次运行物化到 filesDir（幂等；失败只回退读 asset）
                try {
                    appContext.assets.open(MARKER_FILE).use { input ->
                        val text = Bridge.readFully(input)
                        if (text.isNotBlank()) {
                            f.parentFile?.mkdirs()
                            FileWriter(f).use { it.write(text) }
                            JSONObject(text)
                        } else {
                            null
                        }
                    }
                } catch (t: Throwable) {
                    null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 同目录内「先写临时文件、再 rename 覆盖目标」的原子写入（参考 ApkSigner.writeAtomically）。
     * 直接 FileWriter 覆盖写 mcp_bridge.json 时若中途被杀/异常会留下半截或空文件，
     * 之后 BridgeConfig.load() 解析失败返回 null，Bridge 会永远无法启动且无自愈路径；
     * 原子写保证目标文件要么是旧内容、要么是完整新内容。renameTo 失败时退化为复制后删临时文件。
     */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun configSummary(): JSONObject {
        val cfg = Bridge.currentConfig()
        val raw = try {
            Bridge.readConfigJson(appContext) ?: JSONObject()
        } catch (t: Throwable) {
            JSONObject()
        }
        return JSONObject()
            .put("file", raw)
            .put("port", cfg?.port ?: 0)
            .put("host", cfg?.host ?: "")
            .put("mode", cfg?.mode ?: "")
            .put("toastOnBoot", cfg?.toastOnBoot ?: true)
            .put("entryPoint", markerString("entryPoint") ?: "")
            .put("modules", JSONArray(cfg?.modules ?: emptyList<String>()))
    }

    /** 读标记文件中的字符串字段（用于回传注入策略，如 entryPoint）。 */
    private fun markerString(key: String): String? =
        readMarker()?.optString(key, "")?.takeIf { it.isNotEmpty() }

    /** 启用的模块 id 列表：优先 config.enabledModules，其次 marker.modules[].id，最后 official。 */
    private fun enabledModules(): List<String> {
        Bridge.currentConfig()?.enabledModules?.takeIf { it.isNotEmpty() }?.let { return it }
        val fromMarker = readMarker()?.optJSONArray("modules")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("id", "")?.takeIf { it.isNotEmpty() }
            }
        }
        return fromMarker?.takeIf { it.isNotEmpty() } ?: listOf(MODULE_ID_OFFICIAL)
    }

    /** 崩溃摘要（仅计数，不含全文），用于 diagnose 轻量回传。 */
    private fun crashSummary(): JSONObject {
        val text = TargetCrashLogger.read(appContext)
        val count = text.split("=== ").count { it.isNotBlank() }
        return JSONObject()
            .put("hasCrash", text.isNotBlank())
            .put("length", text.length)
            .put("count", count)
    }
}
