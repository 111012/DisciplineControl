package com.discipline.control.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 只做两件事：把当前前台应用包名告诉 GuardService；需要时把用户强制送回桌面。
 * 不读取屏幕内容（canRetrieveWindowContent=false），不联网。
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GuardAccessibilityService? = null
            private set

        fun goHome(): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
                .getOrDefault(false)
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
        GuardService.startSafely(this)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 无障碍服务是系统绑定的，进程基本不会被杀；顺带把守护服务拉起来（比闹钟可靠）
        if (!GuardService.isRunning()) GuardService.startSafely(this)
        val pkg = event.packageName?.toString() ?: return
        GuardService.notifyForeground(pkg)
    }
}
