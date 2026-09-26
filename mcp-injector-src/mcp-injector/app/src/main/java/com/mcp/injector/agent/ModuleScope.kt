package com.mcp.injector.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/**
 * 模块作用域（任务 C，新增；交互参考 NPatch 的 manager 模块作用域）。
 *
 * NPatch 分层中 manager 对每个目标 App 维护「启用的模块列表」，勾选后经 remote-api 下发。
 * 本类对应宿主端该交互：
 * - 模块注册表 [registry]：首个模块 = official 官方调试模块（桥接 + HttpServer + AgentReceiver 自洽体）；
 * - 每应用启用列表：`scopes.json` → `{pkg: {enabledModules: [moduleId]}}`；
 * - 变更后由 ManagerViewModel 同步下发 `AgentReceiver cmd=scope` 到目标应用。
 */
class ModuleScope(private val context: Context) {

    companion object {

        /** 官方调试模块 id（首个模块；与 AgentReceiver/OfficialModule 的 MODULE_ID_OFFICIAL 一致）。 */
        const val OFFICIAL_MODULE_ID = "official"

        /** 模块注册表：首个 = official 调试模块，后续模块在此扩展。 */
        val registry: List<ModuleInfo> = listOf(
            ModuleInfo(
                id = OFFICIAL_MODULE_ID,
                name = "官方调试模块",
                desc = "目标应用内自洽运行的 MCP 调试服务：Bridge + McpHttpServer + AgentReceiver（默认端口 1732）",
                entryClass = "com.mcp.injector.bridge.OfficialModule",
            ),
        )

        /** 作用域文件名（DEV_PLAN：scopes.json）。 */
        const val SCOPES_FILE = "scopes.json"
    }

    /** 模块描述（DEV_PLAN：ModuleInfo{id, name, desc, entryClass}）。 */
    data class ModuleInfo(
        val id: String,
        val name: String,
        val desc: String,
        val entryClass: String,
    )

    private fun file(): File =
        File(context.filesDir, SCOPES_FILE).apply { parentFile?.mkdirs() }

    /** 读取全部作用域：{pkg -> enabledModules}；文件缺失/损坏返回空表。 */
    @Synchronized
    fun loadScopes(): Map<String, List<String>> {
        if (!file().exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file().readText())
            val out = LinkedHashMap<String, List<String>>()
            val names = root.keys()
            while (names.hasNext()) {
                val pkg = names.next()
                val obj = root.optJSONObject(pkg) ?: continue
                out[pkg] = parseModules(obj.optJSONArray("enabledModules"))
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** 覆盖写入全部作用域。 */
    @Synchronized
    fun saveScopes(scopes: Map<String, List<String>>) {
        val root = JSONObject()
        for ((pkg, modules) in scopes) {
            root.put(
                pkg,
                JSONObject().put("enabledModules", JSONArray(modules)),
            )
        }
        // 原子写：先写同目录临时文件再 rename 覆盖目标。直接 FileWriter 覆盖时若中途被杀/异常
        // 会留下半截或空文件，被 loadScopes 的 runCatching 静默吞成 emptyMap，导致用户模块作用域丢失。
        val target = file()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileWriter(tmp).use { it.write(root.toString(2)) }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 某应用启用的模块：缺省默认启用 official 调试模块。 */
    fun enabledModules(pkg: String): List<String> =
        loadScopes()[pkg]?.toList() ?: listOf(OFFICIAL_MODULE_ID)

    /** 所有注册模块（供设置界面遍历）。 */
    fun allModules(): List<ModuleInfo> = registry

    /**
     * 设置某模块对该应用的启用状态（NPatch manager 勾选交互）。
     * 返回更新后的启用列表。
     */
    @Synchronized
    fun setModuleEnabled(pkg: String, moduleId: String, enabled: Boolean): List<String> {
        val scopes = loadScopes().toMutableMap()
        val current = scopes[pkg]?.toMutableList() ?: mutableListOf()
        if (enabled) {
            if (moduleId !in current) current.add(moduleId)
        } else {
            current.remove(moduleId)
            // 保持至少一个启用的模块（默认 official 不可全部关闭，避免目标失联）
            if (current.isEmpty()) current.add(OFFICIAL_MODULE_ID)
        }
        scopes[pkg] = current.distinct()
        saveScopes(scopes)
        return scopes[pkg] ?: emptyList()
    }

    /** 查询某应用启用状态（含模块信息，供 UI 渲染开关）。 */
    fun scopeState(pkg: String): List<Pair<ModuleInfo, Boolean>> {
        val enabled = enabledModules(pkg).toSet()
        return registry.map { it to (it.id in enabled) }
    }

    private fun parseModules(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }
}
