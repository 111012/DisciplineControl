package com.discipline.control.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.discipline.control.R
import com.discipline.control.core.AppEntry

class AppGridAdapter(private val ctx: Context) : BaseAdapter() {

    private var data: List<AppEntry> = emptyList()

    fun setData(list: List<AppEntry>) {
        data = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = data.size

    override fun getItem(position: Int): AppEntry = data[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false)
        val entry = data[position]
        view.findViewById<TextView>(R.id.tvLabel).text = entry.label
        val iv = view.findViewById<ImageView>(R.id.ivIcon)
        if (entry.icon != null) iv.setImageDrawable(entry.icon) else iv.setImageResource(R.drawable.ic_launcher)
        return view
    }
}
