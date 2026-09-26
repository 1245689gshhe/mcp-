package com.mcp.injector.crash

import android.content.Context
import com.mcp.injector.InjectorApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 崩溃日志仓库：负责捕获、落盘、读取与清理进程内未捕获异常。
 *
 * - 落盘目录 `filesDir/crash_logs/`，每个崩溃一个 `yyyyMMdd_HHmmss_<seq>.txt` 文件；
 * - 仅保留最近 [MAX_KEEP] 个日志，超出自动清理最旧；
 * - 崩溃发生时通过 [flagPendingCrash] 标记，供启动入口判断并跳转日志页。
 */
object CrashLogger {

    private const val DIR = "crash_logs"
    private const val FLAG = "crash_pending.flag"
    private const val MAX_KEEP = 20

    /** 崩溃时附加的设备/环境信息，便于在真机上定位。 */
    data class EnvInfo(
        val model: String,
        val sdk: String,
        val versionName: String,
        val minSdk: Int,
        val targetSdk: Int,
    )

    fun crashDir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun flagFile(context: Context): File = File(context.filesDir, FLAG)

    /** 标记「已标记崩溃」（幂等，仅记录存在性；内容在日志文件里）。 */
    @Synchronized
    fun flagPendingCrash(context: Context) {
        runCatching { flagFile(context).writeText(System.currentTimeMillis().toString()) }
    }

    /** 是否有待查看的崩溃日志（启动时据此强制回退到日志页）。 */
    @Synchronized
    fun hasPendingCrash(context: Context): Boolean = flagFile(context).exists()

    /** 清除「待查看崩溃」标记（用户已查看后可重新进入正常主界面）。 */
    @Synchronized
    fun clearPendingCrash(context: Context) {
        runCatching { flagFile(context).delete() }
    }

    /** 收集设备信息。 */
    fun env(context: Context): EnvInfo {
        val app = context.applicationContext as? InjectorApp
        return EnvInfo(
            model = android.os.Build.MODEL,
            sdk = android.os.Build.VERSION.SDK_INT.toString(),
            versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            }.getOrDefault(""),
            minSdk = 26,
            targetSdk = 35,
        )
    }

    /**
     * 把一次未捕获异常写入磁盘。返回写入的文件（失败返回 null）。
     * 线程安全：全局 handler 可能从任意线程触发。
     */
    @Synchronized
    fun save(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ): File? = runCatching {
        val dir = crashDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "${ts}_${seq.getAndIncrement()}.txt")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        val e = env(context)
        pw.println("=== MCP Injector Crash Report ===")
        pw.println("time      : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        pw.println("device    : ${e.model} (SDK ${e.sdk})")
        pw.println("app ver   : ${e.versionName} (minSdk ${e.minSdk}, targetSdk ${e.targetSdk})")
        pw.println("thread    : ${thread.name} (${thread.priority})")
        pw.println("--------------------------------------------------")
        pw.println("EXCEPTION :")
        throwable.printStackTrace(pw)
        pw.println()
        pw.println("CAUSES    :")
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 10) {
            pw.println("  Caused by: ${cause.toString()}")
            cause.printStackTrace(pw)
            cause = cause.cause
            depth++
        }
        pw.println("--------------------------------------------------")
        pw.println("THREADS   :")
        val st = Thread.getAllStackTraces()
        for ((t, frames) in st) {
            if (t === thread) continue
            pw.println("  " + t.name + " (" + t.state + ")")
        }
        pw.flush()
        file.writeText(sw.toString())
        prune(dir)
        file
    }.getOrNull()

    /** 读取所有日志文件，按时间倒序。 */
    fun list(context: Context): List<File> =
        crashDir(context).listFiles()?.filter { it.isFile && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 读取某文件内容。 */
    fun content(file: File): String =
        runCatching { file.readText() }.getOrDefault("<读取失败>")

    /** 删除单个日志。 */
    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** 清空所有日志。 */
    fun clearAll(context: Context): Boolean = runCatching {
        var ok = true
        for (f in list(context)) ok = f.delete() && ok
        clearPendingCrash(context)
        ok
    }.getOrDefault(false)

    private fun prune(dir: File) {
        val all = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        for (i in MAX_KEEP until all.size) all[i].delete()
    }

    private val seq = AtomicInteger(0)
}