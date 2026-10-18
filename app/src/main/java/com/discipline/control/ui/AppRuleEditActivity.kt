package com.discipline.control.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.AppRule
import com.discipline.control.core.Apps
import com.discipline.control.core.Engine
import com.discipline.control.core.PendingChange
import com.discipline.control.core.Store
import com.discipline.control.core.TimeWindow
import com.discipline.control.core.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AppRuleEditActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var pkg: String
    private lateinit var label: String
    private var enabled = true
    private val windows = ArrayList<TimeWindow>()
    private var kickAtWindowEnd = true
    private lateinit var etLimit: EditText
    private lateinit var etSession: EditText
    private lateinit var etGrace: EditText
    private lateinit var etGap: EditText
    private lateinit var timeBox: LinearLayout

    private val dayShort = arrayOf("一", "二", "三", "四", "五", "六", "日")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra("pkg") ?: run {
            finish()
            return
        }
        label = Apps.label(this, pkg)
        val existing = Store.rule(pkg)
        val base = existing ?: AppRule.default(pkg, label)
        enabled = base.enabled
        windows.addAll(base.windows)
        kickAtWindowEnd = base.kickAtWindowEnd
        build(base)
    }

    private fun build(base: AppRule) {
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(40))
        }
        scroll.addView(container)
        setContentView(scroll)

        container.addView(Ui.header(this, "编辑") { finish() })
        container.addView(Ui.pageTitle(this, label))
        container.addView(TextView(this).apply {
            text = pkg
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.sub))
            setPadding(0, dp(4), 0, dp(6))
        })

        container.addView(Ui.sectionTitle(this, "开关"))
        val swCard = Ui.card(this)
        swCard.addView(
            Ui.switchRow(this, "启用管控", "关闭后该应用完全不受限制", enabled) { on ->
                enabled = on
            }
        )
        container.addView(swCard)

        container.addView(Ui.sectionTitle(this, "开放时段（时段外图标会从桌面消失）"))
        val timeCard = Ui.card(this)
        timeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeCard.addView(timeBox)
        timeCard.addView(Ui.divider(this))
        timeCard.addView(
            Ui.row(this, "＋ 添加时段", "支持一天多个时段，可跨零点", null) { editWindow(null) }
        )
        container.addView(timeCard)
        renderWindows()

        container.addView(Ui.sectionTitle(this, "次数与时长"))
        val numCard = Ui.card(this)
        etLimit = numberRow(numCard, "每日最多打开次数", "0 = 不限；超过后当天直接隐身", base.dailyOpenLimit, 0)
        numCard.addView(Ui.divider(this))
        etSession = numberRow(numCard, "单次使用时长（分钟）", "0 = 不限；到点会先提醒", base.sessionLimitMin, 1)
        numCard.addView(Ui.divider(this))
        etGrace = numberRow(numCard, "提醒后宽限（分钟）", "提醒之后再过这么久强制退出", base.graceMin, 0)
        numCard.addView(Ui.divider(this))
        etGap = numberRow(numCard, "离开多久算新的一次（分钟）", "离开超过这个时间再回来才算新一次打开", base.countGapMin, 0)
        numCard.addView(Ui.divider(this))
        numCard.addView(
            Ui.switchRow(this, "时段结束时立即退出", "关掉则允许打到本次时长上限", kickAtWindowEnd) { on ->
                kickAtWindowEnd = on
            }
        )
        container.addView(numCard)

        container.addView(saveButton())
        container.addView(removeButton(base))
    }

    private fun renderWindows() {
        timeBox.removeAllViews()
        if (windows.isEmpty()) {
            timeBox.addView(TextView(this).apply {
                text = "没有时段 = 全天不可用"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.warn))
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }
        windows.forEachIndexed { index, w ->
            if (index > 0) timeBox.addView(Ui.divider(this))
            timeBox.addView(Ui.row(this, w.text(), "点击修改", "编辑") { editWindow(index) })
        }
    }

    private fun numberRow(parent: LinearLayout, title: String, sub: String, value: Int, min: Int): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.text))
        })
        col.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.sub))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (value < min) min.toString() else value.toString())
            setTextSize(18f)
            gravity = Gravity.CENTER
            minWidth = dp(90)
        }
        row.addView(et)
        parent.addView(row)
        return et
    }

    private fun saveButton(): TextView = TextView(this).apply {
        text = "保存"
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.card))
        background = ContextCompat.getDrawable(this@AppRuleEditActivity, R.drawable.bg_btn_primary)
        setPadding(0, dp(16), 0, dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(26) }
        setOnClickListener { save() }
    }

    private fun removeButton(base: AppRule): TextView = TextView(this).apply {
        text = "移除管控"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.warn))
        setPadding(0, dp(16), 0, dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        setOnClickListener {
            AlertDialog.Builder(this@AppRuleEditActivity)
                .setTitle("移除对「$label」的管控？")
                .setMessage(
                    "移除后它立刻回到桌面，不再受限制。\n" +
                        "如果设置了「放宽延迟」，移除也会被延后生效，期间仍按现有规则管控。"
                )
                .setPositiveButton("移除") { _, _ -> performRemove() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun performRemove() {
        val delay = Store.config().relaxDelayHours
        if (delay > 0) {
            val at = System.currentTimeMillis() + delay * 3600_000L
            Store.addPending(
                PendingChange(
                    UUID.randomUUID().toString(),
                    "remove",
                    org.json.JSONObject().put("pkg", pkg),
                    at,
                    "移除对「$label」的管控"
                )
            )
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            AlertDialog.Builder(this)
                .setTitle("移除已登记")
                .setMessage("这是放宽操作，将在 ${fmt.format(Date(at))} 之后生效。\n\n生效前它仍然受管控。")
                .setPositiveButton("知道了") { _, _ -> finish() }
                .show()
        } else {
            Store.removeRule(pkg)
            Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ---------------- 时段编辑 ----------------

    private fun removeWindow(index: Int) {
        if (index !in windows.indices) return
        windows.removeAt(index)
        renderWindows()
    }

    private fun editWindow(index: Int?) {
        val cur = if (index != null && index in windows.indices) windows[index]
        else TimeWindow(TimeWindow.EVERY_DAY, 20 * 60, 23 * 60)

        var days = cur.days
        var start = cur.startMin
        var end = cur.endMin

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }

        body.addView(TextView(this).apply {
            text = "生效的星期"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.sub))
        })

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val checks = ArrayList<CheckBox>()
        for (i in 0 until 7) {
            val cb = CheckBox(this).apply {
                text = dayShort[i]
                textSize = 12f
                isChecked = (days and (1 shl i)) != 0
                setPadding(0, 0, dp(2), 0)
                setOnCheckedChangeListener { _, _ -> days = collectDays(checks) }
            }
            checks.add(cb)
            quick.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(quick)

        val quickBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(10))
        }
        quickBtns.addView(quickButton("每天") {
            setDays(checks, TimeWindow.EVERY_DAY); days = TimeWindow.EVERY_DAY
        })
        quickBtns.addView(quickButton("工作日") {
            setDays(checks, 0x1F); days = 0x1F
        })
        quickBtns.addView(quickButton("周末") {
            setDays(checks, 0x60); days = 0x60
        })
        body.addView(quickBtns)

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        val btnStart = timeButton("")
        val btnEnd = timeButton("")

        fun refreshTimeButtons() {
            btnStart.text = "开始 " + com.discipline.control.core.Utils.fmtMin(start)
            btnEnd.text = "结束 " + com.discipline.control.core.Utils.fmtMin(end)
        }

        refreshTimeButtons()
        btnStart.setOnClickListener {
            showTimePicker(start) { v ->
                start = v
                refreshTimeButtons()
            }
        }
        btnEnd.setOnClickListener {
            showTimePicker(end) { v ->
                end = v
                refreshTimeButtons()
            }
        }
        timeRow.addView(btnStart)
        timeRow.addView(TextView(this).apply { text = "  →  "; textSize = 15f })
        timeRow.addView(btnEnd)
        body.addView(timeRow)

        AlertDialog.Builder(this)
            .setTitle(if (index == null) "添加时段" else "编辑时段")
            .setView(body)
            .setPositiveButton("确定") { _, _ ->
                val d = collectDays(checks)
                if (d == 0) {
                    Toast.makeText(this, "至少要选一天", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (start == end) {
                    Toast.makeText(this, "开始和结束时间不能一样", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val w = TimeWindow(d, start, end)
                if (index != null && index in windows.indices) windows[index] = w else windows.add(w)
                renderWindows()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除", { _, _ ->
                if (index != null) removeWindow(index)
            })
            .show()
    }

    private fun quickButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.accent))
        background = ContextCompat.getDrawable(this@AppRuleEditActivity, R.drawable.bg_chip)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun timeButton(text: String, onClick: () -> Unit = {}): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(ContextCompat.getColor(this@AppRuleEditActivity, R.color.card))
        background = ContextCompat.getDrawable(this@AppRuleEditActivity, R.drawable.bg_btn_primary)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnClickListener { onClick() }
    }

    private fun showTimePicker(initial: Int, onPick: (Int) -> Unit) {
        android.app.TimePickerDialog(
            this,
            { _, hour, minute -> onPick(hour * 60 + minute) },
            initial / 60,
            initial % 60,
            true
        ).show()
    }

    private fun setDays(checks: List<CheckBox>, mask: Int) {
        for (i in 0 until 7) {
            checks[i].isChecked = (mask and (1 shl i)) != 0
        }
    }

    private fun collectDays(checks: List<CheckBox>): Int {
        var d = 0
        for (i in 0 until 7) if (checks[i].isChecked) d = d or (1 shl i)
        return d
    }

    // ---------------- 保存 ----------------

    private fun save() {
        // 空/非数字不能静默当成 0（0 = 不限），否则会意外把限制取消
        fun read(et: EditText, name: String): Int? {
            val raw = et.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(this, "$name 不能为空（填 0 表示不限）", Toast.LENGTH_SHORT).show()
                return null
            }
            val v = raw.toIntOrNull()
            if (v == null) {
                Toast.makeText(this, "$name 只能填数字", Toast.LENGTH_SHORT).show()
                return null
            }
            return v
        }

        val limit = read(etLimit, "每日次数") ?: return
        val session = read(etSession, "单次时长") ?: return
        val grace = read(etGrace, "提醒后宽限") ?: return
        val gap = read(etGap, "计数间隔") ?: return

        val newRule = AppRule(
            pkg = pkg,
            label = label,
            enabled = enabled,
            windows = ArrayList(windows),
            dailyOpenLimit = limit.coerceIn(0, 99),
            sessionLimitMin = session.coerceIn(0, 600),
            graceMin = grace.coerceIn(0, 180),
            countGapMin = gap.coerceIn(0, 120),
            kickAtWindowEnd = kickAtWindowEnd
        )

        val old = Store.rule(pkg)
        val delay = Store.config().relaxDelayHours
        if (old != null && delay > 0 && Engine.isRelaxation(old, newRule)) {
            val at = System.currentTimeMillis() + delay * 3600_000L
            Store.addPending(
                PendingChange(
                    UUID.randomUUID().toString(),
                    "rule",
                    newRule.toJson(),
                    at,
                    diffSummary(old, newRule),
                    base = old.toJson().toString()
                )
            )
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            AlertDialog.Builder(this)
                .setTitle("这是一次「放宽」")
                .setMessage(
                    "为避免一时冲动改掉限制，本次修改将在 ${fmt.format(Date(at))} 生效。\n\n" +
                        "想立刻收窄限制（比如减次数、缩时段）是不需要等待的。"
                )
                .setPositiveButton("知道了") { _, _ -> finish() }
                .show()
        } else {
            Store.upsertRule(newRule)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun diffSummary(old: AppRule, new: AppRule): String {
        val parts = ArrayList<String>()
        if (old.enabled != new.enabled) parts.add(if (new.enabled) "启用管控" else "停用管控")
        if (old.windows != new.windows) {
            parts.add("时段 ${old.windowText()} → ${new.windowText()}")
        }
        if (old.dailyOpenLimit != new.dailyOpenLimit) {
            parts.add("每日次数 ${old.dailyOpenLimit} → ${new.dailyOpenLimit}")
        }
        if (old.sessionLimitMin != new.sessionLimitMin) {
            parts.add("单次时长 ${old.sessionLimitMin} → ${new.sessionLimitMin} 分钟")
        }
        if (old.graceMin != new.graceMin) {
            parts.add("宽限 ${old.graceMin} → ${new.graceMin} 分钟")
        }
        if (old.countGapMin != new.countGapMin) {
            parts.add("计数间隔 ${old.countGapMin} → ${new.countGapMin} 分钟")
        }
        if (old.kickAtWindowEnd != new.kickAtWindowEnd) {
            parts.add(if (new.kickAtWindowEnd) "时段结束立即退出" else "时段结束不退出")
        }
        return new.label + "：" + if (parts.isEmpty()) "规则更新" else parts.joinToString("，")
    }
}
