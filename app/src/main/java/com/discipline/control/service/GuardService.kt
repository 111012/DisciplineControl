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
import android.util.Log
import androidx.core.content.ContextCompat
import com.discipline.control.core.DenyReason
import com.discipline.control.core.Engine
import com.discipline.control.core.Notifier
import com.discipline.control.core.Overlay
import com.discipline.control.core.PkgUsage
import com.discipline.control.core.RemindStyle
import com.discipline.control.core.Store
import com.discipline.control.core.AppRule
import com.discipline.control.ui.RepairActivity
import java.time.LocalDateTime

/** 桌面上用于提示"刚刚被拦截了"的事件 */
data class BlockEvent(val pkg: String, val label: String, val text: String, val at: Long)

/** 到点提醒事件：桌面用它显示"还剩 N 分钟" */
data class WarnEvent(val pkg: String, val label: String, val remainMin: Long, val at: Long)

/**
 * 守护服务：前台应用会话计时、到点提醒、超限强制退出、拦截。
 * 全部状态都落盘在本地，无网络。
 */
class GuardService : Service() {

    private class Session(val pkg: String, val date: String) {
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
    private var lastReconciledPkg: String? = null
    private var tickCount = 0
    private var lastA11yCheck = 0L
    private var lastRepairAlertAt = 0L

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
        Log.i(TAG, "service onCreate")
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
        Log.i(TAG, "service onDestroy")
        // 进程被正常停掉时也要把当前会话落盘，否则重进会重新计时
        runCatching { persistSession(System.currentTimeMillis()) }
        runCatching { Overlay.hide() }
        instance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    // ---------------- 前台应用变化 ----------------

