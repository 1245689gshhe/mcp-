package com.mcp.injector.bridge

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 目标 App 侧崩溃采集（注入运行时）。
 *
 * 注入后的 App 发生闪退时，宿主侧看不到任何堆栈（崩溃发生在另一个进程），
 * 无法判断是注入体的问题还是目标 App 自身的问题。这里在目标进程内安装
 * 默认 [Thread.getDefaultUncaughtExceptionHandler]，把未捕获异常的堆栈追加写入
 * 目标 App 的 filesDir，供宿主经 `/mcp/diagnose`（或 `cmd=crash` 广播）取回，
 * 再喂给 AI 做「改注入策略」的依据。
 *
 * 采集**不改变崩溃行为**：记录后仍委托给原处理器，异常照常抛出、App 照常退出。
 * 自包含：仅依赖 framework。
 */
object TargetCrashLogger {

    /** 崩溃日志文件名（目标 App filesDir 内）。 */
    const val CRASH_FILE = "mcp_crash.log"

    /** 单文件上限：超出则整体清空重记，避免频繁崩溃时无限增长。 */
    private const val MAX_BYTES = 256 * 1024L

    @Volatile
    private var installed = false

    /**
     * 安装崩溃采集（幂等）。先保存原处理器，记录后再委托给它——
     * 绝不能吞掉崩溃，否则目标 App 会处于「看似在跑实则半死」的状态而更难排查。
     */
    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val dir = context.applicationContext.filesDir
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    append(dir, thread, throwable)
                } catch (ignored: Throwable) {
                    // 采集失败不能影响崩溃流程
                }
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /** 读取已采集的崩溃日志（无则返回空串）。 */
    @JvmStatic
    fun read(context: Context): String = try {
        val f = File(context.applicationContext.filesDir, CRASH_FILE)
        if (f.exists() && f.length() > 0) f.readText() else ""
    } catch (t: Throwable) {
        ""
    }

    /** 清空崩溃日志（用户确认问题已解决后调用）。 */
    @JvmStatic
    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, CRASH_FILE).delete()
        } catch (t: Throwable) {
            // 忽略删除失败
        }
    }

    private fun append(dir: File, thread: Thread, throwable: Throwable) {
        val file = File(dir, CRASH_FILE)
        if (file.exists() && file.length() > MAX_BYTES) file.delete()
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val entry = buildString {
            append("=== ").append(System.currentTimeMillis())
                .append(" thread=").append(thread.name)
                .append('\n')
            append(sw.toString())
            append('\n')
        }
        file.appendText(entry)
    }
}