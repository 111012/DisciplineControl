package com.discipline.control.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 一个可开放时段。[days] 用位掩码表示周几，bit0=周一 … bit6=周日。
 * startMin/endMin 为一天中的分钟数（0..1439），start > end 表示跨零点。
 */
data class TimeWindow(val days: Int, val startMin: Int, val endMin: Int) {

    fun covers(now: LocalDateTime): Boolean =
        covers(now.dayOfWeek.value - 1, now.hour * 60 + now.minute)

    /**
     * 是否覆盖"周 [dayIndex0Mon] 的第 [minute] 分钟"。
     * 跨零点时段（如 22:00–02:00）里 00:00–02:00 这一段归属**前一天**，
     * 否则"只在周一开放 22:00–02:00"会变成周二的凌晨也开放/周一凌晨被误拦。
     */
    fun covers(dayIndex0Mon: Int, minute: Int): Boolean {
        return when {
            startMin < endMin -> {
                if (minute < startMin || minute >= endMin) false
                else (days and (1 shl dayIndex0Mon)) != 0
            }

            startMin > endMin -> {
                when {
                    minute >= startMin -> (days and (1 shl dayIndex0Mon)) != 0
                    minute < endMin -> {
                        val prevDay = (dayIndex0Mon + 6) % 7
                        (days and (1 shl prevDay)) != 0
                    }

                    else -> false
                }
            }

            else -> false
        }
    }

    fun timeText(): String = Utils.fmtMin(startMin) + "–" + Utils.fmtMin(endMin)

    fun daysText(): String {
        if (days and 0x7F == 0x7F) return "每天"
        if (days == 0x1F) return "工作日"
        if (days == 0x60) return "周末"
        if (days == 0) return "未选日期"
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val sb = StringBuilder()
        for (i in 0 until 7) if ((days and (1 shl i)) != 0) sb.append(names[i])
        return "周" + sb.toString()
    }

    fun text(): String = daysText() + " " + timeText()

    fun toJson(): JSONObject = JSONObject()
        .put("days", days)
        .put("start", startMin)
        .put("end", endMin)

    companion object {
        const val EVERY_DAY = 0x7F

        fun fromJson(o: JSONObject): TimeWindow = TimeWindow(
            days = o.optInt("days", EVERY_DAY),
            startMin = o.optInt("start", 0),
            endMin = o.optInt("end", 0)
        )
    }
}

data class AppRule(
    val pkg: String,
    val label: String,
    val enabled: Boolean = true,
    val windows: List<TimeWindow> = listOf(TimeWindow(TimeWindow.EVERY_DAY, 20 * 60, 23 * 60)),
    /** 每日最多打开次数，0 = 不限 */
    val dailyOpenLimit: Int = 3,
    /** 单次使用时长上限（分钟），0 = 不限 */
    val sessionLimitMin: Int = 60,
    /** 到点提醒后额外宽限（分钟） */
    val graceMin: Int = 20,
    /** 离开超过这么多分钟再回来，才算新的一次打开 */
    val countGapMin: Int = 5,
    /** 时段结束时若还在使用，是否立即退出 */
    val kickAtWindowEnd: Boolean = true
) {

    fun inWindow(now: LocalDateTime): Boolean = windows.any { it.covers(now) }

    fun windowText(): String =
        if (windows.isEmpty()) "无时段（全天不可用）" else windows.joinToString("、") { it.text() }

    fun limitText(): String = when {
        dailyOpenLimit <= 0 && sessionLimitMin <= 0 -> "不限次数与时长"
        dailyOpenLimit <= 0 -> "不限次数 · 单次 ${sessionLimitMin} 分钟" + graceText()
        sessionLimitMin <= 0 -> "每日 ${dailyOpenLimit} 次 · 不限时长"
        else -> "每日 ${dailyOpenLimit} 次 · 单次 ${sessionLimitMin} 分钟" + graceText()
    }

    private fun graceText(): String = if (graceMin > 0) "（+${graceMin} 分钟宽限）" else ""

    fun summary(): String = windowText() + "\n" + limitText()

    fun toJson(): JSONObject {
        val arr = JSONArray()
        windows.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("pkg", pkg)
            .put("label", label)
            .put("enabled", enabled)
            .put("windows", arr)
            .put("dailyOpenLimit", dailyOpenLimit)
            .put("sessionLimitMin", sessionLimitMin)
            .put("graceMin", graceMin)
            .put("countGapMin", countGapMin)
            .put("kickAtWindowEnd", kickAtWindowEnd)
    }

    companion object {
        fun default(pkg: String, label: String): AppRule = AppRule(pkg = pkg, label = label)

        fun fromJson(o: JSONObject): AppRule {
            val windows = ArrayList<TimeWindow>()
            val arr = o.optJSONArray("windows")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    windows.add(TimeWindow.fromJson(w))
                }
            }
            return AppRule(
                pkg = o.optString("pkg"),
                label = o.optString("label"),
                enabled = o.optBoolean("enabled", true),
                windows = windows,
                dailyOpenLimit = o.optInt("dailyOpenLimit", 3),
                sessionLimitMin = o.optInt("sessionLimitMin", 60),
                graceMin = o.optInt("graceMin", 20),
                countGapMin = o.optInt("countGapMin", 5),
                kickAtWindowEnd = o.optBoolean("kickAtWindowEnd", true)
            )
        }
    }
}

