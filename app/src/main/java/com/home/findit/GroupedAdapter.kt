package com.home.findit

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/** 列表行：位置分组头 或 物品条目 */
sealed class ListRow {
    data class Header(val location: String, val count: Int) : ListRow()
    data class Entry(val item: Item) : ListRow()
}

/** 按位置分组浏览适配器（带分组头） */
class GroupedAdapter(
    private val context: Context,
    private val rows: List<ListRow>,
    private val onEdit: (Item) -> Unit,
    private val onDelete: (Item) -> Unit,
    private val onShare: (Item) -> Unit,
    private val onPhoto: (Item) -> Unit
) : BaseAdapter() {

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is ListRow.Header) 0 else 1

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = when (val r = rows[position]) {
        is ListRow.Header -> -(position + 1).toLong()
        is ListRow.Entry -> r.item.id
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return when (val row = rows[position]) {
            is ListRow.Header -> {
                val v = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.header_row, parent, false)
                v.findViewById<TextView>(R.id.headerLocation).text = row.location
                v.findViewById<TextView>(R.id.headerCount).text = "${row.count} 件"
                v
            }
            is ListRow.Entry -> {
                val v = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_row, parent, false)
                RowBinder.bind(context, v, row.item, onEdit, onDelete, onShare, onPhoto)
                v
            }
        }
    }
}
