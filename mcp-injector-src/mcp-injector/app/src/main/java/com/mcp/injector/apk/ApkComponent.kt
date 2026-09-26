package com.mcp.injector.apk

/**
 * 清单组件（activity/service/receiver/provider 的统一描述，任务 C 支撑文件）。
 *
 * 字段对齐反编译产物 ApkComponent：
 * - [kind]：组件种类（activity / activity-alias / service / receiver / provider）
 * - [name]：解析后的完整类名
 * - [exported]：是否导出（可能为 null，表示清单未显式声明）
 * - [permission]：组件所需权限
 * - [actions] / [categories] / [dataSchemes]：intent-filter 信息
 * - [authorities]：provider 的 authorities
 * - [targetActivity]：activity-alias 的目标 activity
 */
data class ApkComponent(
    val kind: String,
    val name: String,
    val exported: Boolean? = null,
    val permission: String? = null,
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dataSchemes: List<String> = emptyList(),
    val authorities: String? = null,
    val targetActivity: String? = null,
) {
    /** 带导出标记的一行摘要（供模型提示词使用）。 */
    fun summaryLine(): String {
        val sb = StringBuilder()
        sb.append("  - ").append(name)
        if (exported == true) sb.append(" exported")
        if (permission != null) sb.append(" perm=").append(permission)
        if (authorities != null) sb.append(" authorities=").append(authorities)
        actions.take(6).forEach { sb.append("\n      action: ").append(it) }
        dataSchemes.take(4).forEach { sb.append("\n      data: ").append(it) }
        return sb.toString()
    }
}
