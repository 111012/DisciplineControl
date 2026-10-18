package com.discipline.control.core

import android.app.Activity
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest
import java.util.UUID

/**
 * 设置页的 PIN 门禁。PIN 只在本机保存加盐哈希。
 */
object Pin {

    fun isSet(): Boolean = Store.config().hasPin()

    fun set(pin: String) {
        val salt = UUID.randomUUID().toString().replace("-", "").take(16)
        Store.saveConfig(Store.config().copy(pinSalt = salt, pinHash = hash(salt, pin)))
    }

    fun verify(pin: String): Boolean {
        val c = Store.config()
        if (!c.hasPin()) return true
        return hash(c.pinSalt, pin) == c.pinHash
    }

    private fun hash(salt: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt + "#" + "dc" + "#" + pin).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        bytes.forEach { sb.append(String.format("%02x", it.toInt() and 0xff)) }
        return sb.toString()
    }

    /** 要求先通过 PIN（首次进入则先设置 PIN） */
    fun gate(activity: Activity, onOk: () -> Unit) {
        if (!isSet()) {
            setup(activity, onOk)
            return
        }
        val et = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "输入 PIN"
            setSingleLine()
        }
        val box = FrameLayout(activity).apply {
            val pad = activity.dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(et, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("请输入 PIN")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                if (verify(et.text.toString())) onOk()
                else Toast.makeText(activity, "PIN 不正确", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun setup(activity: Activity, onOk: () -> Unit) {
        val et = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "设置 4–8 位数字 PIN"
            setSingleLine()
        }
        val box = FrameLayout(activity).apply {
            val pad = activity.dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(et, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("设置 PIN（保护设置页不被随手改动）")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val pin = et.text.toString()
                if (pin.length < 4) {
                    Toast.makeText(activity, "至少 4 位数字", Toast.LENGTH_SHORT).show()
                } else {
                    set(pin)
                    Toast.makeText(activity, "PIN 已设置", Toast.LENGTH_SHORT).show()
                    onOk()
                }
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    /** 修改 PIN：先验证旧 PIN */
    fun change(activity: Activity) {
        if (!isSet()) {
            setup(activity) {}
            return
        }
        val et = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "当前 PIN"
            setSingleLine()
        }
        val box = FrameLayout(activity).apply {
            val pad = activity.dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(et, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("修改 PIN")
            .setView(box)
            .setPositiveButton("下一步") { _, _ ->
                if (verify(et.text.toString())) setup(activity) {} else
                    Toast.makeText(activity, "PIN 不正确", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
