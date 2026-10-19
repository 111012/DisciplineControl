package com.discipline.control.ui

import android.os.Bundle
import android.view.View
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.AppEntry
import com.discipline.control.core.Apps
import com.discipline.control.core.HomeLayoutEngine
import com.discipline.control.core.Store
import com.discipline.control.core.dp

/**
 * 分组内容页：展示一个文件夹里的应用。
 * 点图标直接启动；长按可移出分组；移空后自动返回桌面。
 */
class FolderActivity : AppCompatActivity() {

    private var folderId: String = ""
    private lateinit var grid: GridView
    private lateinit var adapter: AppGridAdapter
    private lateinit var tvTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderId = intent.getStringExtra("folderId") ?: run {
            finish()
            return
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.folder_in, R.anim.folder_out)
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_page_gradient)
            setPadding(dp(24), dp(24), dp(24), dp(12))
        }
        root.addView(Ui.header(this, "分组") { finish() })
        tvTitle = Ui.pageTitle(this, "分组")
        root.addView(tvTitle)
        root.addView(TextView(this).apply {
            text = "点图标启动 · 长按可移出分组"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@FolderActivity, R.color.sub))
            setPadding(0, dp(4), 0, dp(10))
        })
        grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = dp(6)
            verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
        }
        adapter = AppGridAdapter(this)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position)
            launchApp(app.pkg)
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            askRemove(adapter.getItem(position).pkg)
            true
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun refresh() {
        val folder = Store.layout().folder(folderId)
        if (folder == null) {
            finish()
            return
        }
        tvTitle.text = folder.name
        val apps = folder.pkgs.map { pkg ->
            AppEntry(pkg, Apps.label(this, pkg), Apps.icon(this, pkg))
        }
        adapter.setData(apps)
    }

    private fun askRemove(pkg: String) {
        AlertDialog.Builder(this)
            .setTitle("把「${Apps.label(this, pkg)}」移出分组？")
            .setPositiveButton("移出") { _, _ ->
                val layout = HomeLayoutEngine.removeFromFolder(Store.layout(), pkg)
                Store.saveLayout(layout)
                Toast.makeText(this, "已移出，回到桌面", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Toast.makeText(this, "应用已卸载", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }
}
