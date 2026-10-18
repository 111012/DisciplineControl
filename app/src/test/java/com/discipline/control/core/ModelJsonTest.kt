package com.discipline.control.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则/配置/用量的 JSON 存取往返测试：这些结构真的会写进 SharedPreferences，
 * 一旦 toJson / fromJson 对不上，用户改的规则就会静默丢失。
 */
class ModelJsonTest {

    @Test
    fun ruleRoundTrip() {
        val rule = AppRule(
            pkg = "com.tencent.tmgp.sgame",
            label = "王者荣耀",
            enabled = false,
            windows = listOf(
                TimeWindow(0x1F, 20 * 60, 23 * 60),
                TimeWindow(0x60, 12 * 60, 13 * 60)
            ),
            dailyOpenLimit = 5,
            sessionLimitMin = 90,
            graceMin = 15,
            countGapMin = 7,
            kickAtWindowEnd = false
        )
        val back = AppRule.fromJson(JSONObject(rule.toJson().toString()))
        assertEquals(rule, back)
    }

    @Test
    fun emptyWindowsRoundTrip() {
        val rule = AppRule(pkg = "p", label = "L", windows = emptyList())
        assertEquals(rule, AppRule.fromJson(JSONObject(rule.toJson().toString())))
        assertEquals("无时段（全天不可用）", rule.windowText())
    }

    @Test
    fun configRoundTrip() {
        val cfg = GlobalConfig(
            resetHour = 4,
            pinSalt = "salt",
            pinHash = "hash",
            relaxDelayHours = 48,
            remindStyle = RemindStyle.HOME,
            showStats = false,
            firstRunDone = true
        )
        assertEquals(cfg, GlobalConfig.fromJson(JSONObject(cfg.toJson().toString())))
    }

    @Test
    fun legacyConfigMigratesWarnOverlay() {
        // 0.1.0 存的是 warnOverlay 布尔值，升级后要能读成新的三档提醒方式
        val legacy = JSONObject().put("warnOverlay", true).put("relaxDelayHours", 12)
        val cfg = GlobalConfig.fromJson(legacy)
        assertEquals(RemindStyle.OVERLAY, cfg.remindStyle)
        assertEquals(12, cfg.relaxDelayHours)
        assertEquals(RemindStyle.NOTIFY, GlobalConfig.fromJson(JSONObject()).remindStyle)
    }

    @Test
    fun dayUsageRoundTrip() {
        val day = DayUsage("2026-10-18")
        day.get("com.tencent.tmgp.sgame").apply {
            opens = 2
            sessionMs = 61_000L
            warned = true
            finished = false
            lastExitAt = 123456L
        }
        day.get("other.app").opens = 1

        val back = DayUsage.fromJson(day.toJson().toString())
        assertNotNull(back)
        assertEquals("2026-10-18", back!!.date)
        val sg = back.peek("com.tencent.tmgp.sgame")!!
        assertEquals(2, sg.opens)
        assertEquals(61_000L, sg.sessionMs)
        assertTrue(sg.warned)
        assertFalse(sg.finished)
        assertEquals(123456L, sg.lastExitAt)
        assertEquals(1, back.peek("other.app")!!.opens)
    }

    @Test
    fun malformedJsonDoesNotCrash() {
        assertEquals(null, DayUsage.fromJson(null))
        assertEquals(null, DayUsage.fromJson(""))
        assertEquals(null, DayUsage.fromJson("{not json"))
    }
}
