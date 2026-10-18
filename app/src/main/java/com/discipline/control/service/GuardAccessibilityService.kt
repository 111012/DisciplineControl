package com.discipline.control.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 只做两件事：把当前前台应用包名告诉 GuardService；需要时把用户强制送回桌面。
 * 虽然开启了 canRetrieveWindowContent（为了用 rootInActiveWindow 拿包名兜底），
 * 但代码只读取 packageName，不读任何屏幕内容、不联网。
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DC/A11y"

        @Volatile
        private var lastWindowsProbe = 0L

        @Volatile
        var instance: GuardAccessibilityService? = null
            private set

        fun goHome(): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
                .getOrDefault(false)
        }

        /**
         * 当前真正获得焦点的窗口属于哪个应用。
         * 只取包名，不读内容；拿不到（例如安全窗口）时返回 null，此时调用方按"不变"处理。
         */
        fun activePackage(): String? {
            val svc = instance ?: return null
            return runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        }

        fun isEnabled(ctx: Context): Boolean {
            val enabled = runCatching {
                Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
            }.getOrDefault(0) == 1
            if (!enabled) return false
            val raw = runCatching {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull() ?: return false
            val full = ComponentName(ctx, GuardAccessibilityService::class.java).flattenToString()
            val short = ComponentName(ctx, GuardAccessibilityService::class.java).flattenToShortString()
            return raw.split(':').any {
                it.equals(full, true) || it.equals(short, true) ||
                    it.endsWith("GuardAccessibilityService")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility connected")
        GuardService.startSafely(this)
    }

    override fun onDestroy() {
        Log.i(TAG, "accessibility destroyed")
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 无障碍服务是系统绑定的，进程基本不会被杀；顺带把守护服务拉起来（比闹钟可靠）
        if (!GuardService.isRunning()) GuardService.startSafely(this)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                Log.d(TAG, "window -> $pkg")
                GuardService.notifyForeground(pkg)
            }

            // 窗口列表变化（悬浮层出现/消失、焦点回切）时补一次主动查询，
            // 解决"游戏被系统悬浮窗盖住后回到前台却不发 WINDOW_STATE_CHANGED"导致计时卡死的问题。
            // rootInActiveWindow 在重负载游戏里可能几十~几百毫秒，做个 300ms 节流，避免主线程被打爆。
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastWindowsProbe < 300L) return
                lastWindowsProbe = now
                val pkg = rootInActiveWindow?.packageName?.toString() ?: return
                Log.d(TAG, "windows-changed -> $pkg")
                GuardService.notifyForeground(pkg)
            }
        }
    }
}
