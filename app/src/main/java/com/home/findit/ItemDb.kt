package com.home.findit

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File

/** 一条存放记录 */
data class Item(
    val id: Long,
    val name: String,
    val location: String,
    val photo: String?,        // 照片文件名（不含路径，跨设备可同步）
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val deviceId: String,
    val remoteId: Long
)

/** 一条移动历史 */
data class Move(val location: String, val movedAt: Long)

/** 同步用物品记录（含移动历史） */
data class SyncItem(
    val id: Long,              // 所属设备上的原始 id
    val deviceId: String,
    val name: String,
    val location: String,
    val photo: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val moves: List<Move>
)

/** 一次同步合并的结果 */
data class SyncResult(val applied: Int, val conflicts: Int)

/** 本地 SQLite 存储（v3：多设备同步） */
class ItemDb(context: Context) : SQLiteOpenHelper(context, "findit.db", null, 3) {

    private val appContext = context.applicationContext
    private val myDeviceId: String = SyncPrefs.deviceId(appContext)

    companion object {
        private val DEFAULT_LOCATIONS =
            listOf("客厅", "卧室", "厨房", "卫生间", "书房", "阳台", "玄关", "储物间")

        private const val CREATE_ITEMS = """CREATE TABLE items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                location TEXT DEFAULT '',
                photo TEXT,
                created_at INTEGER NOT NULL,
                pinyin_full TEXT DEFAULT '',
                pinyin_initials TEXT DEFAULT '',
                updated_at INTEGER DEFAULT 0,
                deleted INTEGER DEFAULT 0,
                device_id TEXT DEFAULT '',
                remote_id INTEGER DEFAULT 0
            )"""

        private const val CREATE_LOCATIONS = """CREATE TABLE locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                sort_order INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER DEFAULT 0
            )"""

        private const val CREATE_MOVES = """CREATE TABLE moves (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id INTEGER NOT NULL,
                location TEXT,
                moved_at INTEGER NOT NULL
            )"""
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_ITEMS)
        db.execSQL(CREATE_LOCATIONS)
        db.execSQL(CREATE_MOVES)
        seedLocations(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE items ADD COLUMN pinyin_full TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE items ADD COLUMN pinyin_initials TEXT DEFAULT ''")
            db.execSQL(CREATE_LOCATIONS)
            db.execSQL(CREATE_MOVES)
            seedLocations(db)
            backfillPinyin(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE items ADD COLUMN updated_at INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE items ADD COLUMN deleted INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE items ADD COLUMN device_id TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE items ADD COLUMN remote_id INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE locations ADD COLUMN updated_at INTEGER DEFAULT 0")
            // 老数据回填：updated_at=created_at，photo 改为文件名，登记本机身份
            val rows = ArrayList<Triple<Long, String?, Long>>()
            db.rawQuery("SELECT id, photo, created_at FROM items", null).use {
                while (it.moveToNext()) rows.add(Triple(it.getLong(0), it.getString(1), it.getLong(2)))
            }
            for ((id, photo, created) in rows) {
                db.update(
                    "items",
                    ContentValues().apply {
                        put("updated_at", created)
                        put("deleted", 0)
                        put("device_id", myDeviceId)
                        put("remote_id", id)
                        put("photo", photo?.substringAfterLast('/'))
                    },
                    "id=?", arrayOf(id.toString())
                )
            }
            db.execSQL("UPDATE locations SET updated_at = 0")
        }
    }

    // ---------- 常用位置 ----------

    fun getLocations(): List<String> {
        val list = ArrayList<String>()
        readableDatabase.rawQuery("SELECT name FROM locations ORDER BY sort_order, id", null).use {
            while (it.moveToNext()) list.add(it.getString(0))
        }
        return list
    }

    fun getLocationsForSync(): List<String> = getLocations()

    fun mergeRemoteLocation(name: String) {
        if (name.isNotBlank()) addLocation(name)
    }

