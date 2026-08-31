package com.home.findit

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 「放哪了」主界面 v2.0
 * 最近 / 按位置 / 统计 / 同步 四个页签；语音输入；拍照；备份导入导出；移动历史；分享；大图查看；局域网双设备同步
 */
class MainActivity : Activity() {

    private enum class Tab { RECENT, GROUP, STATS, SYNC }

    private lateinit var db: ItemDb

    private lateinit var listView: ListView
    private lateinit var nameInput: EditText
    private lateinit var locationInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var photoPreview: ImageView
    private lateinit var chipsContainer: LinearLayout
    private lateinit var countText: TextView
    private lateinit var emptyView: TextView
    private lateinit var statsView: android.widget.ScrollView
    private lateinit var statsContent: LinearLayout
    private lateinit var syncView: android.widget.ScrollView

    private lateinit var tabRecent: Button
    private lateinit var tabGroup: Button
    private lateinit var tabStats: Button
    private lateinit var tabSync: Button

    // 同步页控件
    private lateinit var syncDeviceName: EditText
    private lateinit var btnHostToggle: Button
    private lateinit var hostInfoText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var hostInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var btnSyncNow: Button
    private lateinit var syncStatusText: TextView

    private var items: List<Item> = emptyList()
    private var selectedTab = Tab.RECENT

    private var pendingPhotoPath: String? = null
    private var pendingPhotoFile: File? = null
    private var editingId: Long? = null
    private var editPhotoView: ImageView? = null
    private var voiceTarget: EditText? = null
    private var previewingThumb: Bitmap? = null

