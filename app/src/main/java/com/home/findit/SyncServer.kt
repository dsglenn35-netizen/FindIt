package com.home.findit

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream

/**
 * 内置同步主机（NanoHTTPD）
 * 端点：/health、/api/handshake、/api/sync/pull、/api/sync/push、/photo/&lt;name&gt;
 * 安全：所有 /api 与 /photo 接口均用 PIN 派生密钥加密（GCM 信封 / CTR 照片流），
 *       PIN 既做接入校验也做加密密钥种子；/health 仅含设备名/版本，保持明文便于调试。
 */
class SyncServer(
    private val context: Context,
    private val db: ItemDb,
    port: Int = 8888,
    pin: String = (1000..9999).random().toString()
) : NanoHTTPD(port) {

    /** 本次主机会话的 PIN（由服务启动时生成，或由调用方传入） */
    val pin: String = pin
    val port: Int = port

    private val deviceName: String get() = SyncPrefs.deviceName(context)
    private val deviceId: String get() = SyncPrefs.deviceId(context)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/health" && session.method == Method.GET -> json(health())
                uri == "/api/handshake" && session.method == Method.POST ->
                    protected(session) { handleHandshake(it) }
                uri == "/api/sync/pull" && session.method == Method.POST ->
                    protected(session) { handlePull(it) }
                uri == "/api/sync/push" && session.method == Method.POST ->
                    protected(session) { handlePush(it) }
                uri.startsWith("/photo/") && session.method == Method.GET -> handlePhoto(uri)
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "error"))
        }
    }

    private fun health(): JSONObject = JSONObject()
        .put("ok", true)
        .put("app", "FindIt")
        .put("version", "2.1")
        .put("device_id", deviceId)
        .put("device_name", deviceName)

    private fun handleHandshake(b: JSONObject): JSONObject {
        if (!checkPin(b)) throw PinException()
        return JSONObject()
            .put("ok", true)
            .put("server_device_id", deviceId)
            .put("server_name", deviceName)
    }

    private fun handlePull(b: JSONObject): JSONObject {
        if (!checkPin(b)) throw PinException()
        val since = b.optLong("since", 0L)
        val items = db.getItemsForSync(since)
        val arr = JSONArray()
        val photos = JSONArray()
        for (it in items) {
            arr.put(syncItemToJson(it))
            if (!it.deleted && it.photo != null) photos.put(it.photo)
        }
        val locs = JSONArray()
        db.getLocationsForSync().forEach { locs.put(it) }
        return JSONObject()
            .put("server_time", System.currentTimeMillis())
            .put("items", arr)
            .put("locations", locs)
            .put("photos_meta", photos)
    }

    private fun handlePush(b: JSONObject): JSONObject {
        if (!checkPin(b)) throw PinException()
        val itemsArr = b.optJSONArray("items") ?: JSONArray()
        val remote = ArrayList<SyncItem>()
        for (i in 0 until itemsArr.length()) remote.add(syncItemFromJson(itemsArr.getJSONObject(i)))
        val result = db.applyRemoteChanges(remote)
        val locs = b.optJSONArray("locations") ?: JSONArray()
        for (i in 0 until locs.length()) db.mergeRemoteLocation(locs.getString(i))
        return JSONObject()
            .put("accepted", result.applied)
            .put("conflicts", result.conflicts)
    }

    private fun handlePhoto(uri: String): Response {
        val name = uri.removePrefix("/photo/")
        if (!name.matches(Regex("[A-Za-z0-9._-]+"))) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad file name"))
        }
        val f = PhotoFiles.resolve(context, name)
        if (f == null || !f.exists()) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "no photo"))
        }
        // CTR 流加密：密文长度 = 明文长度，可直接回 Content-Length
        val cipher = SyncCrypto.photoCipher(SyncCrypto.keyFor(pin), Cipher.ENCRYPT_MODE, name)
        val cis = CipherInputStream(FileInputStream(f), cipher)
        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", cis, f.length())
    }

    // ---------- 加密信封 ----------

    /** 读取加密请求体 → 解密 → 执行处理 → 加密响应 */
    private fun protected(session: IHTTPSession, block: (JSONObject) -> JSONObject): Response {
        val env = body(session)
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "empty body"))
        val key = SyncCrypto.keyFor(pin)
        val inner = try {
            JSONObject(SyncCrypto.decryptEnvelope(key, env))
        } catch (e: Exception) {
            // 密文无法解密（PIN 不符 / 被篡改 / 旧版客户端明文）统一按 403 拒绝
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "PIN 错误或数据无效"))
        }
        val result = try {
            block(inner)
        } catch (e: PinException) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "PIN 错误"))
        }
        return json(SyncCrypto.encryptEnvelope(key, result.toString()))
    }

    private fun checkPin(b: JSONObject): Boolean = b.optString("pin") == pin

    private class PinException : Exception()

    private fun json(o: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun json(status: Response.Status, o: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())

    private fun body(session: IHTTPSession): JSONObject? {
        return try {
            val len = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (len <= 0) return null
            val bytes = ByteArray(len)
            val stream = session.inputStream
            var read = 0
            while (read < len) {
                val r = stream.read(bytes, read, len - read)
                if (r < 0) break
                read += r
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun syncItemToJson(it: SyncItem): JSONObject = JSONObject()
            .put("id", it.id)
            .put("device_id", it.deviceId)
            .put("name", it.name)
            .put("location", it.location)
            .put("photo", it.photo ?: "")
            .put("created_at", it.createdAt)
            .put("updated_at", it.updatedAt)
            .put("deleted", it.deleted)
            .put(
                "moves",
                JSONArray().also { arr ->
                    it.moves.forEach { m ->
                        arr.put(JSONObject().put("location", m.location).put("moved_at", m.movedAt))
                    }
                }
            )

        fun syncItemFromJson(o: JSONObject): SyncItem {
            val movesArr = o.optJSONArray("moves")
            val moves = ArrayList<Move>()
            if (movesArr != null) {
                for (i in 0 until movesArr.length()) {
                    val m = movesArr.getJSONObject(i)
                    moves.add(Move(m.optString("location", ""), m.optLong("moved_at", 0L)))
                }
            }
            return SyncItem(
                id = o.optLong("id", 0L),
                deviceId = o.optString("device_id", ""),
                name = o.optString("name", ""),
                location = o.optString("location", ""),
                photo = o.optString("photo", "").ifEmpty { null },
                createdAt = o.optLong("created_at", 0L),
                updatedAt = o.optLong("updated_at", 0L),
                deleted = o.optBoolean("deleted", false),
                moves = moves
            )
        }
    }
}
