package com.mcp.injector

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mcp.injector.crash.CrashLogActivity
import com.mcp.injector.crash.CrashLogger
import com.mcp.injector.ui.AppRoot
import com.mcp.injector.ui.theme.LocalDynamicColorEnabled
import com.mcp.injector.ui.theme.MCPInjectorTheme

/**
 * 宿主主 Activity（支撑文件，对齐反编译产物 MainActivity）。
 *
 * EdgeToEdge + 依据设置提供 LocalDynamicColorEnabled + MCPInjectorTheme 包裹 AppRoot。
 *
 * 崩溃策略：启动时若存在"未读取的崩溃标记"，强制先进入崩溃日志页，
 * 用户在日志页清空/读取后返回再进入正常主界面。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 崩溃护卫策略：若存在未读取的崩溃标记（由 CrashGuard 在捕获异常时显式写入），
        // 强制先进入日志页；用户在日志页清空/读取后返回再进入正常主界面。
        // 注意：不使用"启动哨兵"文件——正常完成启动时也会写入完成标志，会被误判为崩溃，
        // 导致正常使用后下次启动被强制送到日志页。
        if (savedInstanceState == null && CrashLogger.hasPendingCrash(this)) {
            startActivity(
                Intent(this, CrashLogActivity::class.java).putExtra("from_crash", true)
            )
            finish()
            return
        }

        val settingsRepo = (application as InjectorApp).settingsRepository
        setContent {
            val settings by settingsRepo.settings.collectAsState(initial = com.mcp.injector.data.AppSettings())
            CompositionLocalProvider(
                LocalDynamicColorEnabled provides settings.dynamicColor,
            ) {
                MCPInjectorTheme(dynamicColor = settings.dynamicColor) {
                    AppRoot()
                }
            }
        }
    }
}
