package com.discipline.control.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.discipline.control.R
import com.discipline.control.core.LauncherEntry

/**
 * 桌面网格（RecyclerView）：应用图标 + 分组文件夹两种类型。
 * 拖拽由 HomeActivity 的 ItemTouchHelper 驱动；这里只负责渲染与"合并目标高亮"。
 */
class HomeGridAdapter(
    private val ctx: Context,
    private val onClick: (LauncherEntry) -> Unit
) : RecyclerView.Adapter<HomeGridAdapter.Holder>() {

    private val data = ArrayList<LauncherEntry>()
    private var icons: Map<String, Drawable?> = emptyMap()
    private var mergeTargetKey: String? = null

    fun setData(entries: List<LauncherEntry>, icons: Map<String, Drawable?>) {
        data.clear()
        data.addAll(entries)
        this.icons = icons
        mergeTargetKey = null
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): LauncherEntry? = data.getOrNull(position)

    fun entries(): List<LauncherEntry> = ArrayList(data)

    fun indexOfKey(key: String): Int = data.indexOfFirst { it.key == key }

    /** 拖拽过程中交换两项位置 */
    fun moveItem(from: Int, to: Int) {
        if (from !in data.indices || to !in data.indices || from == to) return
        val entry = data.removeAt(from)
        data.add(to, entry)
        notifyItemMoved(from, to)
    }

    /** 拖拽悬停到可合并目标时高亮（只刷新变化的两项） */
    fun setMergeTarget(key: String?) {
        if (mergeTargetKey == key) return
        val old = mergeTargetKey
        mergeTargetKey = key
        old?.let { k ->
            val i = indexOfKey(k)
            if (i >= 0) notifyItemChanged(i)
        }
        key?.let { k ->
            val i = indexOfKey(k)
            if (i >= 0) notifyItemChanged(i)
        }
    }

    override fun getItemCount(): Int = data.size

    override fun getItemViewType(position: Int): Int =
        if (data[position] is LauncherEntry.FolderEntry) TYPE_FOLDER else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == TYPE_FOLDER) R.layout.item_folder else R.layout.item_app
        return Holder(LayoutInflater.from(ctx).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = data[position]
        val view = holder.itemView
        view.setOnClickListener { onClick(entry) }

        val highlighted = entry.key == mergeTargetKey
        view.background = ContextCompat.getDrawable(
            ctx,
            if (highlighted) R.drawable.bg_merge_target else R.drawable.bg_grid_item
        )
        view.scaleX = if (highlighted) 1.08f else 1f
        view.scaleY = if (highlighted) 1.08f else 1f

        when (entry) {
            is LauncherEntry.AppEntry -> {
                view.findViewById<TextView>(R.id.tvLabel).text = entry.label
                val iv = view.findViewById<ImageView>(R.id.ivIcon)
                val d = icons[entry.pkg]
                if (d != null) iv.setImageDrawable(d) else iv.setImageResource(R.drawable.ic_launcher)
            }

            is LauncherEntry.FolderEntry -> {
                view.findViewById<TextView>(R.id.tvLabel).text = entry.name
                val slots = intArrayOf(R.id.f1, R.id.f2, R.id.f3, R.id.f4)
                for ((index, id) in slots.withIndex()) {
                    val iv = view.findViewById<ImageView>(id)
                    val app = entry.apps.getOrNull(index)
                    if (app == null) {
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
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_FOLDER = 1
    }
}
