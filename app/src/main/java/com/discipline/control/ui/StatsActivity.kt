package com.discipline.control.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.Apps
import com.discipline.control.core.DayUsage
import com.discipline.control.core.PkgUsage
import com.discipline.control.core.Store
import com.discipline.control.core.Utils
import com.discipline.control.core.dp
import java.time.LocalDate

class StatsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(40))
        }
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(Ui.header(this, "统计") { finish() })
        container.addView(Ui.pageTitle(this, "最近 7 天"))
        container.addView(TextView(this).apply {
            text = "只统计被管控的应用在这台设备上的使用情况（按天 0 点重置）"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.sub))
            setPadding(0, dp(6), 0, dp(4))
        })

        val today = Store.logicalDate()
        var anyRecord = false
        for (i in 0 until 7) {
            val date = today.minusDays(i.toLong())
            val day = Store.dayOf(date)
            val rows: List<Map.Entry<String, PkgUsage>> =
                day?.apps?.entries?.filter { it.value.opens > 0 || it.value.sessionMs > 0 } ?: emptyList()
            val title = when (i) {
                0 -> "今天 · ${date.monthValue}/${date.dayOfMonth}"
                1 -> "昨天 · ${date.monthValue}/${date.dayOfMonth}"
                else -> dayNames[date.dayOfWeek.value - 1] + " · ${date.monthValue}/${date.dayOfMonth}"
            }
            val card = Ui.card(this)
            card.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text))
                setPadding(0, dp(8), 0, dp(8))
            })
            if (rows.isEmpty()) {
                card.addView(TextView(this).apply {
                    text = "没有记录"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.sub))
                    setPadding(0, 0, 0, dp(8))
                })
            } else {
                anyRecord = true
                rows.forEach { (pkg, usage) ->
                    card.addView(Ui.divider(this))
                    card.addView(
                        Ui.row(
                            this,
                            Apps.label(this, pkg),
                            "${usage.opens} 次 · ${Utils.fmtDuration(usage.sessionMs)}" +
                                if (usage.finished) " · 已用完" else "",
                            null,
                            null
                        )
                    )
                }
            }
            container.addView(card)
        }
        if (!anyRecord) {
            container.addView(TextView(this).apply {
                text = "还没有任何使用记录。打开被管控的应用后，这里会出现统计。"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.sub))
                setPadding(dp(6), dp(14), dp(6), 0)
            })
        }
    }
}
