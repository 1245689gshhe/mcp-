package com.mcp.injector.ui.components

import com.mcp.injector.data.Project

/**
 * 工程状态展示工具（任务 C 支撑文件，对齐反编译 ProjectStatusUi）。
 */
object ProjectStatusUi {

    /** 状态 → 中文标签。 */
    fun label(status: String): String = when (status) {
        Project.Status.IMPORTED -> "已导入"
        Project.Status.ANALYZING -> "分析中"
        Project.Status.PLANNING -> "规划中"
        Project.Status.INJECTING -> "注入中"
        Project.Status.DONE -> "完成"
        Project.Status.FAILED -> "失败"
        else -> status
    }

    /** 状态是否为进行中（分析/规划/注入）。 */
    fun isBusy(status: String): Boolean = Project.Status.BUSY.contains(status)
}
