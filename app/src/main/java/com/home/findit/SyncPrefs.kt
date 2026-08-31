package com.home.findit

import android.content.Context
import java.util.UUID

/** 同步相关偏好：设备身份 / 主机地址 / 上次同步时间 */
object SyncPrefs {

    private const val PREFS = "sync_prefs"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本机设备 ID（首次生成，10 位随机，随记录一起同步，作为记录归属标识） */
    fun deviceId(c: Context): String {
        val p = prefs(c)
        var id = p.getString("device_id", "")
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "").take(10)
            p.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun deviceName(c: Context): String =
        prefs(c).getString("device_name", "我的手机") ?: "我的手机"

    fun setDeviceName(c: Context, name: String) {
        prefs(c).edit().putString("device_name", name).apply()
    }

    fun hostUrl(c: Context): String? = prefs(c).getString("host_url", null)
    fun hostPin(c: Context): String? = prefs(c).getString("host_pin", null)
    fun hostName(c: Context): String? = prefs(c).getString("host_name", null)

    fun setHost(c: Context, url: String?, pin: String?, name: String?) {
        prefs(c).edit()
            .putString("host_url", url)
            .putString("host_pin", pin)
            .putString("host_name", name)
            .apply()
    }

    fun lastSyncAt(c: Context): Long = prefs(c).getLong("last_sync_at", 0L)

    fun setLastSyncAt(c: Context, t: Long) {
        prefs(c).edit().putLong("last_sync_at", t).apply()
    }
}