    fun addLocation(name: String): Boolean {
        val cv = ContentValues().apply {
            put("name", name)
            put("sort_order", getLocations().size)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "locations", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    /** 重命名位置：同步更新该位置下所有物品的 location 文本 */
    fun renameLocation(old: String, new: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "locations",
                ContentValues().apply { put("name", new); put("updated_at", System.currentTimeMillis()) },
                "name=?", arrayOf(old)
            )
            db.update(
                "items",
                ContentValues().apply { put("location", new); put("updated_at", System.currentTimeMillis()) },
                "location=?", arrayOf(old)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteLocation(name: String) {
        writableDatabase.delete("locations", "name=?", arrayOf(name))
    }

    // ---------- 物品 ----------

    fun insert(name: String, location: String, photo: String?): Long {
        val id = insertFull(name, location, photo, System.currentTimeMillis())
        addMove(id, location)
        return id
    }

    fun insertFull(name: String, location: String, photo: String?, createdAt: Long): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert(
            "items", null,
            ContentValues().apply {
                put("name", name)
                put("location", location)
                put("photo", photo)
                put("created_at", createdAt)
                put("updated_at", now)
                put("pinyin_full", PinyinUtil.full(name))
                put("pinyin_initials", PinyinUtil.initials(name))
                put("deleted", 0)
            }
        )
        if (id > 0) {
            writableDatabase.update(
                "items",
                ContentValues().apply {
                    put("device_id", myDeviceId)
                    put("remote_id", id)
                },
                "id=?", arrayOf(id.toString())
            )
        }
        return id
    }

    fun update(id: Long, name: String, location: String, photo: String?) {
        writableDatabase.update(
            "items",
            ContentValues().apply {
                put("name", name)
                put("location", location)
                put("photo", photo)
                put("updated_at", System.currentTimeMillis())
                put("pinyin_full", PinyinUtil.full(name))
                put("pinyin_initials", PinyinUtil.initials(name))
            },
            "id=?", arrayOf(id.toString())
        )
    }

    /** 软删除：保留墓碑用于同步 */
    fun delete(id: Long) {
        writableDatabase.update(
            "items",
            ContentValues().apply {
                put("deleted", 1)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?", arrayOf(id.toString())
        )
    }

    // ---------- 移动历史 ----------

    fun addMove(itemId: Long, location: String) {
        addMoveAt(itemId, location, System.currentTimeMillis())
    }

    fun addMoveAt(itemId: Long, location: String, movedAt: Long) {
        writableDatabase.insert(
            "moves", null,
            ContentValues().apply {
                put("item_id", itemId)
                put("location", location)
                put("moved_at", movedAt)
            }
        )
    }

    fun getMoves(itemId: Long): List<Move> {
        val list = ArrayList<Move>()
        readableDatabase.rawQuery(
            "SELECT location, moved_at FROM moves WHERE item_id=? ORDER BY moved_at DESC",
            arrayOf(itemId.toString())
        ).use {
            while (it.moveToNext()) list.add(Move(it.getString(0), it.getLong(1)))
        }
        return list
    }

    // ---------- 查询 ----------

    fun queryAll(): List<Item> = query(null)

    fun search(keyword: String): List<Item> = query(keyword)

    private fun query(keyword: String?): List<Item> {
        val base = "SELECT id,name,location,photo,created_at,updated_at,deleted,device_id,remote_id FROM items WHERE deleted=0"
        val cursor = if (keyword.isNullOrBlank()) {
            readableDatabase.rawQuery("$base ORDER BY created_at DESC", null)
        } else {
            val like = "%$keyword%"
            readableDatabase.rawQuery(
                "$base AND (name LIKE ? OR location LIKE ? OR pinyin_full LIKE ? OR pinyin_initials LIKE ?) " +
                    "ORDER BY created_at DESC",
                arrayOf(like, like, like, like)
            )
        }
        val list = ArrayList<Item>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Item(
                        id = it.getLong(0),
                        name = it.getString(1),
                        location = it.getString(2) ?: "",
                        photo = it.getString(3),
                        createdAt = it.getLong(4),
                        updatedAt = it.getLong(5),
                        deleted = it.getInt(6) == 1,
                        deviceId = it.getString(7),
                        remoteId = it.getLong(8)
                    )
                )
            }
        }
        return list
    }

