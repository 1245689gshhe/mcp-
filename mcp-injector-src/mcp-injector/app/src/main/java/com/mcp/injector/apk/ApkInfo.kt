package com.mcp.injector.apk

/**
 * 目标 APK 解析结果（任务 C 支撑文件）。
 *
 * 字段对齐反编译产物 ApkInfo；[summaryForModel] 供 AiPlanner 拼进模型提示词。
 */
data class ApkInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val appLabel: String? = null,
    val applicationName: String? = null,
    val launcherActivity: String? = null,
    val activities: List<ApkComponent> = emptyList(),
    val services: List<ApkComponent> = emptyList(),
    val receivers: List<ApkComponent> = emptyList(),
    val providers: List<ApkComponent> = emptyList(),
    val permissions: List<String> = emptyList(),
) {
    /** 全部组件（activity + service + receiver + provider）。 */
    val allComponents: List<ApkComponent>
        get() = activities + services + receivers + providers

    /** 供模型阅读的紧凑摘要。 */
    fun summaryForModel(): String {
        val sb = StringBuilder()
        sb.append("package: ").append(packageName).append('\n')
        sb.append("versionName: ").append(versionName).append('\n')
        sb.append("minSdk: ").append(minSdk).append(" targetSdk: ").append(targetSdk).append('\n')
        applicationName?.let { sb.append("application: ").append(it).append('\n') }
        launcherActivity?.let { sb.append("launcher: ").append(it).append('\n') }
        if (permissions.isNotEmpty()) {
            sb.append("permissions(").append(permissions.size).append("):\n")
            permissions.take(24).forEach { sb.append("  - ").append(it).append('\n') }
        }
        dumpComponents(sb, "activities", activities)
        dumpComponents(sb, "services", services)
        dumpComponents(sb, "receivers", receivers)
        dumpComponents(sb, "providers", providers)
        return sb.toString()
    }

    private fun dumpComponents(sb: StringBuilder, label: String, list: List<ApkComponent>) {
        if (list.isEmpty()) return
        sb.append(label).append("(").append(list.size).append("):\n")
        list.take(40).forEach { sb.append(it.summaryLine()).append('\n') }
    }
}
