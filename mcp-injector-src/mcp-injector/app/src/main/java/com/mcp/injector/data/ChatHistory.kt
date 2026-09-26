package com.mcp.injector.data

import android.content.Context
import com.mcp.injector.ai.ChatTurn
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * AI 调试对话的一次会话（历史记录）。
 *
 * [turns] 就是「当前对话的上下文」：AI 分析时只回灌这一份，不跨会话拼接；
 * [title] 取首条用户反馈，便于历史列表辨认。
 */
@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val turns: List<ChatTurn> = emptyList(),
)

/**
 * 对话历史仓库：filesDir/chat_history.json。
 *
 * 与 [ProjectsStore] 同风格（同样的 Json 配置与原子写）。按包名分桶、每包最多保留
 * [MAX_PER_PACKAGE] 条，超出后丢弃最旧的，避免长期使用后文件无限增长。
 */
class ChatHistoryStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(): File =
        File(context.filesDir, "chat_history.json").apply { parentFile?.mkdirs() }

    /** 全部会话（文件缺失或损坏时返回空列表）。 */
    fun loadAll(): List<ChatSession> {
        if (!file().exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ChatSession>>(file().readText())
        }.getOrDefault(emptyList())
    }

    fun saveAll(sessions: List<ChatSession>) {
        val target = file()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.encodeToString(sessions))
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 某包的会话，按最近更新倒序（最近一次排最前，打开弹窗时直接续上）。 */
    fun listFor(packageName: String): List<ChatSession> =
        loadAll().filter { it.packageName == packageName }.sortedByDescending { it.updatedAt }

    fun find(id: String): ChatSession? = loadAll().firstOrNull { it.id == id }

    /** 插入/更新一条会话：保留原 createdAt，刷新 updatedAt，并按包名裁剪条数。 */
    fun upsert(session: ChatSession) {
        val all = loadAll()
        val existing = all.firstOrNull { it.id == session.id }
        val merged = session.copy(
            createdAt = existing?.createdAt ?: session.createdAt,
            updatedAt = System.currentTimeMillis(),
        )
        val kept = all.filter { it.id != session.id } + merged
        val trimmed = kept
            .groupBy { it.packageName }
            .flatMap { (_, list) -> list.sortedByDescending { it.updatedAt }.take(MAX_PER_PACKAGE) }
        saveAll(trimmed)
    }

    fun remove(id: String) {
        saveAll(loadAll().filter { it.id != id })
    }

    private companion object {
        const val MAX_PER_PACKAGE = 20
    }
}