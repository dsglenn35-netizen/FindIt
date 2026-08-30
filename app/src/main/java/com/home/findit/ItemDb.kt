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
    val photo: String?,
    val createdAt: Long
)

/** 一条移动历史 */
data class Move(
    val location: String,
    val movedAt: Long
)

/** 本地 SQLite 存储（v2：拼音搜索 + 常用位置 + 移动历史） */
class ItemDb(context: Context) : SQLiteOpenHelper(context, "findit.db", null, 2) {

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
                pinyin_initials TEXT DEFAULT ''
            )"""

        private const val CREATE_LOCATIONS = """CREATE TABLE locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                sort_order INTEGER NOT NULL DEFAULT 0
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
    }

    // ---------- 常用位置 ----------

    fun getLocations(): List<String> {
        val list = ArrayList<String>()
        readableDatabase.rawQuery("SELECT name FROM locations ORDER BY sort_order, id", null).use {
            while (it.moveToNext()) list.add(it.getString(0))
        }
        return list
    }

    fun addLocation(name: String): Boolean {
        val cv = ContentValues().apply {
            put("name", name)
            put("sort_order", getLocations().size)
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
            db.update("locations", ContentValues().apply { put("name", new) }, "name=?", arrayOf(old))
            db.update("items", ContentValues().apply { put("location", new) }, "location=?", arrayOf(old))
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
        val cv = ContentValues().apply {
            put("name", name)
            put("location", location)
            put("photo", photo)
            put("created_at", createdAt)
            put("pinyin_full", PinyinUtil.full(name))
            put("pinyin_initials", PinyinUtil.initials(name))
        }
        return writableDatabase.insert("items", null, cv)
    }

    fun update(id: Long, name: String, location: String, photo: String?) {
        val cv = ContentValues().apply {
            put("name", name)
            put("location", location)
            put("photo", photo)
            put("pinyin_full", PinyinUtil.full(name))
            put("pinyin_initials", PinyinUtil.initials(name))
        }
        writableDatabase.update("items", cv, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("items", "id=?", arrayOf(id.toString()))
        writableDatabase.delete("moves", "item_id=?", arrayOf(id.toString()))
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
        val cursor = if (keyword.isNullOrBlank()) {
            readableDatabase.rawQuery(
                "SELECT id,name,location,photo,created_at FROM items ORDER BY created_at DESC",
                null
            )
        } else {
            val like = "%$keyword%"
            readableDatabase.rawQuery(
                "SELECT id,name,location,photo,created_at FROM items " +
                    "WHERE name LIKE ? OR location LIKE ? OR pinyin_full LIKE ? OR pinyin_initials LIKE ? " +
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
                        createdAt = it.getLong(4)
                    )
                )
            }
        }
        return list
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
                    val photoPath = if (photoBase.isNotEmpty()) photoMap[photoBase]?.absolutePath else null
                    val id = insertFull(
                        name, location, photoPath,
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

    private fun seedLocations(db: SQLiteDatabase) {
        val exists = db.rawQuery("SELECT COUNT(*) FROM locations", null).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (exists) return
        DEFAULT_LOCATIONS.forEachIndexed { i, name ->
            db.insert(
                "locations", null,
                ContentValues().apply {
                    put("name", name)
                    put("sort_order", i)
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