    fun onForegroundPkg(pkg: String) {
        if (pkg == lastReconciledPkg) return
        lastReconciledPkg = pkg
        if (Engine.isTransientPackage(pkg)) {
            // 通知栏 / 输入法 / 权限弹窗：不是真的离开游戏，继续计时
            Log.d(TAG, "fg=$pkg (临时窗口，忽略)")
            return
        }
        val now = System.currentTimeMillis()
        val cur = session
        if (cur != null && cur.pkg != pkg) pauseSession(now)

        if (pkg == this.packageName) {
            refreshOngoing()
            return
        }
        val rule = Store.rule(pkg) ?: run {
            Log.d(TAG, "fg=$pkg (未管控)")
            refreshOngoing()
            return
        }
        if (!rule.enabled) {
            Log.d(TAG, "fg=$pkg (管控已停用)")
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
        val resuming = Engine.isResumingSession(rule, usage, now)
        val deny = Engine.launchDeny(rule, usage, LocalDateTime.now(), sessionActive = resuming)
        Log.i(
            TAG,
            "fg=${rule.pkg} deny=$deny resuming=$resuming opens=${usage?.opens ?: 0} " +
                "finished=${usage?.finished ?: false} sessionMs=${usage?.sessionMs ?: 0L}"
        )
        if (deny != null) {
            block(rule, deny, now)
            return
        }
        startOrResumeSession(rule, resuming, now)
    }

    private fun startOrResumeSession(rule: AppRule, resuming: Boolean, now: Long) {
        val dateKey = Store.logicalDate().toString()
        val day = Store.dayFor(dateKey)
        val u = day.get(rule.pkg)
        val s = Session(rule.pkg, dateKey)
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
        Log.i(TAG, "session start pkg=${rule.pkg} resuming=$resuming opens=${u.opens} baseMs=${s.baseMs}")
        Overlay.hide()
        if (!resuming) lastWarn = null
        refreshOngoing()
    }

    private fun pauseSession(now: Long) {
        val s = session ?: return
        session = null
        // 关键：清掉前台去重状态，否则"关屏->亮屏"或"被弹回后立即切回"这类没有新窗口事件的情况会永远不再恢复会话
        lastReconciledPkg = null
        // 会话可能跨过零点：时长要写回会话开始的那一天
        val day = Store.dayFor(s.date)
        val u = day.get(s.pkg)
        u.sessionMs = s.elapsed(now)
        u.warned = s.warned
        u.lastExitAt = now
        Store.saveDay(day)
        Log.i(TAG, "session pause pkg=${s.pkg} elapsedMin=${u.sessionMs / 60_000L} warned=${s.warned}")
        Overlay.hide()
        refreshOngoing()
    }

    /** 不触发通知/UI 的落盘（onDestroy 用） */
    private fun persistSession(now: Long) {
        val s = session ?: return
        val day = Store.dayFor(s.date)
        val u = day.get(s.pkg)
        u.sessionMs = s.elapsed(now)
        u.warned = s.warned
        u.lastExitAt = now
        Store.saveDay(day)
        session = null
        lastReconciledPkg = null
    }

    // ---------------- 每秒检查 ----------------

    private fun onTick() {
        val now = System.currentTimeMillis()
        tickCount++
        // 每 2 秒主动核对一次前台窗口，兜住"系统悬浮窗抢焦点后游戏回前台不发事件"的情况
        if (tickCount % 2 == 0) {
            val fg = GuardAccessibilityService.activePackage()
            if (fg != null && fg != lastReconciledPkg) {
                Log.d(TAG, "poll -> $fg")
                onForegroundPkg(fg)
            }
        }
        val s = session
        if (s != null) {
            // 跨天：把这一整段时长结算回原日期，然后结束会话，绝不能让昨天的时长压死今天
            if (s.date != Store.logicalDate().toString()) {
                Log.i(TAG, "day rolled over -> pause session pkg=${s.pkg} (date=${s.date})")
                pauseSession(now)
            } else {
                val rule = Store.rule(s.pkg)
                if (rule == null || !rule.enabled) {
                    session = null
                    lastReconciledPkg = null
                } else {
                    val nowDt = LocalDateTime.now()
                    if (rule.kickAtWindowEnd && !rule.inWindow(nowDt)) {
                        endSessionAtWindowEnd(rule, now)
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
        }

        if (now - lastPendingCheck > 30_000L) {
            lastPendingCheck = now
            val applied = Store.applyDuePending(now)
            if (applied.isNotEmpty()) {
                Notifier.notifyAlert(2002, "设置已生效", applied.joinToString("；"))
                refreshOngoing()
            }
        }

        // 无障碍被系统/安全中心关掉时守护会彻底失效：主动提醒一次（5 分钟一条，避免刷屏）
        if (now - lastA11yCheck > 15_000L) {
            lastA11yCheck = now
            val ok = GuardAccessibilityService.isEnabled(this)
            if (!ok) {
                Log.w(TAG, "accessibility disabled")
                if (now - lastRepairAlertAt > 5 * 60_000L) {
                    lastRepairAlertAt = now
                    val intent = Intent(this, RepairActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    Notifier.notifyAlert(
                        2003,
                        "拦截已失效",
                        "无障碍服务被系统关掉了，点这里一键修复",
                        intent
                    )
                }
            }
            refreshOngoing()
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
        Log.i(
            TAG,
            "warn pkg=${rule.pkg} elapsedMin=${elapsed / 60_000L} remainMin=$remainMin " +
                "style=${Store.config().remindStyle}"
        )
        val text = "已使用 ${elapsed / 60_000} 分钟，$remainMin 分钟后将强制退出"
        Notifier.notifyAlert(2001, rule.label + " 提醒", text)
        vibrate()
        when (Store.config().remindStyle) {
            RemindStyle.OVERLAY -> Overlay.showWarn(this, rule.label + "：" + text)
            RemindStyle.HOME -> {
                // 不覆盖游戏画面，直接退回桌面；桌面上会显示还剩多少分钟，点回去可以继续用完宽限
                lastWarn = WarnEvent(rule.pkg, rule.label, remainMin, System.currentTimeMillis())
                homeWithRetry()
            }
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

    /**
     * 时段结束：只结束本次会话，**不**把今天判死。
     * 否则配置了"一天多个时段"（例如 12–13 点 + 20–23 点）时，第一段结束就会把整天的额度锁死。
     */
    private fun endSessionAtWindowEnd(rule: AppRule, now: Long) {
        Log.i(TAG, "window end -> end session pkg=${rule.pkg}")
        pauseSession(now)
        lastWarn = null
        val text = "开放时段已结束，" + Engine.nextOpenText(rule, LocalDateTime.now()) + "再来"
        lastBlock = BlockEvent(rule.pkg, rule.label, text, now)
        Notifier.notifyAlert(2000 + (rule.pkg.hashCode() and 0xFF), rule.label + " 时段结束", text)
        homeWithRetry()
    }

    private fun finishForDay(rule: AppRule, reason: DenyReason, now: Long) {
        Log.i(TAG, "finish pkg=${rule.pkg} reason=$reason")
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
        lastWarn = null
        block(rule, reason, now, forced = true)
        refreshOngoing()
    }

    private fun block(rule: AppRule, reason: DenyReason, now: Long, forced: Boolean = false) {
        val last = lastBlockAt[rule.pkg] ?: 0L
        val throttled = now - last < 1500L
        lastBlockAt[rule.pkg] = now
        // 拦截动作必须无条件执行：1.5 秒去重只用于防止通知刷屏，
        // 以前把它当成整体闸门，会在"被弹回后 1.5 秒内再点一次"时完全绕过管控。
        homeWithRetry()
        // 允许下一次事件/轮询重新判定（否则去重状态会把后续同包事件全部吃掉）
        lastReconciledPkg = null
        if (throttled) return

        Log.i(TAG, "block pkg=${rule.pkg} reason=$reason forced=$forced")
        val text = Engine.denyText(reason, rule, LocalDateTime.now())
        lastBlock = BlockEvent(rule.pkg, rule.label, text, now)
        val prefix = if (forced) "已强制退出" else "已被拦截"
        Notifier.notifyAlert(2000 + (rule.pkg.hashCode() and 0xFF), rule.label + " " + prefix, text)
    }

    /** 回桌面失败时重试几次（无障碍刚被系统重绑那几秒会返回 false） */
    private fun homeWithRetry(attempt: Int = 0) {
        val ok = GuardAccessibilityService.goHome()
        if (!ok && attempt < 2) {
            handler.postDelayed({ homeWithRetry(attempt + 1) }, 500L)
        }
    }

    private fun refreshOngoing() {
        val s = session
        val title = "自律控制"
        val text: String = if (!GuardAccessibilityService.isEnabled(this)) {
            "⚠ 无障碍已关闭：拦截失效，点应用图标进去修复"
        } else if (s != null) {
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
        private const val TAG = "DC/Guard"
        private const val NOTIF_ID = 1001
        private const val KEEPALIVE_ACTION = "com.discipline.control.KEEPALIVE"

        @Volatile
        private var instance: GuardService? = null

        @Volatile
        var lastBlock: BlockEvent? = null

        @Volatile
        var lastWarn: WarnEvent? = null

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