data class GlobalConfig(
    /** 每天几点算新的一天（0..6），默认 0 点 */
    val resetHour: Int = 0,
    val pinSalt: String = "",
    val pinHash: String = "",
    /** 放宽限制的修改需要等多少小时生效 */
    val relaxDelayHours: Int = 24,
    /** 到点提醒方式，见 RemindStyle */
    val remindStyle: Int = RemindStyle.NOTIFY,
    val showStats: Boolean = true,
    val firstRunDone: Boolean = false
) {
    fun hasPin(): Boolean = pinHash.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("resetHour", resetHour)
        .put("pinSalt", pinSalt)
        .put("pinHash", pinHash)
        .put("relaxDelayHours", relaxDelayHours)
        .put("remindStyle", remindStyle)
        .put("showStats", showStats)
        .put("firstRunDone", firstRunDone)

    companion object {
        fun fromJson(o: JSONObject): GlobalConfig {
            val style = if (o.has("remindStyle")) {
                o.optInt("remindStyle", RemindStyle.NOTIFY)
            } else if (o.optBoolean("warnOverlay", false)) {
                RemindStyle.OVERLAY
            } else {
                RemindStyle.NOTIFY
            }
            return GlobalConfig(
                resetHour = o.optInt("resetHour", 0),
                pinSalt = o.optString("pinSalt", ""),
                pinHash = o.optString("pinHash", ""),
                relaxDelayHours = o.optInt("relaxDelayHours", 24),
                remindStyle = style,
                showStats = o.optBoolean("showStats", true),
                firstRunDone = o.optBoolean("firstRunDone", false)
            )
        }
    }
}

/** 到点提醒方式 */
object RemindStyle {
    /** 只用通知 + 震动（最安全） */
    const val NOTIFY = 0

    /** 通知 + 游戏内悬浮横幅（有被游戏反外挂误判的风险） */
    const val OVERLAY = 1

    /** 到点直接把用户退回桌面（最"砸脸"，但完全不覆盖游戏画面，零误判风险） */
    const val HOME = 2

    fun text(style: Int): String = when (style) {
        OVERLAY -> "通知 + 游戏内悬浮横幅（有误判风险）"
        HOME -> "到点先退回桌面（最强烈，不覆盖游戏画面）"
        else -> "仅通知 + 震动（最安全，推荐）"
    }
}

/** 单个应用当天的用量 */
class PkgUsage(
    var opens: Int = 0,
    var sessionMs: Long = 0L,
    /** 今天是否已经彻底用完（用时到点强制退出 / 次数用尽） */
    var finished: Boolean = false,
    var lastExitAt: Long = 0L,
    var warned: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("opens", opens)
        .put("sessionMs", sessionMs)
        .put("finished", finished)
        .put("lastExitAt", lastExitAt)
        .put("warned", warned)

    companion object {
        fun fromJson(o: JSONObject): PkgUsage = PkgUsage(
            opens = o.optInt("opens", 0),
            sessionMs = o.optLong("sessionMs", 0L),
            finished = o.optBoolean("finished", false),
            lastExitAt = o.optLong("lastExitAt", 0L),
            warned = o.optBoolean("warned", false)
        )
    }
}

