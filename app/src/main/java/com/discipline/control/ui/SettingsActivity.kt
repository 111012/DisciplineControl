package com.discipline.control.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.discipline.control.BuildConfig
import com.discipline.control.R
import com.discipline.control.core.Apps
import com.discipline.control.core.Notifier
import com.discipline.control.core.Overlay
import com.discipline.control.core.PendingChange
import com.discipline.control.core.Pin
import com.discipline.control.core.RemindStyle
import com.discipline.control.core.Store
import com.discipline.control.core.Utils
import com.discipline.control.core.dp
import com.discipline.control.service.GuardAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authed = intent.getBooleanExtra("authed", false)
        if (authed) {
            build()
        } else {
            Pin.gate(this) { build() }
        }
    }

    private fun build() {
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(40))
        }
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized) render()
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        container.removeAllViews()
        container.addView(Ui.header(this, "设置") { finish() })
        container.addView(Ui.pageTitle(this, "设置"))

        renderPermissions()
        renderRules()
        renderGlobal()
        renderPending()
        renderPin()
        renderAbout()
    }

    private fun renderPermissions() {
        container.addView(Ui.sectionTitle(this, "权限与开关（缺一不可）"))
        val card = Ui.card(this)

        val isDefault = isDefaultLauncher()
        card.addView(
            Ui.row(
                this,
                "① 默认桌面",
                if (isDefault) "已设为默认桌面，图标可以真正隐藏" else "未设置：桌面图标无法隐藏，拦截仍可工作",
                "去设置"
            ) { openHomeSettings() }
        )
        card.addView(Ui.divider(this))

        val acc = GuardAccessibilityService.isEnabled(this)
        card.addView(
            Ui.row(
                this,
                "② 无障碍服务",
                if (acc) "已开启，可以拦截启动并强制回桌面" else "未开启：无法拦截，限制形同虚设",
                "去开启"
            ) { openAccessibilitySettings() }
        )
        card.addView(Ui.divider(this))

        val notif = Notifier.canPost()
        card.addView(
            Ui.row(
                this,
                "③ 通知权限",
                if (notif) "已允许，可以收到提醒" else "未允许：到点提醒会收不到",
                "去允许"
            ) {
                if (Build.VERSION.SDK_INT >= 33) {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    }
                }
            }
        )
        card.addView(Ui.divider(this))

        val overlay = Overlay.canDraw(this)
        card.addView(
            Ui.row(
                this,
                "④ 悬浮窗（可选）",
                if (overlay) "已允许，可开启游戏内悬浮提醒" else "未允许：只能用通知提醒",
                "去允许"
            ) { openOverlaySettings() }
        )
        card.addView(Ui.divider(this))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
        card.addView(
            Ui.row(
                this,
                "⑤ 后台与自启动",
                if (ignoring) "已忽略电池优化；MIUI 里还需允许「自启动」并锁定后台"
                else "未忽略电池优化，MIUI 可能杀后台导致计时失效",
                "去设置"
            ) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }.onFailure {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            }
        )
        container.addView(card)
    }

    private fun renderRules() {
        container.addView(Ui.sectionTitle(this, "管控应用"))
        val rules = Store.rules()
        val card = Ui.card(this)
        if (rules.isEmpty()) {
            card.addView(
                Ui.row(this, "还没有管控任何应用", "先添加一个，再给他设置时段、次数和时长", null, null)
            )
        } else {
            rules.forEachIndexed { index, rule ->
                if (index > 0) card.addView(Ui.divider(this))
                card.addView(
                    Ui.row(
                        this,
                        rule.label,
                        rule.summary().replace("\n", " · ") +
                            if (rule.enabled) "" else "（已停用）",
                        "编辑"
                    ) {
                        startActivity(
                            Intent(this, AppRuleEditActivity::class.java).putExtra("pkg", rule.pkg)
                        )
                    }
                )
            }
        }
        card.addView(Ui.divider(this))
        card.addView(
            Ui.row(this, "＋ 添加管控应用", "从已安装的应用里选择", null) {
                startActivity(Intent(this, AppPickerActivity::class.java))
            }
        )
        container.addView(card)
    }

    private fun renderGlobal() {
        container.addView(Ui.sectionTitle(this, "全局设置"))
        val card = Ui.card(this)
        val cfg = Store.config()

        card.addView(
            Ui.row(
                this,
                "每天几点重置次数",
                "当前：${cfg.resetHour} 点（改动会按「放宽延迟」延后生效，防止拿来清空当天额度）",
                "修改"
            ) {
                val options = (0..6).map { "$it 点" to it }
                pickNumber("每天几点算新的一天", options, cfg.resetHour) { hour ->
                    if (hour == cfg.resetHour) {
                        render()
                        return@pickNumber
                    }
                    val delay = cfg.relaxDelayHours
                    if (delay > 0) {
                        val at = System.currentTimeMillis() + delay * 3600_000L
                        Store.addPending(
                            PendingChange(
                                UUID.randomUUID().toString(),
                                "config",
                                cfg.copy(resetHour = hour).toJson(),
                                at,
                                "每天重置时间 ${cfg.resetHour} 点 → $hour 点"
                            )
                        )
                        Toast.makeText(
                            this,
                            "这会改变当天额度的计算，已登记，${fmtTime(at)} 后生效",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Store.saveConfig(cfg.copy(resetHour = hour))
                    }
                    render()
                }
            }
        )
        card.addView(Ui.divider(this))

        card.addView(
            Ui.row(
                this,
                "放宽限制的修改需等待",
                "当前：${cfg.relaxDelayHours} 小时（0 = 立即生效，容易被自己绕过）",
                "修改"
            ) {
                val options = listOf(0, 6, 12, 24, 48, 72).map {
                    if (it == 0) "立即生效（不推荐）" to 0 else "$it 小时" to it
                }
                pickNumber("放宽类修改延迟生效", options, cfg.relaxDelayHours) { hours ->
                    applyRelaxDelay(hours)
                }
            }
        )
        card.addView(Ui.divider(this))

        card.addView(
            Ui.row(this, "到点提醒方式", RemindStyle.text(cfg.remindStyle), "修改") {
                pickRemindStyle()
            }
        )
        card.addView(Ui.divider(this))

        card.addView(
            Ui.row(this, "使用统计", "查看最近 7 天的打开次数与时长", "查看") {
                startActivity(Intent(this, StatsActivity::class.java))
            }
        )
        container.addView(card)
    }

    private fun renderPending() {
        val pending = Store.pending()
        if (pending.isEmpty()) return
        container.addView(Ui.sectionTitle(this, "待生效的修改（涉及放宽）"))
        val card = Ui.card(this)
        pending.forEachIndexed { index, change ->
            if (index > 0) card.addView(Ui.divider(this))
            card.addView(
                Ui.row(this, change.summary, "生效时间：${fmtTime(change.effectiveAt)}", "取消") {
                    Store.removePending(change.id)
                    Toast.makeText(this, "已取消该修改", Toast.LENGTH_SHORT).show()
                    render()
                }
            )
        }
        container.addView(card)
    }

    private fun renderPin() {
        container.addView(Ui.sectionTitle(this, "安全"))
        val card = Ui.card(this)
        card.addView(
            Ui.row(this, "修改 PIN", "进入设置页需要输入", "修改") {
                Pin.change(this)
            }
        )
        card.addView(Ui.divider(this))
        card.addView(
            Ui.row(this, "清除全部数据", "清空规则、用量和 PIN（谨慎）", "清除") {
                AlertDialog.Builder(this)
                    .setTitle("确定清除全部数据？")
                    .setMessage("所有规则、今天的用量记录和 PIN 都会被清空，无法恢复。")
                    .setPositiveButton("确定清除") { _, _ ->
                        Store.clearAll()
                        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        container.addView(card)
    }

    private fun renderAbout() {
        container.addView(Ui.sectionTitle(this, "关于"))
        val card = Ui.card(this)
        card.addView(
            Ui.row(
                this,
                "自律控制 v${BuildConfig.VERSION_NAME}",
                "纯本地运行，不联网、不上传任何数据。所有规则都存在这台平板上。",
                null,
                null
            )
        )
        container.addView(card)
    }

    // ---------------- 动作 ----------------

    private fun applyRelaxDelay(hours: Int) {
        val cfg = Store.config()
        val old = cfg.relaxDelayHours
        if (hours < old && old > 0) {
            val at = System.currentTimeMillis() + old * 3600_000L
            Store.addPending(
                PendingChange(
                    UUID.randomUUID().toString(),
                    "config",
                    cfg.copy(relaxDelayHours = hours).toJson(),
                    at,
                    "放宽延迟 ${old} 小时 → ${hours} 小时"
                )
            )
            Toast.makeText(this, "这会放宽限制，已登记，${fmtTime(at)} 后生效", Toast.LENGTH_LONG).show()
        } else {
            Store.saveConfig(cfg.copy(relaxDelayHours = hours))
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun pickRemindStyle() {
        val labels = arrayOf(
            "仅通知 + 震动（最安全，推荐）",
            "通知 + 游戏内悬浮横幅（有被 ACE 误判为辅助工具的风险）",
            "到点先退回桌面（最“砸脸”，完全不覆盖游戏画面）"
        )
        AlertDialog.Builder(this)
            .setTitle("到点提醒方式")
            .setSingleChoiceItems(labels, Store.config().remindStyle) { dialog, which ->
                Store.saveConfig(Store.config().copy(remindStyle = which))
                dialog.dismiss()
                render()
                if (which == RemindStyle.OVERLAY && !Overlay.canDraw(this)) {
                    AlertDialog.Builder(this)
                        .setTitle("还需要悬浮窗权限")
                        .setMessage("用这个提醒方式需要允许「显示在其他应用上层」。也可以改成第三种「到点先退回桌面」，效果更强烈且没有误判风险。")
                        .setPositiveButton("去允许") { _, _ -> openOverlaySettings() }
                        .setNegativeButton("再说", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickNumber(title: String, options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit) {
        val labels = options.map { it.first }.toTypedArray()
        val idx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, idx) { dialog, which ->
                dialog.dismiss()
                onPick(options[which].second)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = runCatching {
            packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() ?: return false
        return res.activityInfo?.packageName == packageName
    }

    private fun openHomeSettings() {
        val ok = runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }
}
