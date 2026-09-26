package com.mcp.injector.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 注入引导 Provider —— 目标 App 启动时**最先执行**的注入入口（provider 策略）。
 *
 * 时机（Android `ActivityThread.handleBindApplication` 顺序）：
 * 1. `Application.attachBaseContext()`
 * 2. 安装并回调各 ContentProvider 的 `onCreate()`   ← 本类在这里
 * 3. `Application.onCreate()`
 * 4. 启动 Activity / 进入 App 主页
 *
 * 所以注入体与「启用的模块」先于 App 自身业务初始化执行，之后 App 照常进入自己的主页。
 *
 * 为什么用 Provider 而不是改写目标 dex 的 `<clinit>`：Provider 是**纯声明式挂载**，
 * 完全不触碰目标字节码，从根上规避了插桩导致的 VerifyError / 异常表错位等启动闪退；
 * 而且这里能直接拿到 Context，不必再去轮询 `ActivityThread.currentApplication`。
 *
 * 自包含：仅依赖 framework + 同包注入类。
 */
class BridgeInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        // 崩溃采集与桥接启动分开 try：两者互不牵连。原先写在同一个 try 里，
        // 一旦崩溃采集抛异常（如 filesDir 不可写），会连带跳过桥接启动，
        // 导致注入体完全不启动。
        try {
            // 崩溃采集最先安装：这样后续启动期任何异常（含注入体自身）都能落盘供诊断。
            TargetCrashLogger.install(ctx)
        } catch (t: Throwable) {
            // 采集安装失败不影响注入体启动
        }
        try {
            Bridge.bootWithContext(ctx)
        } catch (t: Throwable) {
            // 注入体绝不允许影响目标 App 启动
        }
        return true
    }

    // 本 Provider 不对外提供任何数据，仅借 onCreate 时机做初始化。

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}