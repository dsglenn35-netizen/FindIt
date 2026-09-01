package com.home.findit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD

/**
 * 同步主机前台服务：
 * - 持有 SyncServer，App 退后台/被划掉后主机继续运行（防止后台被杀导致同步中断）
 * - 通知栏常驻 + 「停止主机」操作按钮；点击通知回到 App
 * - 类型 specialUse（用户从同步页显式开启主机，无 dataSync 6 小时时限）
 */
class SyncHostService : Service() {

    companion object {
        const val ACTION_STOP = "com.home.findit.action.STOP_HOST"
        private const val CHANNEL_ID = "sync_host"
        private const val NOTIF_ID = 1

        // 平台常量 ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE（API 34+，低版本忽略）
        private const val FGS_TYPE_SPECIAL_USE = 0x40000000
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (HostRuntime.server == null) {
            val pin = intent?.getStringExtra("pin") ?: (1000..9999).random().toString()
            val server = SyncServer(applicationContext, ItemDb(applicationContext), HostRuntime.port, pin)
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                HostRuntime.server = server
                HostRuntime.pin = pin
            } catch (e: Exception) {
                HostRuntime.server = null
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        HostRuntime.server?.stop()
        HostRuntime.server = null
        super.onDestroy()
    }

    /** 划掉任务卡片不停止主机（用户显式开启的，继续在后台服务） */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, SyncHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val backPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val ip = HostRuntime.localIp(this) ?: "未知IP"
        val hostText = "http://$ip:${HostRuntime.port}"
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_notif_title))
            .setContentText(getString(R.string.sync_notif_text, hostText, HostRuntime.pin))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(backPi)
            .addAction(0, getString(R.string.sync_notif_stop), stopPi)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif, FGS_TYPE_SPECIAL_USE
        )
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.sync_notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }
}
