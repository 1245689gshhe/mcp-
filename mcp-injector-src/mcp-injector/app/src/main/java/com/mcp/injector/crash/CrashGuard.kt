package com.mcp.injector.crash

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局未捕获异常护卫。
 *
 * 在 [Install] 时设置为应用默认 handler，任何线程的未捕获异常都会：
 * 1. 将完整堆栈接入 [CrashLogger.save] 落盘；
 * 2. 通过 [CrashLogger.flagPendingCrash] 标记"存在待查看崩溃"；
 * 3. 尝试重新拉起 [CrashLogActivity]（强制回退到日志页）；
 * 4. 结束自身进程。
 *
 * "无论什么情况，强制回退到日志"：即使崩溃发生在主界面首帧，也会记录并拉起日志页。
 */
object CrashGuard {

    private var installed = false

    @Synchronized
    fun install(application: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 不能在此线程做太重的工作，但写文件+启动新组件是必需的
            try {
                // 优先尝试正常写入
                val saved = CrashLogger.save(application, thread, throwable)
                CrashLogger.flagPendingCrash(application)
                // 无论写入是否成功，都尝试回到日志页（时序上有先后，flutter/native 崩溃除外）
                launchCrashLog(application)
                // 给新 Activity 一点安顿时间后结束进程
                Thread {
                    try {
                        Thread.sleep(1200)
                    } catch (_: InterruptedException) {
                    }
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable)
                    } else {
                        Process.killProcess(Process.myPid())
                    }
                }.start()
            } catch (t: Throwable) {
                // 兜底：即使 handler 自身出错也交给原 handler/结束进程
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private fun launchCrashLog(application: Application) {
        runCatching {
            val intent = Intent(application, CrashLogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("from_crash", true)
            }
            ContextCompat.startActivity(application, intent, null)
        }
    }

    /** 将任意异常结构化为字符串（复用给诊断用途）。 */
    fun dump(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}