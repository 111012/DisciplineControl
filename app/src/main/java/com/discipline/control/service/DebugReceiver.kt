package com.discipline.control.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.discipline.control.BuildConfig
import com.discipline.control.core.AppRule
import com.discipline.control.core.Apps
import com.discipline.control.core.Store
import com.discipline.control.core.TimeWindow

/**
 * 仅 debug 构建有效的调试入口，方便用 adb 做自动化验证（release 构建里直接 return）。
 *
 *   adb shell am broadcast -a com.discipline.control.DEBUG -e cmd status
 *   adb shell am broadcast -a com.discipline.control.DEBUG -e cmd reset-day
 *   adb shell am broadcast -a com.discipline.control.DEBUG -e cmd rule \
 *       --es pkg com.tencent.tmgp.sgame --ei opens 3 --ei sessionMin 2 \
 *       --ei graceMin 1 --ei gapMin 0 --ei startHour 0 --ei endHour 24
 *   adb shell am broadcast -a com.discipline.control.DEBUG -e cmd remove --es pkg <pkg>
 *   adb shell am broadcast -a com.discipline.control.DEBUG -e cmd clear
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val cmd = intent?.getStringExtra("cmd") ?: return
        Log.i(TAG, "cmd=$cmd")
        when (cmd) {
            "status" -> {
                Log.i(TAG, "rules=${Store.rules().joinToString(" | ") { it.toJson().toString() }}")
                Log.i(TAG, "day=${Store.day().toJson()}")
                Log.i(TAG, "pending=${Store.pending().joinToString(" | ") { it.summary + "@" + it.effectiveAt }}")
                Log.i(TAG, "config=${Store.config().toJson()}")
            }

            "reset-day" -> {
                val day = Store.day()
                day.apps.clear()
                Store.saveDay(day)
                Log.i(TAG, "day cleared: ${day.date}")
            }

            "rule" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val base = Store.rule(pkg) ?: AppRule.default(pkg, Apps.label(context, pkg))
                val startHour = intent.getIntExtra("startHour", 0)
                val endHour = intent.getIntExtra("endHour", 24)
                val window = TimeWindow(
                    TimeWindow.EVERY_DAY,
                    (startHour.coerceIn(0, 24)) * 60,
                    if (endHour >= 24) 24 * 60 else endHour.coerceAtLeast(0) * 60
                )
                val rule = base.copy(
                    windows = listOf(window),
                    enabled = intent.getBooleanExtra("enabled", true),
                    dailyOpenLimit = intent.getIntExtra("opens", base.dailyOpenLimit),
                    sessionLimitMin = intent.getIntExtra("sessionMin", base.sessionLimitMin),
                    graceMin = intent.getIntExtra("graceMin", base.graceMin),
                    countGapMin = intent.getIntExtra("gapMin", base.countGapMin)
                )
                Store.upsertRule(rule)
                Log.i(TAG, "rule saved: ${rule.toJson()}")
            }

            "remove" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                Store.removeRule(pkg)
                Log.i(TAG, "rule removed: $pkg")
            }

            "clear" -> {
                Store.clearAll()
                Log.i(TAG, "store cleared")
            }

            "start-service" -> GuardService.startSafely(context)
        }
    }

    private companion object {
        const val TAG = "DC/Debug"
    }
}
