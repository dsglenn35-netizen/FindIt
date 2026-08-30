package com.home.findit

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 列表行绑定（「最近」「按位置」两个适配器共用） */
object RowBinder {
    fun bind(
        context: Context,
        view: View,
        item: Item,
        onEdit: (Item) -> Unit,
        onDelete: (Item) -> Unit,
        onShare: (Item) -> Unit,
        onPhoto: (Item) -> Unit
    ) {
        val thumb = view.findViewById<ImageView>(R.id.thumb)
        val name = view.findViewById<TextView>(R.id.itemName)
        val loc = view.findViewById<TextView>(R.id.itemLocation)
        val time = view.findViewById<TextView>(R.id.itemTime)
        val del = view.findViewById<ImageButton>(R.id.btnDelete)
        val share = view.findViewById<ImageButton>(R.id.btnShare)

        name.text = item.name
        loc.text = if (item.location.isBlank()) "（未记录位置）" else "📍 ${item.location}"
        time.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(item.createdAt))

        val photo = item.photo
        if (photo != null && File(photo).exists()) {
            thumb.setImageBitmap(PhotoUtils.loadThumb(photo))
        } else {
            thumb.setImageResource(R.drawable.ic_placeholder)
        }

        view.setOnClickListener { onEdit(item) }
        thumb.setOnClickListener { onPhoto(item) }
        share.setOnClickListener { onShare(item) }
        del.setOnClickListener { onDelete(item) }
    }
}

/** 最近记录列表适配器 */
class ItemsAdapter(
    private val context: Context,
    private val items: List<Item>,
    private val onEdit: (Item) -> Unit,
    private val onDelete: (Item) -> Unit,
    private val onShare: (Item) -> Unit,
    private val onPhoto: (Item) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Item = items[position]

    override fun getItemId(position: Int): Long = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_row, parent, false)
        RowBinder.bind(context, view, items[position], onEdit, onDelete, onShare, onPhoto)
        return view
    }
}
