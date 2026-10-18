package com.discipline.control.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.Apps
import com.discipline.control.core.HomeItem
import com.discipline.control.core.HomeLayoutEngine
import com.discipline.control.core.Store
import com.discipline.control.core.dp

/** 分组管理：重命名 / 解散。新建分组请到桌面长按应用操作。 */
class GroupManageActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_page_gradient)
            setPadding(dp(22), dp(26), dp(22), dp(40))
        }
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(Ui.header(this, "分组") { finish() })
        container.addView(Ui.pageTitle(this, "分组管理"))
        container.addView(TextView(this).apply {
            text = "想新建分组？回桌面长按任意图标 →「移动到分组…」→「＋ 新建分组…」。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@GroupManageActivity, R.color.sub))
            setPadding(0, dp(6), 0, dp(8))
        })

        val folders = Store.layout().items.filterIsInstance<HomeItem.FolderItem>().map { it.folder }
        val card = Ui.card(this)
        if (folders.isEmpty()) {
            card.addView(Ui.row(this, "还没有分组", "长按桌面图标即可创建", null, null))
        } else {
            folders.forEachIndexed { index, folder ->
                if (index > 0) card.addView(Ui.divider(this))
                val names = folder.pkgs.joinToString("、") { Apps.label(this, it) }
                card.addView(
                    Ui.row(
                        this,
                        folder.name,
                        (if (folder.pkgs.isEmpty()) "空分组" else names).take(60),
                        "重命名"
                    ) { promptRename(folder.id, folder.name) }
                )
                card.addView(Ui.divider(this))
                card.addView(
                    Ui.row(this, "解散「${folder.name}」", "组内应用回到桌面", "解散") {
                        AlertDialog.Builder(this)
                            .setTitle("解散「${folder.name}」？")
                            .setMessage("组内的 ${folder.pkgs.size} 个应用会回到桌面，管控规则不受影响。")
                            .setPositiveButton("解散") { _, _ ->
                                Store.saveLayout(HomeLayoutEngine.deleteFolder(Store.layout(), folder.id))
                                Toast.makeText(this, "已解散", Toast.LENGTH_SHORT).show()
                                render()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                )
            }
        }
        container.addView(card)
    }

    private fun promptRename(folderId: String, current: String) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current)
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名分组")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名字不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    Store.saveLayout(HomeLayoutEngine.renameFolder(Store.layout(), folderId, name))
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
