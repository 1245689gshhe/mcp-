package com.mcp.injector

import android.app.Application
import com.mcp.injector.agent.InjectedAppsStore
import com.mcp.injector.agent.ModuleScope
import com.mcp.injector.crash.CrashGuard
import com.mcp.injector.data.ChatHistoryStore
import com.mcp.injector.data.ProjectsStore
import com.mcp.injector.data.SettingsRepository

/**
 * 应用入口（支撑文件，对齐反编译产物 InjectorApp 的公开契约）。
 *
 * 在原版 settingsRepository/projectsStore 基础上，为任务 C 增加：
 * - [injectedAppsStore]：注入历史仓库（「已注入应用」列表数据源）；
 * - [moduleScope]：模块作用域（NPatch 式按应用启停模块）。
 */
class InjectorApp : Application() {

    companion object {
        private var instance: InjectorApp? = null

        /** 全局实例（Home/Manager ViewModel 经 application 强转访问仓库）。 */
        fun getInstance(): InjectorApp =
            checkNotNull(instance) { "InjectorApp 尚未初始化" }
    }

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var projectsStore: ProjectsStore
        private set

    lateinit var injectedAppsStore: InjectedAppsStore
        private set

    lateinit var moduleScope: ModuleScope
        private set

    /** AI 调试对话历史仓库（长按工程/已注入应用的对话记录）。 */
    lateinit var chatHistoryStore: ChatHistoryStore
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃护卫务必最先安装：捕获任何线程的未捕获异常并强制回退到日志页。
        CrashGuard.install(this)
        instance = this
        settingsRepository = SettingsRepository(this)
        projectsStore = ProjectsStore(this)
        injectedAppsStore = InjectedAppsStore(this)
        chatHistoryStore = ChatHistoryStore(this)
        moduleScope = ModuleScope(this)
    }
}
