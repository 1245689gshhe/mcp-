package com.mcp.injector.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mcp.injector.bridge.AgentReceiver
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * AgentReceiver 广播探测（任务 C 支撑文件；指纹识别来源三）。
 *
 * - [send]：无回执下发（改端口 cmd=config、模块作用域 cmd=scope）；
 * - [probe]：有序广播等待目标 AgentReceiver 回执（resultData JSON）——
 *   用于 status / tools / diagnose 提取；超时或目标未注入返回 null。
 */
object AgentProbe {

    /** 有序广播等待回执的超时上限。 */
    const val PROBE_TIMEOUT_MS = 6_000L

    /** 构造显式控制广播：action=CONTROL + setPackage 目标。 */
    fun controlIntent(
        packageName: String,
        cmd: String,
        port: Int? = null,
        modulesJson: String? = null,
    ): Intent = Intent(AgentReceiver.ACTION_CONTROL).apply {
        setPackage(packageName)
        putExtra(AgentReceiver.EXTRA_CMD, cmd)
        if (port != null) putExtra(AgentReceiver.EXTRA_PORT, port)
        if (modulesJson != null) putExtra(AgentReceiver.EXTRA_MODULES, modulesJson)
    }

    /** 下发无需回执的控制广播（失败静默，不影响 UI 流程）。 */
    fun send(
        context: Context,
        packageName: String,
        cmd: String,
        port: Int? = null,
        modulesJson: String? = null,
    ) {
        runCatching { context.sendBroadcast(controlIntent(packageName, cmd, port, modulesJson)) }
    }

    /**
     * 有序广播探测：等待回执 resultData；超时（[PROBE_TIMEOUT_MS]）或
     * 目标无 AgentReceiver 时返回 null。返回内容为 JSON 字符串。
     */
    suspend fun probe(
        context: Context,
        packageName: String,
        cmd: String,
        port: Int? = null,
        modulesJson: String? = null,
    ): String? = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        lateinit var timeout: Runnable

        // 该 receiver 只作为有序广播的「最终结果接收器」（resultReceiver），
        // 不再动态注册：sendOrderedBroadcast 会在所有普通接收器（含目标 App 的
        // 静态 AgentReceiver）执行完之后才回调它，此时 resultData 已由目标侧
        // setResultData 写入。若同时把它动态注册，它会在广播链条中先被当作普通
        // 一环调用（resultData 仍为 null），导致探测恒返回 null。
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (done.compareAndSet(false, true)) {
                    main.removeCallbacks(timeout)
                    val data = resultData
                    if (data != null) cont.resume(data) else cont.resume(null)
                }
            }
        }

        timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                cont.resume(null)
            }
        }

        main.postDelayed(timeout, PROBE_TIMEOUT_MS)
        runCatching {
            context.sendOrderedBroadcast(
                controlIntent(packageName, cmd, port, modulesJson),
                null,
                receiver,
                main,
                0,
                null,
                null,
            )
        }.onFailure {
            // 发送失败不再让协程挂死：记录原因并立即按超时结束（CAS 保证只 resume 一次）。
            Log.w("AgentProbe", "sendOrderedBroadcast failed: pkg=$packageName cmd=$cmd", it)
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                cont.resume(null)
            }
        }

        cont.invokeOnCancellation {
            main.removeCallbacks(timeout)
            done.compareAndSet(false, true)
        }
    }
}
