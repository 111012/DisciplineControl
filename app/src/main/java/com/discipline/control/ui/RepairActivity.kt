package com.discipline.control.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.Notifier
import com.discipline.control.core.dp
import com.discipline.control.service.GuardAccessibilityService

/**
 * 权限掉了之后的"修理页"：不需要 PIN，只提供修复入口，规则不可改。
 * 无障碍被系统/安全中心关掉时，守护会彻底失效，必须让用户能一眼看到并一键修好。
 */
class RepairActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(30))
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

        val a11y = GuardAccessibilityService.isEnabled(this)
        val isHome = isDefaultLauncher()
        val notif = Notifier.canPost()

        container.addView(Ui.pageTitle(this, if (a11y && isHome) "一切正常" else "需要修复"))
        container.addView(TextView(this).apply {
            text = "这里只能修复，不能改规则（改规则要 PIN）。修好后本页会自动关闭。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@RepairActivity, R.color.sub))
            setPadding(0, dp(8), 0, dp(4))
        })

        val card = Ui.card(this)
        card.addView(
            Ui.row(
                this,
                if (a11y) "✅ 无障碍服务" else "❌ 无障碍服务（拦截失效）",
                if (a11y) "正常运行" else "被系统关掉了，点右边重新开启「自律控制 · 应用守护」",
                if (a11y) null else "去开启"
            ) {
                if (!a11y) openAccessibilitySettings()
            }
        )
        card.addView(Ui.divider(this))
        card.addView(
            Ui.row(
                this,
                if (isHome) "✅ 默认桌面" else "❌ 默认桌面",
                if (isHome) "图标可以真正隐藏" else "没设成默认桌面，图标不会消失",
                if (isHome) null else "去设置"
            ) {
                if (!isHome) openHomeSettings()
            }
        )
        card.addView(Ui.divider(this))
        card.addView(
            Ui.row(
                this,
                if (notif) "✅ 通知权限" else "❌ 通知权限",
                if (notif) "可以收到到点提醒" else "收不到提醒，点右边允许",
                if (notif) null else "去允许"
            ) {
                if (!notif) openNotificationSettings()
            }
        )
        container.addView(card)

        if (a11y && isHome) {
            container.addView(TextView(this).apply {
                text = "守护已恢复，正在关闭本页…"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@RepairActivity, R.color.ok))
                setPadding(dp(6), dp(18), 0, 0)
            })
        } else {
            container.addView(TextView(this).apply {
                text = "提示：不要用系统的「强行停止」关本应用 —— 那会连带关掉无障碍服务，\n之后必须重新打开一次无障碍才能恢复拦截。"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@RepairActivity, R.color.sub))
                setPadding(dp(6), dp(18), dp(6), 0)
            })
        }
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = runCatching {
            packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() ?: return false
        return res.activityInfo?.packageName == packageName
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { Toast.makeText(this, "打不开无障碍设置", Toast.LENGTH_SHORT).show() }
    }

    private fun openHomeSettings() {
        val ok = runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    @Suppress("unused")
    private fun finishIfReady() {
        if (GuardAccessibilityService.isEnabled(this) && isDefaultLauncher()) {
            (this as Activity).finish()
        }
    }
}
