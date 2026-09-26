package com.mcp.injector.bridge

import android.app.Application
import android.app.Service
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 桥接层入口（任务 B）。
 *
 * 自洽运行：目标 App 进程启动即由注入 hook（<clinit> 调用 [boot]），或由宿主显式
 * 广播经 [AgentReceiver] / [BridgeService] 兜底拉起；读取 filesDir 或打包 asset 中的
 * mcp_bridge.json（缺省用内置默认端口 1732），无宿主时也正常监听并提供 MCP 服务。
 *
 * 依赖：仅 framework + 同包注入类，不引用宿主独有类。
 */
object Bridge {

    const val CONFIG_ASSET = "mcp_bridge.json"
    const val DEFAULT_PORT = 1732
    const val AGENT_VERSION = "0.9.2"

    /** 模块列表为空时的兜底展示名（官方调试模块）。 */
    const val DEFAULT_MODULE_LABEL = "official"

    private val LOCK = Any()

    @Volatile
    private var started = false

    @Volatile
    private var server: McpHttpServer? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var runtimeConfig: BridgeConfig? = null

    @Volatile
    private var runtimeRegistry: ToolRegistry? = null

    @Volatile
    private var bootStartedAt = 0L

    /** 注入 hook 入口：后台守护线程等待 Application 就绪后启动桥接。任何异常都不能外泄，避免拖垮目标 App。 */
    @JvmStatic
    fun boot() {
        bootStartedAt = System.currentTimeMillis()
        val t = Thread({
            // 注入进目标 <clinit> 的执行必须在守护线程里吞掉一切异常，
            // 否则一次桥接失败会造成整个目标进程崩溃、永远进不了首页。
            try {
                val app = waitForApplication(30_000L)
                if (app != null) start(app)
            } catch (t: Throwable) {
                resetForRetry()
            }
        }, "mcp-bridge-boot")
        t.isDaemon = true
        t.start()
    }

    /**
     * Provider 引导入口（provider 策略，默认）：[BridgeInitProvider] 在
     * `Application.onCreate()` 之前拿到 Context 后调用本方法，因此无需再等待 Application，
     * 注入体比目标 App 自身业务初始化更早启动。异常同样不外泄。
     */
    @JvmStatic
    fun bootWithContext(context: Context) {
        bootStartedAt = System.currentTimeMillis()
        val app = context.applicationContext
        val t = Thread({
            try {
                start(app)
                notifyBootDone(app)
            } catch (t: Throwable) {
                resetForRetry()
            }
        }, "mcp-bridge-boot")
        t.isDaemon = true
        t.start()
    }

