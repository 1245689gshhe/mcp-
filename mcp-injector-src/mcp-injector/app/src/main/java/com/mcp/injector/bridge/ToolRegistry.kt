package com.mcp.injector.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 工具注册表（任务 B）：从 mcp_bridge.json 的 `tools` 数组加载工具定义，
 * 支持 registry/update 热更新并持久化回配置（保留 port/host/modules，不写任何鉴权字段）。
 */
class ToolRegistry(context: Context, private val config: BridgeConfig) {

    /** 单个工具定义：name / description / inputSchema / target。 */
    class Tool(json: JSONObject) {
        val name: String = json.optString("name", "")
        val description: String = json.optString("description", "")
        val inputSchema: JSONObject = json.optJSONObject("inputSchema") ?: JSONObject()
        val target: JSONObject = json.optJSONObject("target") ?: JSONObject()

        fun describe(): JSONObject = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", inputSchema)
    }

    private val appContext: Context = context.applicationContext
    private val tools = LinkedHashMap<String, Tool>()

    init {
        try {
            val json = Bridge.readConfigJson(appContext)
            val arr = json?.optJSONArray("tools")
            if (json != null && arr != null) {
                loadTools(arr)
            }
        } catch (t: Throwable) {
            // 配置损坏时保持空注册表
        }
    }

    @Synchronized
    fun list(): List<Tool> = ArrayList(tools.values)

    @Synchronized
    fun find(name: String?): Tool? = if (name == null) null else tools[name]

    /** registry/update：解析工具数组（JSON 字符串），更新内存并持久化。 */
    @Synchronized
    fun update(toolsJson: String): List<Tool> {
        val arr = JSONArray(toolsJson)
        loadTools(arr)
        persist(arr)
        return list()
    }

    private fun loadTools(arr: JSONArray) {
        tools.clear()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val tool = Tool(obj)
            if (tool.name.isNotEmpty()) tools[tool.name] = tool
        }
    }

    private fun persist(arr: JSONArray) {
        try {
            val json = config.toJson()
            json.put("tools", arr)
            // 原子写入，避免覆盖写中途被杀留下半截/空配置：会导致 BridgeConfig.load() 失败、
            // Bridge 无法启动且没有自愈路径（文件存在但非法，不会回落到 asset）。
            writeAtomically(File(appContext.filesDir, Bridge.CONFIG_ASSET), json.toString())
        } catch (t: Throwable) {
            // 持久化失败不影响本次内存更新
        }
    }

    /** 同目录内「先写临时文件、再 rename 覆盖目标」的原子写入（参考 ApkSigner.writeAtomically）。 */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // 极少数文件系统上 renameTo 可能失败，退化为复制以避免残留 tmp。
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
