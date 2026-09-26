package com.mcp.injector.bridge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlin.concurrent.thread

/**
 * 桥接兜底服务（任务 B）：目标进程被拉起后若 hook 未生效，由该服务补启动桥接。
 * onStartCommand 返回 START_STICKY，保证进程被杀后系统可重建。
 */
class BridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        thread(name = "mcp-service-boot", isDaemon = true) {
            val app = Bridge.waitForApplication(10_000L)
            if (app != null) {
                Bridge.start(app)
            } else {
                Bridge.startFromService(this@BridgeService)
            }
        }
    }
}
