package com.mcp.injector.bridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject

/**
 * 广播控制通道（任务 B，新增）。
 *
 * 契约：exported=true；action = com.mcp.injector.agent.CONTROL；
 * extras {cmd: status|config|diagnose|scope, port?}；宿主经显式广播控制目标 App；
 * 回执走 resultData / 有序广播（resultExtras 另存 "json" 冗余字段）。
 * 收到广播时若桥接未运行则尝试拉起（自愈入口之一，与 hook / BridgeService 并列）。
 */
class AgentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONTROL = "com.mcp.injector.agent.CONTROL"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_PORT = "port"
        const val EXTRA_MODULES = "modules"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONTROL) return

        // onReceive 运行在目标进程主线程：任何异常都不能外泄，
        // 否则目标 App 每次启动都会被广播拉起而崩溃、永远进不了首页。
        val result: JSONObject = try {
            // 自愈：桥接未启动则尝试拉起（幂等）
            Bridge.start(context)

            val cmd = intent.getStringExtra(EXTRA_CMD) ?: "status"
            val module = OfficialModule(context)
            when (cmd) {
                "status" -> module.status()
                "diagnose" -> module.diagnose()
                "config" -> {
                    val port = intent.getIntExtra(EXTRA_PORT, -1)
                    if (port <= 0) {
                        module.errorJson("缺少 port 参数（四位数 1024-9999）")
                    } else {
                        module.applyConfig(port)
                    }
                }
                "scope" -> module.scopeInfo(intent.getStringExtra(EXTRA_MODULES))
                // 目标 App 崩溃日志（注入后闪退排查证据）
                "crash" -> module.crashInfo()
                "clearcrash" -> module.clearCrash()
                else -> module.errorJson("未知 cmd: $cmd")
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
        }

        // 回执：resultData 主通道 + resultExtras 冗余字段（有序广播可用）
        setResultData(result.toString())
        val extras = Bundle()
        extras.putString("json", result.toString())
        setResultExtras(extras)
        setResultCode(Activity.RESULT_OK)
    }
}