    /**
     * 启动完成提示：列出实际启用的模块并弹一个 2 秒 Toast（`Toast.LENGTH_SHORT`），
     * 与「模块已加载 → 自动消失 → 进入 App 主页」的交互预期一致。
     * 仅当真正监听成功才提示；提示文案与开关（`toastOnBoot`）均可配置。
     */
    private fun notifyBootDone(context: Context) {
        try {
            if (!isRunning()) return
            val cfg = runtimeConfig ?: return
            if (!cfg.toastOnBoot) return
            val modules = cfg.enabledModules?.takeIf { it.isNotEmpty() } ?: cfg.modules
            val label = modules.takeIf { it.isNotEmpty() }?.joinToString("/") ?: DEFAULT_MODULE_LABEL
            val text = "MCP 模块已加载：$label"
            // Toast 必须主线程；此处已在跨线程回调里，切回主线程再弹。
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    // 提示失败不影响桥接
                }
            }
        } catch (t: Throwable) {
            // 提示失败不影响桥接
        }
    }

    /** 以任意 Context 启动（幂等）。hook / AgentReceiver / BridgeService 均可调用。 */
    @JvmStatic
    fun start(context: Context) {
        synchronized(LOCK) {
            if (started) return
            started = true
            try {
                bootStartedAt = System.currentTimeMillis()
                val cfg = try {
                    BridgeConfig.load(context)
                } catch (t: Throwable) {
                    null
                }
                if (cfg == null) {
                    resetForRetry()
                    return
                }
                appContext = context.applicationContext
                runtimeConfig = cfg
                val registry = ToolRegistry(context, cfg)
                runtimeRegistry = registry
                val srv = McpHttpServer(cfg, registry, ToolExecutor(context), OfficialModule(context.applicationContext))
                try {
                    srv.start()
                    server = srv
                } catch (t: Throwable) {
                    // 端口冲突等：此处 server 尚未赋值，resetForRetry() 拿不到 srv，
                    // 必须先手动 stop() 释放 srv 构造时创建的线程池，否则每次广播重试
                    // 都会泄漏一份（4 个线程）。之后再重置状态允许 AgentReceiver 重试。
                    try {
                        srv.stop()
                    } catch (ignored: Throwable) {
                        // 忽略停止失败
                    }
                    resetForRetry()
                }
            } catch (t: Throwable) {
                // 桥接层任何组件初始化异常都不能外泄到目标进程，
                // 重置状态让 AgentReceiver 后续重试，而不是让目标 App 崩溃。
                resetForRetry()
            }
        }
    }

    /** 将启动状态复位，允许后续广播/服务再次拉起（自愈），但绝不抛异常。 */
    private fun resetForRetry() {
        try {
            server?.stop()
        } catch (t: Throwable) {
            // 忽略停止失败
        }
        server = null
        started = false
    }

    /** 改端口后重启 HTTP 服务（POST /mcp/config 使用），保留 started 状态。 */
    @JvmStatic
    fun restart(context: Context) {
        synchronized(LOCK) {
            val old = server
            server = null
            old?.stop()
            val cfg = try {
                BridgeConfig.load(context)
            } catch (t: Throwable) {
                null
            }
            if (cfg == null) {
                // 配置加载失败：旧 server 已停止且 server=null，必须把 started 复位为 false，
                // 否则若此前启动成功（started=true），后续 Bridge.start() 会因 if (started) return
                // 直接返回，导致 HTTP 服务永远无法再拉起（桥接永久失效）。
                started = false
                return
            }
            appContext = context.applicationContext
            runtimeConfig = cfg
            val registry = ToolRegistry(context, cfg)
            runtimeRegistry = registry
            val srv = McpHttpServer(cfg, registry, ToolExecutor(context), OfficialModule(context.applicationContext))
            try {
                srv.start()
                server = srv
                // restart 成功后标记为已启动：否则若此前启动曾失败（started=false），
                // 重启成功后 started 仍为 false，之后 start() 会误判未启动而在同端口
                // 另建一个 server（BindException），进而 resetForRetry() 把正在工作的
                // server 停掉。
                started = true
            } catch (t: Throwable) {
                // 重启失败（如新端口被占）：先 stop() 释放 srv 线程池，再重置状态以允许再次拉起
                try {
                    srv.stop()
                } catch (ignored: Throwable) {
                    // 忽略停止失败
                }
                server = null
                started = false
            }
        }
    }

    internal fun startFromService(service: Service) = start(service)

    internal fun isRunning(): Boolean = server?.isRunning() == true

    internal fun currentServer(): McpHttpServer? = server

    internal fun currentConfig(): BridgeConfig? = runtimeConfig

    internal fun currentRegistry(): ToolRegistry? = runtimeRegistry

    internal fun appContext(): Context? = appContext

    internal fun uptimeMillis(): Long =
        if (bootStartedAt == 0L) 0L else System.currentTimeMillis() - bootStartedAt

    /** 通过 ActivityThread.currentApplication 等待目标 App 的 Application 实例。 */
    internal fun waitForApplication(timeoutMs: Long): Application? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val m = Class.forName("android.app.ActivityThread").getMethod("currentApplication")
                val app = m.invoke(null)
                if (app is Application) return app
            } catch (t: Throwable) {
                // 应用尚未初始化完成，继续等待
            }
            try {
                Thread.sleep(200L)
            } catch (t: InterruptedException) {
                // 忽略中断
            }
        }
        return null
    }

    /** 读取 mcp_bridge.json：优先 filesDir，其次打包 asset。 */
    internal fun readConfigJson(context: Context): JSONObject? {
        try {
            val file = File(context.filesDir, CONFIG_ASSET)
            if (file.exists() && file.length() > 0) {
                return JSONObject(readFully(FileInputStream(file)))
            }
        } catch (t: Throwable) {
            // 落到 asset
        }
        return try {
            JSONObject(readFully(context.assets.open(CONFIG_ASSET)))
        } catch (t: Throwable) {
            null
        }
    }

    internal fun readFully(input: InputStream): String {
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            try {
                input.close()
            } catch (t: Throwable) {
                // 忽略
            }
        }
    }
}