class DayUsage(val date: String) {
    val apps = LinkedHashMap<String, PkgUsage>()

    fun get(pkg: String): PkgUsage = apps.getOrPut(pkg) { PkgUsage() }

    fun peek(pkg: String): PkgUsage? = apps[pkg]

    fun toJson(): JSONObject {
        val obj = JSONObject()
        apps.forEach { (k, v) -> obj.put(k, v.toJson()) }
        return JSONObject().put("date", date).put("apps", obj)
    }

    companion object {
        fun fromJson(text: String?): DayUsage? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(text)
                val d = DayUsage(o.optString("date", LocalDate.now().toString()))
                val apps = o.optJSONObject("apps") ?: return@runCatching d
                val it = apps.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = apps.optJSONObject(k) ?: continue
                    d.apps[k] = PkgUsage.fromJson(v)
                }
                d
            }.getOrNull()
        }
    }
}

enum class DenyReason {
    /** 不在允许的时段 */
    NOT_IN_WINDOW,

    /** 今日打开次数已用完 */
    NO_OPENS_LEFT,

    /** 今日已经彻底用完（时长到点或强制退出） */
    DAY_FINISHED,

    /** 时段结束，被强制退出 */
    WINDOW_ENDED,
}

/** 打开次数/时长的判定，纯函数，方便在桌面预览与守护服务中共用 */
object Engine {

    /**
     * 逻辑"天"：每天 [resetHour] 点之前算前一天。
     * 抽成纯函数，跨天相关的行为不用等真机到 0 点也能单测。
     */
    fun logicalDate(now: LocalDateTime, resetHour: Int): LocalDate {
        val d = now.toLocalDate()
        return if (now.hour < resetHour.coerceIn(0, 23)) d.minusDays(1) else d
    }

    /** 桌面是否显示该应用图标 */
    fun visibleInLauncher(rule: AppRule, usage: PkgUsage?, now: LocalDateTime): Boolean {
        if (!rule.enabled) return false
        if (usage?.finished == true) return false
        if (!rule.inWindow(now)) return false
        val opens = usage?.opens ?: 0
        if (rule.dailyOpenLimit > 0 && opens >= rule.dailyOpenLimit) return false
        return true
    }

    fun launchDeny(rule: AppRule, usage: PkgUsage?, now: LocalDateTime, sessionActive: Boolean): DenyReason? {
        if (!rule.enabled) return null
        if (usage?.finished == true) return DenyReason.DAY_FINISHED
        if (!rule.inWindow(now)) return DenyReason.NOT_IN_WINDOW
        if (!sessionActive && rule.dailyOpenLimit > 0 && (usage?.opens ?: 0) >= rule.dailyOpenLimit) {
            return DenyReason.NO_OPENS_LEFT
        }
        return null
    }

    /**
     * 通知栏、输入法、权限弹窗这类系统临时窗口，不代表"用户离开了游戏"。
     * 游戏里下拉通知栏不应该暂停计时。
     */
    fun isTransientPackage(pkg: String): Boolean {
        if (pkg == "android") return true
        val lower = pkg.lowercase()
        return lower.contains("systemui") ||
            lower.contains("inputmethod") ||
            lower.contains("permissioncontroller") ||
            lower.contains("packageinstaller")
    }

