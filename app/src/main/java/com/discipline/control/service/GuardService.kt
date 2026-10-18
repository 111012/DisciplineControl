package com.discipline.control.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.discipline.control.core.DenyReason
import com.discipline.control.core.Engine
import com.discipline.control.core.Notifier
import com.discipline.control.core.Overlay
import com.discipline.control.core.PkgUsage
import com.discipline.control.core.Store
import com.discipline.control.core.AppRule
import java.time.LocalDateTime

/** 桌面上用于提示"刚刚被拦截了"的事件 */
data class BlockEvent(val pkg: String, val label: String, val text: String, val at: Long)

/**
 * 守护服务：前台应用会话计时、到点提醒、超限强制退出、拦截。
 * 全部状态都落盘在本地，无网络。
 */
class GuardService : Service() {

    private class Session(val pkg: String) {
        var baseMs: Long = 0L
        var resumedAt: Long = 0L
        var warned: Boolean = false
        fun elapsed(now: Long): Long = baseMs + if (resumedAt > 0) now - resumedAt else 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val lastBlockAt = HashMap<String, Long>()
    private var lastPendingCheck = 0L
    private var lastOngoingUpdate = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseSession(System.currentTimeMillis())
                Intent.ACTION_SCREEN_ON -> refreshOngoing()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            runCatching { onTick() }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.init(this)
        Store.init(this)
        startForeground(NOTIF_ID, Notifier.ongoing("守护中", "正在加载规则…"))
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            )
        }
        runCatching { Store.applyDuePending() }
        handler.post(ticker)
        scheduleKeepAlive(this)
        refreshOngoing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshOngoing()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    // ---------------- 前台应用变化 ----------------

    fun onForegroundPkg(pkg: String) {
        val now = System.currentTimeMillis()
        val cur = session
        if (cur != null && cur.pkg != pkg) pauseSession(now)

        if (pkg == this.packageName) {
            refreshOngoing()
            return
        }
        val rule = Store.rule(pkg) ?: run {
            refreshOngoing()
            return
        }
        if (!rule.enabled) {
            refreshOngoing()
            return
        }

        val existing = session
        if (existing != null && existing.pkg == pkg) {
            if (existing.resumedAt <= 0L) existing.resumedAt = now
            return
        }

        val day = Store.day()
        val usage = day.peek(pkg)
        val resuming = isResuming(rule, usage, now)
        val deny = Engine.launchDeny(rule, usage, LocalDateTime.now(), sessionActive = resuming)
        if (deny != null) {
            block(rule, deny, now)
            return
        }
        startOrResumeSession(rule, resuming, now)
    }

    private fun isResuming(rule: AppRule, usage: PkgUsage?, now: Long): Boolean {
        if (usage == null) return false
        if (usage.finished) return false
        if (usage.sessionMs <= 0L) return false
        if (usage.lastExitAt <= 0L) return false
        val gap = rule.countGapMin.coerceAtLeast(0) * 60_000L
        return now - usage.lastExitAt <= gap
    }

    private fun startOrResumeSession(rule: AppRule, resuming: Boolean, now: Long) {
        val day = Store.day()
        val u = day.get(rule.pkg)
        val s = Session(rule.pkg)
        if (resuming) {
            s.baseMs = u.sessionMs
            s.warned = u.warned
        } else {
            u.opens += 1
            u.sessionMs = 0L
            u.warned = false
            u.lastExitAt = 0L
            Store.saveDay(day)
            s.baseMs = 0L
            s.warned = false
        }
        s.resumedAt = now
        session = s
        Overlay.hide()
        refreshOngoing()
    }

    private fun pauseSession(now: Long) {
        val s = session ?: return
        session = null
        val day = Store.day()
        val u = day.get(s.pkg)
        u.sessionMs = s.elapsed(now)
        u.warned = s.warned
        u.lastExitAt = now
        Store.saveDay(day)
        Overlay.hide()
        refreshOngoing()
    }

    // ---------------- 每秒检查 ----------------

    private fun onTick() {
        val now = System.currentTimeMillis()
        val s = session
        if (s != null) {
            val rule = Store.rule(s.pkg)
            if (rule == null || !rule.enabled) {
                session = null
            } else {
                val nowDt = LocalDateTime.now()
                if (rule.kickAtWindowEnd && !rule.inWindow(nowDt)) {
                    finishForDay(rule, DenyReason.WINDOW_ENDED, now)
                } else if (rule.sessionLimitMin > 0) {
                    val limitMs = rule.sessionLimitMin * 60_000L
                    val graceMs = rule.graceMin.coerceAtLeast(0) * 60_000L
                    val elapsed = s.elapsed(now)
                    if (elapsed >= limitMs && !s.warned) {
                        s.warned = true
                        persistWarned(s, now)
                        warn(rule, elapsed, limitMs, graceMs)
                    }
                    if (elapsed >= limitMs + graceMs) {
                        finishForDay(rule, DenyReason.DAY_FINISHED, now)
                    }
                }
            }
        }

        if (now - lastPendingCheck > 30_000L) {
            lastPendingCheck = now
            val applied = Store.applyDuePending(now)
            if (applied.isNotEmpty()) {
                Notifier.notifyAlert(2002, "设置已生效", applied.joinToString("；"))
                refreshOngoing()
            }
        }
        if (now - lastOngoingUpdate > 60_000L) {
            lastOngoingUpdate = now
            refreshOngoing()
        }
    }

    private fun persistWarned(s: Session, now: Long) {
        val day = Store.day()
        val u = day.get(s.pkg)
        u.sessionMs = s.elapsed(now)
        u.warned = true
        Store.saveDay(day)
    }

    private fun warn(rule: AppRule, elapsed: Long, limitMs: Long, graceMs: Long) {
        val remainMin = ((limitMs + graceMs - elapsed) / 60_000L).coerceAtLeast(0)
        val text = "已使用 ${elapsed / 60_000} 分钟，$remainMin 分钟后将强制退出"
        Notifier.notifyAlert(2001, rule.label + " 提醒", text)
        vibrate()
        if (Store.config().warnOverlay) {
            Overlay.showWarn(this, rule.label + "：" + text)
        }
    }

    private fun vibrate() {
        runCatching {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 300, 150, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    private fun finishForDay(rule: AppRule, reason: DenyReason, now: Long) {
        val s = session
        val day = Store.day()
        val u = day.get(rule.pkg)
        if (s != null && s.pkg == rule.pkg) {
            u.sessionMs = s.elapsed(now)
            u.warned = s.warned
        }
        u.finished = true
        u.lastExitAt = now
        Store.saveDay(day)
        session = null
        Overlay.hide()
        block(rule, reason, now, forced = true)
        refreshOngoing()
    }

    private fun block(rule: AppRule, reason: DenyReason, now: Long, forced: Boolean = false) {
        val last = lastBlockAt[rule.pkg] ?: 0L
        if (now - last < 1500L) return
        lastBlockAt[rule.pkg] = now

        val text = Engine.denyText(reason, rule, LocalDateTime.now())
        lastBlock = BlockEvent(rule.pkg, rule.label, text, now)
        val prefix = if (forced) "已强制退出" else "已被拦截"
        Notifier.notifyAlert(2000 + (rule.pkg.hashCode() and 0xFF), rule.label + " " + prefix, text)
        GuardAccessibilityService.goHome()
    }

    private fun refreshOngoing() {
        val s = session
        val title = "自律控制"
        val text: String = if (s != null) {
            val rule = Store.rule(s.pkg)
            if (rule != null && rule.sessionLimitMin > 0) {
                val elapsed = s.elapsed(System.currentTimeMillis())
                val limitMs = rule.sessionLimitMin * 60_000L
                val left = ((limitMs - elapsed) / 60_000L).coerceAtLeast(0L)
                "${rule.label} 使用中 · 剩余 $left 分钟"
            } else {
                (rule?.label ?: s.pkg) + " 使用中"
            }
        } else {
            val n = Store.rules().count { it.enabled }
            "守护中 · 已管控 $n 个应用"
        }
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, Notifier.ongoing(title, text))
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val KEEPALIVE_ACTION = "com.discipline.control.KEEPALIVE"

        @Volatile
        private var instance: GuardService? = null

        @Volatile
        var lastBlock: BlockEvent? = null

        fun startSafely(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
            }
        }

        fun isRunning(): Boolean = instance != null

        fun notifyForeground(pkg: String) {
            instance?.onForegroundPkg(pkg)
        }

        /** 用 AlarmManager 每 15 分钟把自己拉一次，缓解 MIUI 杀后台 */
        fun scheduleKeepAlive(ctx: Context) {
            runCatching {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(ctx, BootReceiver::class.java).setAction(KEEPALIVE_ACTION)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = PendingIntent.getBroadcast(ctx, 9001, intent, flags)
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 15 * 60 * 1000L,
                    15 * 60 * 1000L,
                    pi
                )
            }
        }
    }
}
