package com.discipline.control.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.AppEntry
import com.discipline.control.core.AppRule
import com.discipline.control.core.Apps
import com.discipline.control.core.DayUsage
import com.discipline.control.core.Engine
import com.discipline.control.core.Notifier
import com.discipline.control.core.Overlay
import com.discipline.control.core.Pin
import com.discipline.control.core.Store
import com.discipline.control.core.dp
import com.discipline.control.service.GuardAccessibilityService
import com.discipline.control.service.GuardService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HomeActivity : AppCompatActivity() {

    private lateinit var grid: GridView
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvFooter: TextView
    private lateinit var chipBox: LinearLayout
    private lateinit var adapter: AppGridAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var lastMinute = -1

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            val minute = LocalDateTime.now().minute
            if (minute != lastMinute) {
                lastMinute = minute
                refresh()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        grid = findViewById(R.id.grid)
        tvClock = findViewById(R.id.tvClock)
        tvDate = findViewById(R.id.tvDate)
        tvStatus = findViewById(R.id.tvStatus)
        tvFooter = findViewById(R.id.tvFooter)
        chipBox = findViewById(R.id.chipBox)
        val btnSettings: ImageButton = findViewById(R.id.btnSettings)

        adapter = AppGridAdapter(this)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            launchApp(adapter.getItem(position).pkg)
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            showAppInfo(adapter.getItem(position).pkg)
            true
        }

        btnSettings.setOnClickListener {
            Pin.gate(this) { openSettings() }
        }
        btnSettings.setOnLongClickListener {
            openSystemSettings()
            true
        }

        requestNotifPermissionIfNeeded()
        updateClock()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun updateClock() {
        val now = LocalDateTime.now()
        tvClock.text = now.format(clockFmt)
        tvDate.text = "${now.monthValue} 月 ${now.dayOfMonth} 日 ${dayNames[now.dayOfWeek.value - 1]}"
    }

    // ---------------- 桌面刷新 ----------------

    private fun refresh() {
        val now = LocalDateTime.now()
        val rules = Store.rules()
        val day = Store.day()
        val all = Apps.launchable(this)
        val visible = ArrayList<AppEntry>(all.size)
        for (e in all) {
            val rule = rules.firstOrNull { it.pkg == e.pkg && it.enabled }
            if (rule == null) {
                visible.add(e)
            } else if (Engine.visibleInLauncher(rule, day.peek(e.pkg), now)) {
                visible.add(e)
            }
        }
        adapter.setData(visible)
        rebuildStatus(rules, day, now)
        rebuildChips()
        rebuildFooter()
    }

    private fun rebuildStatus(rules: List<AppRule>, day: DayUsage, now: LocalDateTime) {
        val enabledRules = rules.filter { it.enabled }
        if (enabledRules.isEmpty()) {
            tvStatus.text = "还没有管控任何应用。点右上角 → 添加管控应用"
            return
        }
        val sb = StringBuilder()
        enabledRules.forEach { r ->
            if (sb.isNotEmpty()) sb.append("\n")
            val u = day.peek(r.pkg)
            val state = when {
                u?.finished == true -> "今日已用完，明天再来"
                r.dailyOpenLimit > 0 && (u?.opens ?: 0) >= r.dailyOpenLimit -> "今日次数已用完"
                !r.inWindow(now) -> "已隐身 · ${Engine.nextOpenText(r, now)} 开放"
                r.dailyOpenLimit > 0 -> "开放中 · 今日已用 ${u?.opens ?: 0}/${r.dailyOpenLimit} 次"
                else -> "开放中 · 次数不限"
            }
            sb.append(r.label).append("：").append(state)
        }
        tvStatus.text = sb.toString()
    }

    private fun rebuildChips() {
        chipBox.removeAllViews()
        val needDefault = !isDefaultLauncher()
        val accEnabled = GuardAccessibilityService.isEnabled(this)
        if (needDefault) {
            addChip("① 设为默认桌面", true) { openHomeSettings() }
        }
        if (!accEnabled) {
            addChip("② 开启无障碍拦截", true) { openAccessibilitySettings() }
        }
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost()) {
            addChip("③ 允许通知", true) { requestNotifPermissionIfNeeded() }
        }
        if (Store.config().warnOverlay && !Overlay.canDraw(this)) {
            addChip("悬浮窗权限", true) { openOverlaySettings() }
        }
        if (!needDefault && accEnabled && Store.rules().isEmpty()) {
            addChip("＋ 添加管控应用", false) { openSettings() }
        }
        if (!needDefault && accEnabled && Store.rules().isNotEmpty()) {
            addChip("守护运行中", false) {}
        }
    }

    private fun rebuildFooter() {
        val lb = GuardService.lastBlock
        if (lb != null && System.currentTimeMillis() - lb.at < 90_000L) {
            tvFooter.text = "${lb.label} 已被拦下：${lb.text}"
            tvFooter.setTextColor(ContextCompat.getColor(this, R.color.warn))
        } else {
            tvFooter.text = "长按图标查看应用信息 · 长按右上角进入系统设置"
            tvFooter.setTextColor(ContextCompat.getColor(this, R.color.sub))
        }
    }

    private fun addChip(text: String, warn: Boolean, onClick: () -> Unit) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(ContextCompat.getColor(this, if (warn) R.color.warn else R.color.accent))
        tv.background = ContextCompat.getDrawable(this, if (warn) R.drawable.bg_chip_warn else R.drawable.bg_chip)
        val h = dp(12)
        val v = dp(6)
        tv.setPadding(h, v, h, v)
        tv.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = dp(8)
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        chipBox.addView(tv)
    }

    // ---------------- 动作 ----------------

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, "这个应用没有可启动的入口", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "启动失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun showAppInfo(pkg: String) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("authed", true))
    }

    private fun openSystemSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openHomeSettings() {
        val ok = runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            Toast.makeText(this, "请在「默认应用 / 桌面」里选择「自律控制」", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "请在列表里选择「自律控制」并设为默认", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Toast.makeText(this, "找到「自律控制 · 应用守护」并开启", Toast.LENGTH_LONG).show()
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        }
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = runCatching { packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrNull() ?: return false
        return res.activityInfo?.packageName == packageName
    }
}
