package com.discipline.control.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.ui.HomeActivity

object Notifier {
    const val CH_ONGOING = "dc_ongoing"
    const val CH_ALERT = "dc_alert"

    private var appCtx: Context? = null

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ongoing = NotificationChannel(CH_ONGOING, "守护状态", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = "常驻通知，显示当前守护状态"
            }
            val alert = NotificationChannel(CH_ALERT, "提醒与拦截", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                description = "使用到点提醒、超限拦截"
            }
            nm.createNotificationChannel(ongoing)
            nm.createNotificationChannel(alert)
        }
    }

    private fun ctx(): Context = appCtx ?: throw IllegalStateException("Notifier not initialized")

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(ctx(), Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun build(channel: String, title: String, text: String, alert: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            ctx(),
            100,
            Intent(ctx(), HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(ctx(), channel)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(!alert)
            .setOnlyAlertOnce(!alert)
            .setShowWhen(false)
        if (alert) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            builder.setAutoCancel(true)
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            builder.setVibrate(longArrayOf(0, 200, 120, 200))
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_MIN)
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        }
        return builder.build()
    }

    fun ongoing(title: String, text: String): Notification = build(CH_ONGOING, title, text, false)

    fun alert(title: String, text: String): Notification = build(CH_ALERT, title, text, true)

    fun notifyAlert(id: Int, title: String, text: String) {
        if (!canPost()) return
        runCatching {
            val nm = ctx().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, alert(title, text))
        }
    }

    fun cancel(id: Int) {
        runCatching {
            val nm = ctx().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        }
    }
}
