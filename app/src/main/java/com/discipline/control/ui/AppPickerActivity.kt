package com.discipline.control.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.AppEntry
import com.discipline.control.core.AppRule
import com.discipline.control.core.Apps
import com.discipline.control.core.Store
import com.discipline.control.core.dp

class AppPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(10))
        }
        val header = Ui.header(this, "添加") { finish() }
        root.addView(header)
        root.addView(Ui.pageTitle(this, "选择要管控的应用"))
        root.addView(TextView(this).apply {
            text = "添加后默认：每天 20:00–23:00 开放 · 最多 3 次 · 单次 60 分钟（之后点应用可以改）"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AppPickerActivity, R.color.sub))
            setPadding(0, dp(6), 0, dp(12))
        })

        val list = ListView(this)
        list.divider = null
        list.dividerHeight = dp(4)
        val all = Apps.launchable(this)
        list.adapter = PickerAdapter(this, all)
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = all[position]
            val existing = Store.rule(entry.pkg)
            if (existing != null) {
                Toast.makeText(this, "「${entry.label}」已经在管控中", Toast.LENGTH_SHORT).show()
                startActivity(
                    android.content.Intent(this, AppRuleEditActivity::class.java)
                        .putExtra("pkg", entry.pkg)
                )
            } else {
                Store.upsertRule(AppRule.default(entry.pkg, entry.label))
                Toast.makeText(this, "已添加「${entry.label}」", Toast.LENGTH_SHORT).show()
                startActivity(
                    android.content.Intent(this, AppRuleEditActivity::class.java)
                        .putExtra("pkg", entry.pkg)
                )
                finish()
            }
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private class PickerAdapter(private val ctx: Context, private val data: List<AppEntry>) : BaseAdapter() {
        override fun getCount(): Int = data.size
        override fun getItem(position: Int): AppEntry = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_pick, parent, false)
            val e = data[position]
            val icon = view.findViewById<ImageView>(R.id.ivIcon)
            if (e.icon != null) icon.setImageDrawable(e.icon) else icon.setImageResource(R.drawable.ic_launcher)
            view.findViewById<TextView>(R.id.tvLabel).text = e.label
            val managed = Store.rule(e.pkg) != null
            view.findViewById<TextView>(R.id.tvTag).apply {
                visibility = if (managed) View.VISIBLE else View.GONE
                text = "已管控"
            }
            return view
        }
    }
}