    // ---------- 同步 ----------

    /** 取自 since 以来变更的记录（含已删除墓碑），供推送/拉取 */
    fun getItemsForSync(since: Long): List<SyncItem> {
        val out = ArrayList<SyncItem>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, device_id, remote_id, name, location, photo, created_at, updated_at, deleted FROM items WHERE updated_at > ?",
            arrayOf(since.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                val localId = it.getLong(0)
                val dev = it.getString(1)
                out.add(
                    SyncItem(
                        id = if (dev.isEmpty()) localId else it.getLong(2),
                        deviceId = dev.ifEmpty { myDeviceId },
                        name = it.getString(3),
                        location = it.getString(4) ?: "",
                        photo = it.getString(5),
                        createdAt = it.getLong(6),
                        updatedAt = it.getLong(7),
                        deleted = it.getInt(8) == 1,
                        moves = getMoves(localId)
                    )
                )
            }
        }
        return out
    }

    /** 应用远端记录（LWW：updated_at 大者胜，相同时间设备 ID 字典序大者胜） */
    fun applyRemoteChanges(remote: List<SyncItem>): SyncResult {
        val db = writableDatabase
        var applied = 0
        var conflicts = 0
        db.beginTransaction()
        try {
            for (r in remote) {
                if (r.deviceId.isEmpty()) continue
                val localId = findLocalId(r.deviceId, r.id)
                if (localId == null) {
                    if (r.deleted) continue // 远端删除的未知记录，本地无需处理
                    val newId = db.insert("items", null, cvFromSyncItem(r))
                    if (newId > 0) {
                        applyMoves(db, newId, r.moves)
                        applied++
                    }
                } else {
                    val cur = getSyncItem(localId) ?: continue
                    val incomingWins = r.updatedAt > cur.updatedAt ||
                        (r.updatedAt == cur.updatedAt && r.deviceId > cur.deviceId)
                    if (incomingWins) {
                        if (cur.updatedAt > cur.createdAt) conflicts++ // 本地曾修改过，记为冲突
                        db.update("items", cvFromSyncItem(r), "id=?", arrayOf(localId.toString()))
                        applyMoves(db, localId, r.moves)
                        applied++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return SyncResult(applied, conflicts)
    }

    /** 清理 30 天前的删除墓碑及其照片 */
    fun purgeTombstones(before: Long) {
        val victims = ArrayList<Pair<Long, String?>>()
        readableDatabase.rawQuery(
            "SELECT id, photo FROM items WHERE deleted=1 AND updated_at < ?",
            arrayOf(before.toString())
        ).use {
            while (it.moveToNext()) victims.add(it.getLong(0) to it.getString(1))
        }
        for ((id, photo) in victims) {
            photo?.let { PhotoFiles.resolve(appContext, it)?.delete() }
            writableDatabase.delete("items", "id=?", arrayOf(id.toString()))
            writableDatabase.delete("moves", "item_id=?", arrayOf(id.toString()))
        }
    }

    // ---------- 备份恢复 ----------

    /** 用备份 JSON 整体替换当前数据（清空后重灌） */
    fun replaceAllFromBackup(json: JSONObject, photoMap: Map<String, File>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("items", null, null)
            db.delete("moves", null, null)
            db.delete("locations", null, null)

            val locs = json.optJSONArray("locations")
            if (locs != null && locs.length() > 0) {
                for (i in 0 until locs.length()) {
                    db.insert(
                        "locations", null,
                        ContentValues().apply {
                            put("name", locs.getString(i))
                            put("sort_order", i)
                            put("updated_at", System.currentTimeMillis())
                        }
                    )
                }
            } else {
                seedLocations(db)
            }

            val itemsArr = json.optJSONArray("items")
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    val o = itemsArr.getJSONObject(i)
                    val name = o.optString("name", "")
                    if (name.isEmpty()) continue
                    val location = o.optString("location", "")
                    val photoBase = o.optString("photo", "")
                    val photoName = if (photoBase.isNotEmpty()) photoMap[photoBase]?.name else null
                    val id = insertFull(
                        name, location, photoName,
                        o.optLong("created_at", System.currentTimeMillis())
                    )
                    val movesArr = o.optJSONArray("moves")
                    if (movesArr != null) {
                        for (j in 0 until movesArr.length()) {
                            val m = movesArr.getJSONObject(j)
                            addMoveAt(
                                id,
                                m.optString("location", ""),
                                m.optLong("moved_at", System.currentTimeMillis())
                            )
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- 内部 ----------

    private fun cvFromSyncItem(r: SyncItem): ContentValues = ContentValues().apply {
        put("name", r.name)
        put("location", r.location)
        put("photo", r.photo)
        put("created_at", r.createdAt)
        put("updated_at", r.updatedAt)
        put("deleted", if (r.deleted) 1 else 0)
        put("pinyin_full", PinyinUtil.full(r.name))
        put("pinyin_initials", PinyinUtil.initials(r.name))
        put("device_id", r.deviceId)
        put("remote_id", r.id)
    }

    private fun applyMoves(db: SQLiteDatabase, itemId: Long, moves: List<Move>) {
        db.delete("moves", "item_id=?", arrayOf(itemId.toString()))
        for (m in moves) {
            db.insert(
                "moves", null,
                ContentValues().apply {
                    put("item_id", itemId)
                    put("location", m.location)
                    put("moved_at", m.movedAt)
                }
            )
        }
    }

    private fun findLocalId(deviceId: String, remoteId: Long): Long? {
        val cursor = readableDatabase.rawQuery(
            "SELECT id FROM items WHERE device_id=? AND remote_id=?",
            arrayOf(deviceId, remoteId.toString())
        )
        cursor.use { return if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun getSyncItem(localId: Long): SyncItem? {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, device_id, remote_id, name, location, photo, created_at, updated_at, deleted FROM items WHERE id=?",
            arrayOf(localId.toString())
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            val id = it.getLong(0)
            val dev = it.getString(1)
            return SyncItem(
                id = if (dev.isEmpty()) id else it.getLong(2),
                deviceId = dev.ifEmpty { myDeviceId },
                name = it.getString(3),
                location = it.getString(4) ?: "",
                photo = it.getString(5),
                createdAt = it.getLong(6),
                updatedAt = it.getLong(7),
                deleted = it.getInt(8) == 1,
                moves = getMoves(id)
            )
        }
    }

    private fun seedLocations(db: SQLiteDatabase) {
        val exists = db.rawQuery("SELECT COUNT(*) FROM locations", null).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (exists) return
        DEFAULT_LOCATIONS.forEachIndexed { i, name ->
            db.insert(
                "locations", null,
                ContentValues().apply {
                    put("name", name)
                    put("sort_order", i)
                    put("updated_at", 0L)
                }
            )
        }
    }

    private fun backfillPinyin(db: SQLiteDatabase) {
        val rows = ArrayList<Pair<Long, String>>()
        db.rawQuery("SELECT id, name FROM items", null).use {
            while (it.moveToNext()) rows.add(it.getLong(0) to it.getString(1))
        }
        for ((id, name) in rows) {
            db.update(
                "items",
                ContentValues().apply {
                    put("pinyin_full", PinyinUtil.full(name))
                    put("pinyin_initials", PinyinUtil.initials(name))
                },
                "id=?", arrayOf(id.toString())
            )
        }
    }
}
