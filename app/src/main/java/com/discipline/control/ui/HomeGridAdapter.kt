package com.discipline.control.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.discipline.control.R
import com.discipline.control.core.LauncherEntry

/**
 * 桌面网格适配器：应用图标 + 分组（文件夹）格子两种类型。
 */
class HomeGridAdapter(private val ctx: Context) : BaseAdapter() {

    private var data: List<LauncherEntry> = emptyList()
    private var icons: Map<String, Drawable?> = emptyMap()

    fun setData(entries: List<LauncherEntry>, icons: Map<String, Drawable?>) {
        this.data = entries
        this.icons = icons
        notifyDataSetChanged()
    }

    override fun getCount(): Int = data.size

    override fun getItem(position: Int): LauncherEntry = data[position]

    override fun getItemId(position: Int): Long = data[position].key.hashCode().toLong()

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int =
        if (data[position] is LauncherEntry.FolderEntry) 1 else 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        return when (val entry = data[position]) {
            is LauncherEntry.AppEntry -> bindApp(entry, convertView, parent)
            is LauncherEntry.FolderEntry -> bindFolder(entry, convertView, parent)
        }
    }

    private fun bindApp(entry: LauncherEntry.AppEntry, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false)
        view.findViewById<TextView>(R.id.tvLabel).text = entry.label
        val iv = view.findViewById<ImageView>(R.id.ivIcon)
        val d = icons[entry.pkg]
        if (d != null) iv.setImageDrawable(d) else iv.setImageResource(R.drawable.ic_launcher)
        return view
    }

    private fun bindFolder(entry: LauncherEntry.FolderEntry, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_folder, parent, false)
        view.findViewById<TextView>(R.id.tvLabel).text = entry.name
        val slots = intArrayOf(R.id.f1, R.id.f2, R.id.f3, R.id.f4)
        for ((index, id) in slots.withIndex()) {
            val iv = view.findViewById<ImageView>(id)
            val app = entry.apps.getOrNull(index)
            if (app == null) {
                // GONE 而不是 INVISIBLE：1~3 个应用时靠 gravity=center 自动居中
                iv.visibility = View.GONE
                iv.setImageDrawable(null)
            } else {
                iv.visibility = View.VISIBLE
                val d = icons[app.pkg]
                if (d != null) iv.setImageDrawable(d) else iv.setImageResource(R.drawable.ic_launcher)
            }
        }
        view.findViewById<View>(R.id.row2).visibility =
            if (entry.apps.size > 2) View.VISIBLE else View.GONE
        return view
    }
}