    /**
     * 重新进入受限应用时，是否算"接着上次的会话"：
     * 不消耗次数、不把计时清零（宽限期内回来继续倒数）。
     */
    fun isResumingSession(rule: AppRule, usage: PkgUsage?, now: Long): Boolean {
        if (usage == null) return false
        if (usage.finished) return false
        if (usage.sessionMs <= 0L) return false
        // 已经提醒过、还在宽限期内：继续上次会话，绝不能重置计时
        if (usage.warned) return true
        if (usage.lastExitAt <= 0L) return false
        // 最少给 10 秒回归缓冲：系统悬浮窗（MIUI 游戏加速等）抢一下焦点再回来，
        // 即使把"计数间隔"设成 0 也不该算成新的一次打开
        val gap = rule.countGapMin.coerceAtLeast(0) * 60_000L
        val safeGap = if (gap < 10_000L) 10_000L else gap
        return now - usage.lastExitAt <= safeGap
    }

    fun denyText(reason: DenyReason, rule: AppRule, now: LocalDateTime): String = when (reason) {
        DenyReason.NOT_IN_WINDOW -> "不在开放时段。下次开放：${nextOpenText(rule, now)}"
        DenyReason.NO_OPENS_LEFT -> "今天的 ${rule.dailyOpenLimit} 次已经用完了，${resetText()}之后再来"
        DenyReason.DAY_FINISHED -> "今天的时间/次数已经用完，${resetText()}之后再来"
        DenyReason.WINDOW_ENDED -> "开放时段已结束，${nextOpenText(rule, now)}再来"
    }

    private fun resetText(): String {
        val h = Store.config().resetHour
        return if (h == 0) "明天 0 点" else "明天 $h 点"
    }

    /**
     * 下一次开放的描述，例如 «今天 20:00»。
     * 旧实现从现在起逐分钟扫 14 天（最多 2 万次覆盖判断，而且桌面每分钟在主线程跑一次）；
     * 改成只检查"各时段的下一个开始时刻"，复杂度降到 O(时段数 × 14)。
     */
    fun nextOpenText(rule: AppRule, now: LocalDateTime): String {
        if (rule.windows.isEmpty()) return "（无可用时段）"
        if (rule.inWindow(now)) return "现在"
        var best: LocalDateTime? = null
        for (offset in 0..14) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            val dayIdx = day.dayOfWeek.value - 1
            for (w in rule.windows) {
                if ((w.days and (1 shl dayIdx)) == 0) continue
                val start = day.atStartOfDay().plusMinutes(w.startMin.toLong())
                if (!start.isAfter(now)) continue
                if (best == null || start.isBefore(best)) best = start
            }
        }
        val t = best ?: return "（无可用时段）"
        val diff = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val dayText = when (diff) {
            0L -> "今天"
            1L -> "明天"
            else -> "周" + names[t.dayOfWeek.value - 1]
        }
        return dayText + " " + Utils.fmtMin(t.hour * 60 + t.minute)
    }
    /** new 是否相对 old 放宽了限制 */
    fun isRelaxation(old: AppRule, new: AppRule): Boolean {
        // 关掉管控 = 完全不受限，属于放宽；重新开启管控 = 收紧，立即生效
        if (old.enabled && !new.enabled) return true
        if (new.windows != old.windows && supersetOf(new.windows, old.windows)) return true
        if (new.dailyOpenLimit == 0 && old.dailyOpenLimit != 0) return true
        if (new.dailyOpenLimit != 0 && old.dailyOpenLimit != 0 && new.dailyOpenLimit > old.dailyOpenLimit) return true
        if (new.sessionLimitMin == 0 && old.sessionLimitMin != 0) return true
        if (old.sessionLimitMin != 0 && new.sessionLimitMin != 0 && new.sessionLimitMin > old.sessionLimitMin) return true
        if (new.sessionLimitMin != 0 && old.sessionLimitMin != 0 && new.graceMin > old.graceMin) return true
        if (new.sessionLimitMin != 0 && new.countGapMin > old.countGapMin) return true
        if (old.kickAtWindowEnd && !new.kickAtWindowEnd) return true
        return false
    }

    /** big 是否完整覆盖了 small 的所有分钟 */
    fun supersetOf(big: List<TimeWindow>, small: List<TimeWindow>): Boolean {
        for (d in 0 until 7) {
            for (m in 0 until 1440) {
                val inSmall = small.any { it.covers(d, m) }
                if (!inSmall) continue
                if (!big.any { it.covers(d, m) }) return false
            }
        }
        return true
    }
}