    private var syncServer: SyncServer? = null
    private var appResumed = false
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (appResumed && SyncPrefs.hostUrl(this@MainActivity) != null) {
                SyncEngine.doSync(this@MainActivity, db) { outcome ->
                    if (outcome.ok) updateSyncStatus(outcome.message)
                }
            }
            syncHandler.postDelayed(this, 30_000)
        }
    }

    companion object {
        private const val REQ_CAMERA = 1001
        private const val REQ_SPEECH = 1002
        private const val REQ_IMPORT = 1003
        private const val AUTHORITY = "com.home.findit.fileprovider"
        private const val SYNC_PORT = 8888
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = ItemDb(this)
        listView = findViewById(R.id.list)
        nameInput = findViewById(R.id.inputName)
        locationInput = findViewById(R.id.inputLocation)
        searchInput = findViewById(R.id.inputSearch)
        photoPreview = findViewById(R.id.photoPreview)
        chipsContainer = findViewById(R.id.chipsContainer)
        countText = findViewById(R.id.countText)
        emptyView = findViewById(R.id.emptyView)
        statsView = findViewById(R.id.statsView)
        statsContent = findViewById(R.id.statsContent)
        syncView = findViewById(R.id.syncView)
        tabRecent = findViewById(R.id.tabRecent)
        tabGroup = findViewById(R.id.tabGroup)
        tabStats = findViewById(R.id.tabStats)
        tabSync = findViewById(R.id.tabSync)
        syncDeviceName = findViewById(R.id.syncDeviceName)
        btnHostToggle = findViewById(R.id.btnHostToggle)
        hostInfoText = findViewById(R.id.hostInfoText)
        qrImage = findViewById(R.id.qrImage)
        hostInput = findViewById(R.id.hostInput)
        pinInput = findViewById(R.id.pinInput)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        syncStatusText = findViewById(R.id.syncStatusText)

        findViewById<Button>(R.id.btnCamera).setOnClickListener { startCamera() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { onSave() }
        findViewById<ImageButton>(R.id.micName).setOnClickListener { startVoice(nameInput) }
        findViewById<ImageButton>(R.id.micLoc).setOnClickListener { startVoice(locationInput) }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        photoPreview.setOnClickListener { startCamera() }

        tabRecent.setOnClickListener { setTab(Tab.RECENT) }
        tabGroup.setOnClickListener { setTab(Tab.GROUP) }
        tabStats.setOnClickListener { setTab(Tab.STATS) }
        tabSync.setOnClickListener { setTab(Tab.SYNC) }

        // 同步页
        syncDeviceName.setText(SyncPrefs.deviceName(this))
        findViewById<Button>(R.id.btnSaveDeviceName).setOnClickListener {
            val n = syncDeviceName.text.toString().trim()
            if (n.isEmpty()) {
                toast("设备名不能为空")
            } else {
                SyncPrefs.setDeviceName(this, n)
                toast("设备名已保存：$n")
            }
        }
        btnHostToggle.setOnClickListener { toggleHost() }
        findViewById<Button>(R.id.btnScanQr).setOnClickListener { scanQr() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectHost() }
        btnSyncNow.setOnClickListener { doSyncWithFeedback("正在同步…") }

        buildChips()
        setupSearch()
        setTab(Tab.RECENT)
    }

    override fun onResume() {
        super.onResume()
        appResumed = true
        syncHandler.postDelayed(syncRunnable, 30_000)
    }

    override fun onPause() {
        super.onPause()
        appResumed = false
        syncHandler.removeCallbacks(syncRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        syncHandler.removeCallbacks(syncRunnable)
        stopHost()
    }

    // ---------- 页签 ----------

    private fun setTab(tab: Tab) {
        selectedTab = tab
        tabRecent.isSelected = tab == Tab.RECENT
        tabGroup.isSelected = tab == Tab.GROUP
        tabStats.isSelected = tab == Tab.STATS
        tabSync.isSelected = tab == Tab.SYNC
        refresh()
    }

    // ---------- 常用位置（自定义） ----------

    private fun buildChips() {
        chipsContainer.removeAllViews()
        for (loc in db.getLocations()) {
            chipsContainer.addView(makeChip(loc))
        }
        val plus = TextView(this).apply {
            text = "+ 添加"
            setTextSize(13f)
            setTextColor(getColor(R.color.chip_text))
            setBackgroundResource(R.drawable.bg_chip_dashed)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            isFocusable = true
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        plus.layoutParams = lp
        plus.setOnClickListener { addLocationDialog() }
        chipsContainer.addView(plus)
    }

    private fun makeChip(loc: String): TextView {
        val chip = TextView(this).apply {
            text = loc
            setTextSize(13f)
            setTextColor(getColor(R.color.chip_text))
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            isFocusable = true
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, dp(8), 0)
        chip.layoutParams = lp
        chip.setOnClickListener {
            locationInput.setText(loc)
            locationInput.setSelection(loc.length)
        }
        chip.setOnLongClickListener {
            manageLocationDialog(loc)
            true
        }
        return chip
    }

    private fun addLocationDialog() {
        val input = EditText(this).apply {
            hint = "新位置名称，如：车里、公司抽屉"
            setTextSize(14f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("添加常用位置")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val n = input.text.toString().trim()
                when {
                    n.isEmpty() -> toast("名称不能为空")
                    !db.addLocation(n) -> toast("该位置已存在")
                    else -> {
                        buildChips()
                        toast("已添加「$n」，点它即可填入位置")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun manageLocationDialog(loc: String) {
        val opts = arrayOf("重命名", "删除")
        AlertDialog.Builder(this)
            .setTitle("「$loc」")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> renameLocationDialog(loc)
                    1 -> confirmDeleteLocation(loc)
                }
            }
            .show()
    }

    private fun renameLocationDialog(old: String) {
        val input = EditText(this).apply {
            setText(old)
            setTextSize(14f)
            setSelection(old.length)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名位置")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty()) {
                    toast("名称不能为空")
                } else {
                    db.renameLocation(old, n)
                    buildChips()
                    refresh()
                    toast("已重命名为「$n」")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteLocation(loc: String) {
        AlertDialog.Builder(this)
            .setTitle("删除位置")
            .setMessage("从常用位置中删除「$loc」？（已有记录不受影响）")
            .setPositiveButton("删除") { _, _ ->
                db.deleteLocation(loc)
                buildChips()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 搜索 ----------

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh()
            }
        })
    }

    // ---------- 数据刷新 ----------

    private fun refresh() {
        val q = searchInput.text.toString().trim()
        items = if (q.isEmpty()) db.queryAll() else db.search(q)

        val showList = selectedTab == Tab.RECENT || selectedTab == Tab.GROUP
        listView.visibility = if (showList) View.VISIBLE else View.GONE
        statsView.visibility = if (selectedTab == Tab.STATS) View.VISIBLE else View.GONE
        syncView.visibility = if (selectedTab == Tab.SYNC) View.VISIBLE else View.GONE

        when (selectedTab) {
            Tab.RECENT -> {
                listView.adapter = ItemsAdapter(
                    this, items, ::openEditDialog, ::confirmDelete, ::shareItem, ::showItemPhoto
                )
                countText.text = if (q.isEmpty()) "共 ${items.size} 件" else "搜索「$q」：${items.size} 条"
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
            Tab.GROUP -> {
                val rows = buildGroupedRows(items)
                listView.adapter = GroupedAdapter(
                    this, rows, ::openEditDialog, ::confirmDelete, ::shareItem, ::showItemPhoto
                )
                val groupCount = rows.count { it is ListRow.Header }
                countText.text = if (q.isEmpty()) "$groupCount 个位置 · ${items.size} 件" else "搜索「$q」：${items.size} 条"
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
            Tab.STATS -> {
                buildStats(items)
                emptyView.visibility = View.GONE
                countText.text = "统计"
            }
            Tab.SYNC -> {
                emptyView.visibility = View.GONE
                countText.text = "多设备同步"
            }
        }
    }

    // ---------- 分组浏览 ----------

    private fun buildGroupedRows(items: List<Item>): List<ListRow> {
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (it in items) {
            val k = if (it.location.isBlank()) "（未记录位置）" else it.location
            groups.getOrPut(k) { mutableListOf() }.add(it)
        }
        val rows = ArrayList<ListRow>()
        groups.entries
            .sortedWith(compareByDescending<Map.Entry<String, MutableList<Item>>> { it.value.size }.thenBy { it.key })
            .forEach { (loc, list) ->
                rows.add(ListRow.Header(loc, list.size))
                rows.addAll(list.sortedByDescending { it.createdAt }.map { ListRow.Entry(it) })
            }
        return rows
    }

    // ---------- 统计图表 ----------

    private fun buildStats(items: List<Item>) {
        statsContent.removeAllViews()
        val map = LinkedHashMap<String, Int>()
        for (it in items) {
            val k = if (it.location.isBlank()) "（未记录位置）" else it.location
            map[k] = (map[k] ?: 0) + 1
        }
        if (map.isEmpty()) {
            statsContent.addView(
                TextView(this).apply {
                    text = "暂无数据"
                    setTextColor(getColor(R.color.text_secondary))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, 0)
                }
            )
            return
        }
        val sorted = map.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val maxCount = sorted.maxOf { it.value }

        statsContent.addView(
            TextView(this).apply {
                text = "共 ${items.size} 件物品，分散在 ${sorted.size} 个位置"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, 0, 0, dp(10))
            }
        )

        for ((loc, count) in sorted) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val name = TextView(this).apply {
                text = loc
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val countTv = TextView(this).apply {
                text = "$count"
                textSize = 13f
                setTextColor(getColor(R.color.primary))
                minWidth = dp(30)
                gravity = Gravity.END
            }
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_preview)
            }
            val bar = View(this).apply {
                setBackgroundColor(getColor(R.color.primary))
            }
            val trackLp = LinearLayout.LayoutParams(0, dp(10), (maxCount + 1).toFloat())
            val barLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, count.toFloat())
            track.layoutParams = trackLp
            track.addView(bar, barLp)

            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(countTv)
            row.addView(
                track,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                    .apply { leftMargin = dp(10) }
            )
            statsContent.addView(row)
        }
    }

    // ---------- 保存 ----------

    private fun onSave() {
        val n = nameInput.text.toString().trim()
        val l = locationInput.text.toString().trim()
        if (n.isEmpty()) {
            toast("请输入物品名称")
            return
        }
        if (l.isEmpty()) {
            toast("请输入存放位置")
            return
        }
        db.insert(n, l, pendingPhotoPath)
        toast("已记录：$n → $l")
        nameInput.text.clear()
        locationInput.text.clear()
        clearPendingPhoto()
        refresh()
        hideKeyboard()
    }

    private fun clearPendingPhoto() {
        pendingPhotoPath = null
        pendingPhotoFile?.delete()
        pendingPhotoFile = null
        previewingThumb?.recycle()
        previewingThumb = null
        photoPreview.setImageDrawable(null)
        photoPreview.visibility = View.GONE
    }

    // ---------- 拍照 ----------

    private fun startCamera() {
        try {
            pendingPhotoFile?.delete()
            val file = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            val uri: Uri = FileProvider.getUriForFile(this, AUTHORITY, file)
            pendingPhotoFile = file
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            startActivityForResult(intent, REQ_CAMERA)
        } catch (e: Exception) {
            toast("无法打开相机：${e.message}")
        }
    }

    // ---------- 语音输入 ----------

    private fun startVoice(target: EditText) {
        voiceTarget = target
        val prompt = if (target === nameInput) "请说出物品名称" else "请说出存放位置"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        try {
            startActivityForResult(intent, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            voiceTarget = null
            toast("设备不支持语音识别")
        }
    }

    // ---------- 备份 ----------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "导出备份")
        pm.menu.add(0, 2, 0, "导入备份")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> exportBackup()
                2 -> importBackup()
            }
            true
        }
        pm.show()
    }

    private fun exportBackup() {
        try {
            val dir = File(cacheDir, "backup").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val zipFile = File(dir, "findit_backup_$stamp.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                zos.putNextEntry(ZipEntry("findit.json"))
                zos.write(buildBackupJson().toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                val photosDir = File(filesDir, "photos")
                if (photosDir.exists()) {
                    photosDir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            zos.putNextEntry(ZipEntry("photos/${f.name}"))
                            FileInputStream(f).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            val uri = FileProvider.getUriForFile(this, AUTHORITY, zipFile)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "保存备份到…"))
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    private fun buildBackupJson(): JSONObject {
        val root = JSONObject()
        root.put("app", "FindIt")
        root.put("schema", 1)
        root.put("exported_at", System.currentTimeMillis())
        val locArr = org.json.JSONArray()
        db.getLocations().forEach { locArr.put(it) }
        root.put("locations", locArr)
        val itemsArr = org.json.JSONArray()
        for (it in db.queryAll()) {
            val o = JSONObject()
            o.put("name", it.name)
            o.put("location", it.location)
            o.put("photo", it.photo?.substringAfterLast('/'))
            o.put("created_at", it.createdAt)
            val movesArr = org.json.JSONArray()
            for (m in db.getMoves(it.id)) {
                val mo = JSONObject()
                mo.put("location", m.location)
                mo.put("moved_at", m.movedAt)
                movesArr.put(mo)
            }
            o.put("moves", movesArr)
            itemsArr.put(o)
        }
        root.put("items", itemsArr)
        return root
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "选择备份文件"), REQ_IMPORT)
        } catch (e: Exception) {
            toast("无法打开文件选择器")
        }
    }

    private fun onImportPicked(uri: Uri) {
        try {
            val dir = File(cacheDir, "backup").apply { mkdirs() }
            val tmp = File(dir, "import_${System.currentTimeMillis()}.zip")
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                toast("无法读取文件")
                return
            }
            input.use { i -> FileOutputStream(tmp).use { i.copyTo(it) } }
            AlertDialog.Builder(this)
                .setTitle("导入备份")
                .setMessage("导入将覆盖当前全部记录，确定继续吗？")
                .setPositiveButton("导入") { _, _ ->
                    if (restoreBackup(tmp)) {
                        buildChips()
                        refresh()
                        toast("导入成功")
                    } else {
                        toast("导入失败：文件格式不正确")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    private fun restoreBackup(zipFile: File): Boolean {
        return try {
            val photosDir = File(filesDir, "photos").apply { mkdirs() }
            photosDir.listFiles()?.forEach { it.delete() }
            val photoMap = HashMap<String, File>()
            var jsonStr: String? = null
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    when {
                        e.name == "findit.json" ->
                            jsonStr = zis.readBytes().toString(Charsets.UTF_8)
                        e.name.startsWith("photos/") && !e.isDirectory -> {
                            val base = e.name.removePrefix("photos/")
                            val dest = File(photosDir, base)
                            FileOutputStream(dest).use { zis.copyTo(it) }
                            photoMap[base] = dest
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            val json = jsonStr?.let { JSONObject(it) } ?: return false
            if (json.optString("app") != "FindIt") return false
            db.replaceAllFromBackup(json, photoMap)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- 分享 ----------

    private fun shareItem(item: Item) {
        val text = buildString {
            append("【${item.name}】放在「${if (item.location.isBlank()) "未记录" else item.location}」\n")
            append("存放时间：${formatTime(item.createdAt)}")
        }
        try {
            val photoFile = PhotoFiles.resolve(this, item.photo)
            val intent = if (photoFile != null && photoFile.exists()) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this@MainActivity, AUTHORITY, photoFile))
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            }
            startActivity(Intent.createChooser(intent, "分享「${item.name}」"))
        } catch (e: Exception) {
            toast("分享失败：${e.message}")
        }
    }

    // ---------- 大图查看 ----------

    private fun showItemPhoto(item: Item) {
        val file = PhotoFiles.resolve(this, item.photo)
        if (file == null || !file.exists()) {
            toast("该记录没有照片")
            return
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { dialog.dismiss() }
        }
        val bmp = PhotoUtils.loadFull(file.absolutePath)
        if (bmp != null) img.setImageBitmap(bmp)
        dialog.setContentView(img)
        dialog.show()
    }

    // ---------- 编辑 / 删除 ----------

    private fun openEditDialog(item: Item) {
        editingId = item.id
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null)
        val name = view.findViewById<EditText>(R.id.dlgName)
        val loc = view.findViewById<EditText>(R.id.dlgLocation)
        val photo = view.findViewById<ImageView>(R.id.dlgPhoto)
        val retake = view.findViewById<Button>(R.id.dlgRetake)
        val history = view.findViewById<TextView>(R.id.dlgHistory)
        editPhotoView = photo

        name.setText(item.name)
        loc.setText(item.location)
        val photoFile = PhotoFiles.resolve(this, item.photo)
        if (photoFile != null && photoFile.exists()) {
            photo.setImageBitmap(PhotoUtils.loadThumb(photoFile.absolutePath, 128))
        } else {
            photo.setImageResource(R.drawable.ic_placeholder)
        }

        val moves = db.getMoves(item.id)
        if (moves.isNotEmpty()) {
            val sb = StringBuilder("位置历史：\n")
            for ((i, m) in moves.withIndex()) {
                if (i >= 8) break
                sb.append("· ${formatTime(m.movedAt)}  ${m.location}\n")
            }
            history.text = sb.toString().trimEnd()
            history.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑记录")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val n = name.text.toString().trim()
                val l = loc.text.toString().trim()
                if (n.isEmpty()) {
                    toast("物品名称不能为空")
                } else {
                    if (l != item.location) db.addMove(item.id, l)
                    db.update(item.id, n, l, item.photo)
                    refresh()
                    toast("已保存")
                }
            }
            .setNegativeButton("取消", null)
            .create()

        retake.setOnClickListener { startCamera() }
        dialog.setOnDismissListener { editingId = null }
        dialog.show()
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("确定删除「${item.name}」吗？\n（已同步到其他设备的话，下次同步也会一并删除）")
            .setPositiveButton("删除") { _, _ ->
                db.delete(item.id)
                refresh()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 同步（主机/客户端） ----------

    private fun toggleHost() {
        if (syncServer != null) {
            stopHost()
            return
        }
        val server = SyncServer(this, db, SYNC_PORT)
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            syncServer = server
            updateHostUi(true)
            toast("主机已开启，让另一台设备扫码连接")
        } catch (e: Exception) {
            toast("启动主机失败：${e.message}")
        }
    }

    private fun stopHost() {
        syncServer?.stop()
        syncServer = null
        updateHostUi(false)
    }

    private fun updateHostUi(on: Boolean) {
        if (on) {
            btnHostToggle.text = getString(R.string.sync_host_stop)
            val ip = localIp() ?: "未知IP"
            val pin = syncServer?.pin ?: ""
            hostInfoText.text = "地址：http://$ip:$SYNC_PORT\nPIN：$pin"
            val qrContent = "findit://sync?host=$ip&port=$SYNC_PORT&pin=$pin"
            val bmp = QrUtils.generate(qrContent, 512)
            if (bmp != null) {
                qrImage.setImageBitmap(bmp)
                qrImage.visibility = View.VISIBLE
            }
        } else {
            btnHostToggle.text = getString(R.string.sync_host_start)
            hostInfoText.text = getString(R.string.sync_host_off)
            qrImage.setImageDrawable(null)
            qrImage.visibility = View.GONE
        }
    }

    private fun localIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null else String.format(
                Locale.US, "%d.%d.%d.%d",
                ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun scanQr() {
        try {
            IntentIntegrator(this).initiateScan()
        } catch (e: Exception) {
            toast("无法启动扫码：${e.message}")
        }
    }

    private fun parseSyncQr(content: String): Boolean {
        return try {
            val uri = Uri.parse(content)
            if (uri.scheme != "findit") return false
            val host = uri.getQueryParameter("host") ?: return false
            val port = uri.getQueryParameter("port") ?: "$SYNC_PORT"
            val pin = uri.getQueryParameter("pin") ?: ""
            hostInput.setText("http://$host:$port")
            pinInput.setText(pin)
            connectHost()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun connectHost() {
        var url = hostInput.text.toString().trim()
        val pin = pinInput.text.toString().trim()
        if (url.isEmpty()) {
            toast("请输入主机地址")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        if (pin.isEmpty()) {
            toast("请输入 PIN")
            return
        }
        SyncPrefs.setHost(this, url, pin, null)
        SyncPrefs.setLastSyncAt(this, 0L)
        doSyncWithFeedback("正在连接主机并同步…")
    }

    private fun doSyncWithFeedback(loadingMsg: String) {
        syncStatusText.text = loadingMsg
        SyncEngine.doSync(this, db) { outcome ->
            updateSyncStatus(outcome.message)
            if (outcome.ok) {
                refresh()
                toast(outcome.message)
            }
        }
    }

    private fun updateSyncStatus(msg: String) {
        syncStatusText.text = msg
    }

    // ---------- 结果回调 ----------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // zxing 扫码结果
        val scanResult: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            val content = scanResult.contents
            if (content != null && !parseSyncQr(content)) {
                toast("不是有效的同步二维码")
            }
            return
        }

        when (requestCode) {
            REQ_CAMERA -> handleCameraResult(resultCode, data)
            REQ_SPEECH -> handleSpeechResult(resultCode, data)
            REQ_IMPORT -> if (resultCode == RESULT_OK && data?.data != null) onImportPicked(data.data!!)
        }
    }

    private fun handleSpeechResult(resultCode: Int, data: Intent?) {
        val target = voiceTarget
        voiceTarget = null
        if (resultCode != RESULT_OK || target == null) return
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val text = results?.firstOrNull()
        if (text.isNullOrBlank()) {
            toast("没有听清，再试一次")
            return
        }
        target.setText(text)
        target.setSelection(text.length)
    }

    private fun handleCameraResult(resultCode: Int, data: Intent?) {
        val file = pendingPhotoFile
        if (resultCode == RESULT_OK) {
            val saved: String? = when {
                file != null && file.exists() && file.length() > 0 ->
                    copyToPhotos(file)?.also { file.delete() }
                else -> saveThumbBitmap(data)
            }
            if (saved != null) {
                val editing = editingId
                if (editing != null) {
                    val item = items.firstOrNull { it.id == editing }
                    if (item != null) {
                        db.update(item.id, item.name, item.location, saved)
                        PhotoFiles.resolve(this, item.photo)?.delete()
                        editPhotoView?.setImageBitmap(PhotoUtils.loadThumb(
                            PhotoFiles.resolve(this, saved)?.absolutePath ?: "", 128))
                        refresh()
                        toast("照片已更新")
                    }
                } else {
                    pendingPhotoPath = saved
                    showPreview(saved)
                }
            } else {
                toast("照片保存失败")
            }
        } else {
            toast("已取消拍照")
        }
        pendingPhotoFile = null
    }

    private fun copyToPhotos(src: File): String? {
        return try {
            val dir = File(filesDir, "photos").apply { mkdirs() }
            val dest = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            src.copyTo(dest, overwrite = true)
            dest.name
        } catch (e: Exception) {
            null
        }
    }

    private fun saveThumbBitmap(data: Intent?): String? {
        val bmp = data?.extras?.get("data") as? Bitmap ?: return null
        return try {
            val dir = File(filesDir, "photos").apply { mkdirs() }
            val dest = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(dest).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            dest.name
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun showPreview(path: String) {
        previewingThumb?.recycle()
        val file = PhotoFiles.resolve(this, path)
        previewingThumb = if (file != null) PhotoUtils.loadThumb(file.absolutePath, 128) else null
        previewingThumb?.let { photoPreview.setImageBitmap(it) }
        photoPreview.visibility = View.VISIBLE
    }

    // ---------- 工具 ----------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(nameInput.windowToken, 0)
    }
}
