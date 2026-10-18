package com.discipline.control.core

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 游戏内提醒横幅（可选）。默认关闭：这类悬浮窗有被游戏反外挂判定为辅助工具的风险。
 */
object Overlay {

    private var view: View? = null
    private var wm: WindowManager? = null

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    fun showWarn(ctx: Context, text: String) {
        if (!canDraw(ctx)) return
        hide()
        runCatching {
            val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(ctx).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                val ph = ctx.dp(20)
                val pv = ctx.dp(14)
                setPadding(ph, pv, ph, pv)
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(16).toFloat()
                    setColor(0xF01F2430.toInt())
                }
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = ctx.dp(36)
            }
            windowManager.addView(tv, lp)
            view = tv
            wm = windowManager
        }
    }

    fun hide() {
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        wm = null
    }
}
