package com.mcp.injector.data

import android.content.Context
import com.mcp.injector.ai.InjectionPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 注入工程模型与仓库（任务 C 支撑文件）。
 *
 * 对齐反编译产物 Projects.kt：Project 为 @Serializable 数据类，字段/默认值/Status 常量
 * 与反编译产物一致；ProjectsStore 以 filesDir/projects.json 持久化。
 */
@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val appName: String,
    val packageName: String,
    val versionName: String = "",
    val iconFile: String? = null,
    val status: String = Project.Status.IMPORTED,
    val createdAt: Long = System.currentTimeMillis(),
    val outputRelative: String? = null,
    val error: String? = null,
    val plan: InjectionPlan? = null,
    /**
     * 是否为「调整前的原始备份」工程。
     *
     * 「应用并重新注入」会把调整前的产物另存一份并落成该标记的工程，卡片名称右侧显示
     * 「备份」标签；新注入版本不可用时，直接安装这条备份即可回退。
     */
    val isBackup: Boolean = false,
) {
    /** 状态常量与忙碌状态集合（对齐反编译 Project$Status）。 */
    object Status {
        const val IMPORTED = "imported"
        const val ANALYZING = "analyzing"
        const val PLANNING = "planning"
        const val INJECTING = "injecting"
        const val DONE = "done"
        const val FAILED = "failed"

        /** 进行中状态：这些状态下不允许并发导入。 */
        val BUSY: Set<String> = setOf(ANALYZING, PLANNING, INJECTING)
    }
}

/**
 * 工程持久化仓库：filesDir/projects.json。
 */
class ProjectsStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(): File =
        File(context.filesDir, "projects.json").apply { parentFile?.mkdirs() }

    /** 全部工程（文件缺失或损坏时返回空列表）。 */
    fun loadAll(): List<Project> {
        if (!file().exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<Project>>(file().readText())
        }.getOrDefault(emptyList())
    }

    fun saveAll(projects: List<Project>) {
        val target = file()
        // 原子写：先写同目录临时文件再 rename 覆盖目标。直接 writeText 覆盖时若中途被杀/异常
        // 会留下半截或空文件，随后被 loadAll 的 runCatching 静默吞成 emptyList，导致全部工程记录丢失。
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.encodeToString(projects))
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 按 id 去重插入，按创建时间倒序保存，返回保存后的完整列表。 */
    fun upsert(project: Project): List<Project> {
        val all = loadAll().filter { it.id != project.id } + project
        saveAll(all.sortedByDescending { it.createdAt })
        return all
    }

    fun remove(id: String): List<Project> {
        val all = loadAll().filter { it.id != id }
        saveAll(all)
        return all
    }

    fun iconFile(project: Project): File? =
        project.iconFile?.let { File(context.filesDir, it) }

    /** 注入产物输出目录（filesDir/outputs）。 */
    fun outputDir(): File =
        File(context.filesDir, "outputs").apply { mkdirs() }

    /**
     * 备份「调整前的产物」并落一条 [Project.isBackup]=true 的工程，供重新注入后回退安装。
     *
     * 首页 AI 对话的「应用并重新注入」与管理页的「重新注入」共用这里：必须在覆盖主产物
     * 之前调用，工程列表才会由一份变两份——新的注入版本 + 名称右侧带「备份」标签的原始版本。
     * 重复调整时复用同一条备份工程（同 id，沿用原 createdAt），不会越攒越多。
     *
     * @param packageName 目标包名（决定备份文件名）
     * @param template 主工程，提供 appName/versionName/iconFile/createdAt/plan（备份工程据此落档）
     * @param previousOutput 当前产物文件；null 或不存在表示无可备份产物
     * @return 备份产物文件；无可备份产物或复制失败返回 null
     */
    fun backupOutput(packageName: String, template: Project, previousOutput: File?): File? {
        if (previousOutput == null || !previousOutput.exists()) return null
        val backupFile = File(outputDir(), sanitizeName(packageName) + "-backup-mcp.apk")
        val copied = runCatching {
            previousOutput.copyTo(backupFile, overwrite = true)
        }.isSuccess && backupFile.exists()
        if (!copied) return null
        // 备份条目：沿用同一 id（重复调整只更新这一条），createdAt 排在主工程之后
        val seed = loadAll().firstOrNull { it.isBackup && it.packageName == packageName }
            ?: template.copy(
                id = UUID.randomUUID().toString(),
                isBackup = true,
                createdAt = template.createdAt - 1,
            )
        upsert(
            seed.copy(
                status = Project.Status.DONE,
                outputRelative = backupFile.relativeTo(context.filesDir).path,
                plan = template.plan,
                error = null,
                isBackup = true,
            ),
        )
        return backupFile
    }

    /** 产物文件名净化：与注入产物命名规则一致（非字母数字下划线连字符一律替换）。 */
    private fun sanitizeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
}
