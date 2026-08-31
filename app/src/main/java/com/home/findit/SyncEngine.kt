package com.home.findit

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 客户端同步引擎：拉取 → 合并 → 照片下载 → 推送 */
object SyncEngine {

    data class SyncOutcome(val ok: Boolean, val message: String)

    /** 后台线程执行同步，完成后回调到 UI 线程 */
    fun doSync(context: Context, db: ItemDb, onDone: (SyncOutcome) -> Unit) {
        Thread {
            val outcome = doSyncSync(context, db)
            android.os.Handler(context.mainLooper).post { onDone(outcome) }
        }.start()
    }

    private fun doSyncSync(context: Context, db: ItemDb): SyncOutcome {
        val host = SyncPrefs.hostUrl(context)
            ?: return SyncOutcome(false, "未配置主机，请先扫码或输入主机地址")
        val pin = SyncPrefs.hostPin(context)
            ?: return SyncOutcome(false, "未配置 PIN")
        try {
            val since = SyncPrefs.lastSyncAt(context)
            val deviceId = SyncPrefs.deviceId(context)

            // 1. 拉取主机变更
            val pullBody = JSONObject()
                .put("pin", pin)
                .put("device_id", deviceId)
                .put("since", since)
            val pullResp = postJson("$host/api/sync/pull", pullBody)
            val serverTime = pullResp.optLong("server_time", System.currentTimeMillis())
            val itemsArr = pullResp.optJSONArray("items")
            val remote = ArrayList<SyncItem>()
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    remote.add(SyncServer.syncItemFromJson(itemsArr.getJSONObject(i)))
                }
            }
            val result = db.applyRemoteChanges(remote)

            // 2. 合并常用位置（并集）
            val locs = pullResp.optJSONArray("locations")
            if (locs != null) {
                for (i in 0 until locs.length()) db.mergeRemoteLocation(locs.getString(i))
            }

            // 3. 按需下载缺失照片
            var photosDownloaded = 0
            for (item in remote) {
                val name = item.photo ?: continue
                if (item.deleted) continue
                if (PhotoFiles.resolve(context, name)?.exists() == true) continue
                if (downloadPhoto(host, name, context)) photosDownloaded++
            }

            // 4. 推送本机变更
            val pushItems = db.getItemsForSync(since)
            val arr = org.json.JSONArray()
            for (it in pushItems) arr.put(SyncServer.syncItemToJson(it))
            val locArr = org.json.JSONArray()
            db.getLocationsForSync().forEach { locArr.put(it) }
            val pushBody = JSONObject()
                .put("pin", pin)
                .put("device_id", deviceId)
                .put("items", arr)
                .put("locations", locArr)
            val pushResp = postJson("$host/api/sync/push", pushBody)
            val accepted = pushResp.optInt("accepted", 0)
            val conflicts = pushResp.optInt("conflicts", 0) + result.conflicts

            SyncPrefs.setLastSyncAt(context, serverTime)
            db.purgeTombstones(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)

            val msg = buildString {
                append("同步完成：拉取 ${result.applied} 条")
                if (photosDownloaded > 0) append("，照片 $photosDownloaded 张")
                append("，推送 $accepted 条")
                if (conflicts > 0) append("，冲突 $conflicts 条（按较新修改生效）")
            }
            return SyncOutcome(true, msg)
        } catch (e: Exception) {
            return SyncOutcome(false, "同步失败：${e.message ?: "未知错误"}")
        }
    }

    private fun postJson(urlStr: String, body: JSONObject): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(120)}")
        return JSONObject(text)
    }

    private fun downloadPhoto(host: String, name: String, context: Context): Boolean {
        return try {
            val conn = URL("$host/photo/$name").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 20000
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return false
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            true
        } catch (e: Exception) {
            false
        }
    }
}
