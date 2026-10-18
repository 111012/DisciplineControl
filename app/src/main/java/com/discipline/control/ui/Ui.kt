package com.discipline.control.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.discipline.control.R
import com.discipline.control.core.dp

object Ui {

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
        val h = ctx.dp(18)
        setPadding(h, ctx.dp(4), h, ctx.dp(4))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(10) }
    }

    fun sectionTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(ctx, R.color.sub))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            leftMargin = ctx.dp(6)
            topMargin = ctx.dp(18)
            bottomMargin = ctx.dp(2)
        }
    }

    fun row(
        ctx: Context,
        title: String,
        sub: String? = null,
        action: String? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = ctx.dp(13)
            setPadding(0, v, 0, v)
            isClickable = onClick != null
            if (onClick != null) setOnClickListener { onClick.invoke() }
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
        })
        if (!sub.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = sub
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.sub))
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(3) }
            })
        }
        root.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!action.isNullOrBlank()) {
            root.addView(TextView(ctx).apply {
                text = action
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                setPadding(ctx.dp(10), ctx.dp(4), 0, ctx.dp(4))
            })
        }
        return root
    }

    fun switchRow(
        ctx: Context,
        title: String,
        sub: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = ctx.dp(13)
            setPadding(0, v, 0, v)
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
        })
        if (!sub.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = sub
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.sub))
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(3) }
            })
        }
        root.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        root.addView(sw)
        return root
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(ContextCompat.getColor(ctx, R.color.line))
        layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(1))
    }

    fun header(ctx: Context, title: String, onBack: (() -> Unit)? = null): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (onBack == null) {
                setPadding(0, ctx.dp(10), 0, ctx.dp(10))
            } else {
                isClickable = true
                setOnClickListener { onBack.invoke() }
                setPadding(0, ctx.dp(10), 0, ctx.dp(10))
            }
            addView(TextView(ctx).apply {
                text = if (onBack == null) title else "‹ 返回"
                textSize = 15f
                setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                setPadding(0, ctx.dp(6), ctx.dp(10), ctx.dp(6))
            })
        }
    }

    fun pageTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(ctx, R.color.text))
    }
}
