package com.home.findit

import android.content.Context
import android.net.wifi.WifiManager
import java.util.Locale

/**
 * 同步主机运行态：前台服务持有 SyncServer，Activity 只读它来渲染界面。
 * 进程被杀后重新拉起时本对象随之重建（服务会重新生成 PIN），属预期行为。
 */
object HostRuntime {

    @Volatile
    var server: SyncServer? = null

    @Volatile
    var port: Int = 8888

    @Volatile
    var pin: String = ""

    val isRunning: Boolean get() = server != null

    /** 本机局域网 IPv4（需在 WiFi 下；未连接返回 null） */
    fun localIp(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null else String.format(
                Locale.US, "%d.%d.%d.%d",
                ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff
            )
        } catch (e: Exception) {
            null
        }
    }
}
