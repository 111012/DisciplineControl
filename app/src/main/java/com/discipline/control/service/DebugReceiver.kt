package com.discipline.control.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.discipline.control.BuildConfig
import com.discipline.control.core.AppRule
import com.discipline.control.core.Apps
import com.discipline.control.core.PendingChange
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
                // 支持直接给"当天第几分钟"（方便测时段开始/结束的边界）
                val startMin = if (intent.hasExtra("startMin")) {
                    intent.getIntExtra("startMin", 0)
                } else {
                    startHour * 60
                }
                val endMin = if (intent.hasExtra("endMin")) {
                    intent.getIntExtra("endMin", 1440)
                } else if (endHour >= 24) {
                    24 * 60
                } else {
                    endHour * 60
                }
                val window = TimeWindow(
                    TimeWindow.EVERY_DAY,
                    startMin.coerceIn(0, 24 * 60),
                    endMin.coerceIn(0, 24 * 60)
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

            // 直接设定今日状态，省得为了测次数限制真的去开 3 次游戏
            "set-usage" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val day = Store.day()
                val u = day.get(pkg)
                if (intent.hasExtra("opens")) u.opens = intent.getIntExtra("opens", 0)
                if (intent.hasExtra("sessionMin")) {
                    u.sessionMs = intent.getIntExtra("sessionMin", 0) * 60_000L
                }
                if (intent.hasExtra("finished")) {
                    u.finished = intent.getBooleanExtra("finished", false)
                }
                if (intent.hasExtra("warned")) {
                    u.warned = intent.getBooleanExtra("warned", false)
                }
                u.lastExitAt = 0L
                Store.saveDay(day)
                Log.i(TAG, "usage updated: ${u.toJson()}")
            }

            "remove" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                Store.removeRule(pkg)
                Log.i(TAG, "rule removed: $pkg")
            }

            "config" -> {
                var cfg = Store.config()
                if (intent.hasExtra("remindStyle")) {
                    cfg = cfg.copy(remindStyle = intent.getIntExtra("remindStyle", 0))
                }
                if (intent.hasExtra("relaxDelayHours")) {
                    cfg = cfg.copy(relaxDelayHours = intent.getIntExtra("relaxDelayHours", 24))
                }
                if (intent.hasExtra("resetHour")) {
                    cfg = cfg.copy(resetHour = intent.getIntExtra("resetHour", 0))
                }
                Store.saveConfig(cfg)
                Log.i(TAG, "config saved: ${cfg.toJson()}")
            }

            // 造一条 20 秒后才生效的"放宽"修改，用来验证延迟生效链路
            "pending-demo" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val rule = Store.rule(pkg) ?: return
                val newRule = rule.copy(dailyOpenLimit = rule.dailyOpenLimit + 5)
                val at = System.currentTimeMillis() + 20_000L
                Store.addPending(
                    PendingChange(
                        java.util.UUID.randomUUID().toString(),
                        "rule",
                        newRule.toJson(),
                        at,
                        "${rule.label}：每日次数 ${rule.dailyOpenLimit} → ${newRule.dailyOpenLimit}（调试）"
                    )
                )
                Log.i(TAG, "pending added, effectiveAt=$at")
            }

            "clear" -> {
                Store.clearAll()
                Log.i(TAG, "store cleared")
            }

            // 壁纸调试：把应用私有目录里的图片设为壁纸（配合 adb 推图，免去手点相册）
            "wallpaper" -> {
                val name = intent.getStringExtra("file") ?: "test_wallpaper.jpg"
                val f = java.io.File(context.filesDir, name)
                if (f.exists() && f.length() > 0) {
                    Store.saveLayout(Store.layout().copy(wallpaper = f.absolutePath))
                    Log.i(TAG, "wallpaper set: ${f.absolutePath} (${f.length()} bytes)")
                } else {
                    Log.w(TAG, "wallpaper file not found: ${f.absolutePath}")
                }
            }

            "clear-wallpaper" -> {
                Store.saveLayout(Store.layout().copy(wallpaper = ""))
                Log.i(TAG, "wallpaper cleared")
            }

            // 重置桌面布局（保留壁纸），用于把拖拽测试弄乱的顺序恢复成默认排序
            "reset-layout" -> {
                val wallpaper = Store.layout().wallpaper
                Store.saveLayout(com.discipline.control.core.HomeLayout(items = emptyList(), wallpaper = wallpaper))
                Log.i(TAG, "layout reset (wallpaper kept=$wallpaper)")
            }

            "layout" -> {
                Log.i(TAG, "layout=${Store.layout().toJson()}")
            }

            "start-service" -> GuardService.startSafely(context)
        }
    }

    private companion object {
        const val TAG = "DC/Debug"
    }
}
