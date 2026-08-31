package com.home.findit

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

/**
 * 内置同步主机（NanoHTTPD）
 * 端点：/health、/api/handshake、/api/sync/pull、/api/sync/push、/photo/<name>
 */
class SyncServer(
    private val context: Context,
    private val db: ItemDb,
    port: Int = 8888
) : NanoHTTPD(port) {

    /** 每次启动主机时重新生成 4 位 PIN */
    val pin: String = (1000..9999).random().toString()
    val port: Int = port

    private val deviceName: String get() = SyncPrefs.deviceName(context)
    private val deviceId: String get() = SyncPrefs.deviceId(context)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/health" && session.method == Method.GET -> json(health())
                uri == "/api/handshake" && session.method == Method.POST -> handleHandshake(body(session))
                uri == "/api/sync/pull" && session.method == Method.POST -> handlePull(body(session))
                uri == "/api/sync/push" && session.method == Method.POST -> handlePush(body(session))
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
        .put("version", "2.0")
        .put("device_id", deviceId)
        .put("device_name", deviceName)

    private fun handleHandshake(b: JSONObject?): Response {
        if (!checkPin(b)) return forbidden()
        return json(
            JSONObject()
                .put("ok", true)
                .put("server_device_id", deviceId)
                .put("server_name", deviceName)
        )
    }

    private fun handlePull(b: JSONObject?): Response {
        if (!checkPin(b)) return forbidden()
        val since = b?.optLong("since", 0L) ?: 0L
        val items = db.getItemsForSync(since)
        val arr = JSONArray()
        val photos = JSONArray()
        for (it in items) {
            arr.put(syncItemToJson(it))
            if (!it.deleted && it.photo != null) photos.put(it.photo)
        }
        val locs = JSONArray()
        db.getLocationsForSync().forEach { locs.put(it) }
        return json(
            JSONObject()
                .put("server_time", System.currentTimeMillis())
                .put("items", arr)
                .put("locations", locs)
                .put("photos_meta", photos)
        )
    }

    private fun handlePush(b: JSONObject?): Response {
        if (!checkPin(b)) return forbidden()
        val itemsArr = b?.optJSONArray("items") ?: JSONArray()
        val remote = ArrayList<SyncItem>()
        for (i in 0 until itemsArr.length()) remote.add(syncItemFromJson(itemsArr.getJSONObject(i)))
        val result = db.applyRemoteChanges(remote)
        val locs = b?.optJSONArray("locations") ?: JSONArray()
        for (i in 0 until locs.length()) db.mergeRemoteLocation(locs.getString(i))
        return json(
            JSONObject()
                .put("accepted", result.applied)
                .put("conflicts", result.conflicts)
        )
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
        return newFixedLengthResponse(
            Response.Status.OK, "image/jpeg",
            java.io.ByteArrayInputStream(f.readBytes()), f.length()
        )
    }

    private fun checkPin(b: JSONObject?): Boolean = b?.optString("pin") == pin

    private fun forbidden() = json(Response.Status.FORBIDDEN, JSONObject().put("error", "PIN 错误"))

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
