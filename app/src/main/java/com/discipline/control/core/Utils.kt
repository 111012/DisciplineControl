package com.discipline.control.core

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue

fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

object Utils {
    fun fmtMin(min: Int): String = String.format("%02d:%02d", min / 60, min % 60)

    fun fmtDuration(ms: Long): String {
        if (ms <= 0) return "0 分钟"
        val totalMin = ms / 60000L
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h} 小时 ${m} 分"
            h > 0 -> "${h} 小时"
            else -> "${m} 分钟"
        }
    }

    fun shortClock(ms: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(ms))
    }
}

data class AppEntry(val pkg: String, val label: String, val icon: Drawable?)
