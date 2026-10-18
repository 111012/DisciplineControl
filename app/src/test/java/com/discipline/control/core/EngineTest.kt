package com.discipline.control.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 纯逻辑单测（跑在 JVM 上，不需要真机）：时段判定、桌面可见性、启动拦截、放宽判定、下次开放时间。
 * 时间基准：2026-10-18 是周日（dayOfWeek=7）。
 */
class EngineTest {

    private val sg = AppRule(
        pkg = "com.tencent.tmgp.sgame",
        label = "王者荣耀",
        windows = listOf(TimeWindow(TimeWindow.EVERY_DAY, 20 * 60, 23 * 60)),
        dailyOpenLimit = 3,
        sessionLimitMin = 60,
        graceMin = 20,
        countGapMin = 5
    )

    private fun at(hour: Int, minute: Int, day: Int = 18) = LocalDateTime.of(2026, 10, day, hour, minute)

    // ---------------- 时段 ----------------

    @Test
    fun windowBoundaries() {
        assertFalse("19:59 不该开放", sg.inWindow(at(19, 59)))
        assertTrue("20:00 应该开放", sg.inWindow(at(20, 0)))
        assertTrue("22:59 应该开放", sg.inWindow(at(22, 59)))
        assertFalse("23:00 应该关闭", sg.inWindow(at(23, 0)))
        assertFalse("凌晨不开放", sg.inWindow(at(0, 30)))
    }

    @Test
    fun crossMidnightWindow() {
        val w = TimeWindow(TimeWindow.EVERY_DAY, 22 * 60, 2 * 60)
        assertTrue(w.covers(at(23, 0)))
        assertTrue(w.covers(at(1, 59)))
        assertFalse(w.covers(at(2, 0)))
        assertFalse(w.covers(at(12, 0)))
    }

    @Test
    fun weekdayFilter() {
        val weekdays = TimeWindow(0x1F, 20 * 60, 23 * 60) // 周一到周五
        assertTrue("周日 2026-10-18 不在工作日里", !weekdays.covers(at(21, 0, 18)))
        assertTrue("周一 2026-10-19 工作日", weekdays.covers(at(21, 0, 19)))
        assertTrue("周五 2026-10-23 工作日", weekdays.covers(at(21, 0, 23)))
        assertTrue("周六 2026-10-24 不是工作日", !weekdays.covers(at(21, 0, 24)))
    }

    // ---------------- 桌面可见性 ----------------

    @Test
    fun visibleOnlyInsideWindow() {
        assertTrue(Engine.visibleInLauncher(sg, PkgUsage(), at(20, 30)))
        assertFalse(Engine.visibleInLauncher(sg, PkgUsage(), at(18, 0)))
    }

    @Test
    fun hiddenAfterThreeOpens() {
        assertTrue(Engine.visibleInLauncher(sg, PkgUsage(opens = 2), at(21, 0)))
        assertFalse("已经点了 3 次就该隐身", Engine.visibleInLauncher(sg, PkgUsage(opens = 3), at(21, 0)))
    }

    @Test
    fun hiddenWhenFinishedOrDisabled() {
        assertFalse(Engine.visibleInLauncher(sg, PkgUsage(opens = 1, finished = true), at(21, 0)))
        assertFalse(Engine.visibleInLauncher(sg.copy(enabled = false), PkgUsage(), at(21, 0)))
    }

    @Test
    fun unlimitedOpensNeverHides() {
        val unlimited = sg.copy(dailyOpenLimit = 0)
        assertTrue(Engine.visibleInLauncher(unlimited, PkgUsage(opens = 99), at(21, 0)))
    }

    // ---------------- 启动拦截 ----------------

    @Test
    fun denyReasons() {
        assertEquals(
            DenyReason.NOT_IN_WINDOW,
            Engine.launchDeny(sg, PkgUsage(), at(10, 0), sessionActive = false)
        )
        assertEquals(
            DenyReason.NO_OPENS_LEFT,
            Engine.launchDeny(sg, PkgUsage(opens = 3), at(21, 0), sessionActive = false)
        )
        assertEquals(
            DenyReason.DAY_FINISHED,
            Engine.launchDeny(sg, PkgUsage(finished = true), at(21, 0), sessionActive = false)
        )
        assertNull(Engine.launchDeny(sg, PkgUsage(opens = 1), at(21, 0), sessionActive = false))
    }

    @Test
    fun resumingSessionBypassesOpenLimit() {
        // 同一次会话内切出去再切回来，不该因为"次数用完"被拦
        assertNull(Engine.launchDeny(sg, PkgUsage(opens = 3), at(21, 0), sessionActive = true))
    }

    @Test
    fun disabledRuleNeverBlocks() {
        assertNull(Engine.launchDeny(sg.copy(enabled = false), PkgUsage(opens = 99), at(10, 0), false))
    }

    // ---------------- 下次开放时间 ----------------

    @Test
    fun nextOpenText() {
        assertEquals("今天 20:00", Engine.nextOpenText(sg, at(19, 30)))
        assertEquals("今天 20:00", Engine.nextOpenText(sg, at(10, 0)))
        assertEquals("明天 20:00", Engine.nextOpenText(sg, at(23, 30)))
    }

    // ---------------- 放宽判定（决定要不要延后 24 小时生效） ----------------

    @Test
    fun moreOpensIsRelaxation() {
        assertTrue(Engine.isRelaxation(sg, sg.copy(dailyOpenLimit = 5)))
        assertFalse(Engine.isRelaxation(sg, sg.copy(dailyOpenLimit = 2)))
        assertTrue("改成不限次数是放宽", Engine.isRelaxation(sg, sg.copy(dailyOpenLimit = 0)))
        assertFalse("不变的规则不是放宽", Engine.isRelaxation(sg, sg.copy()))
    }

    @Test
    fun longerSessionIsRelaxation() {
        assertTrue(Engine.isRelaxation(sg, sg.copy(sessionLimitMin = 90)))
        assertFalse(Engine.isRelaxation(sg, sg.copy(sessionLimitMin = 30)))
        assertTrue(Engine.isRelaxation(sg, sg.copy(sessionLimitMin = 0)))
        assertTrue(Engine.isRelaxation(sg, sg.copy(graceMin = 40)))
        assertFalse(Engine.isRelaxation(sg, sg.copy(graceMin = 5)))
    }

    @Test
    fun widerWindowIsRelaxation() {
        val wider = sg.copy(windows = listOf(TimeWindow(TimeWindow.EVERY_DAY, 19 * 60, 23 * 60)))
        val narrower = sg.copy(windows = listOf(TimeWindow(TimeWindow.EVERY_DAY, 21 * 60, 23 * 60)))
        assertTrue(Engine.isRelaxation(sg, wider))
        assertFalse(Engine.isRelaxation(sg, narrower))
    }

    @Test
    fun extraWindowIsRelaxation() {
        val twoWindows = sg.copy(
            windows = sg.windows + TimeWindow(TimeWindow.EVERY_DAY, 12 * 60, 13 * 60)
        )
        assertTrue(Engine.isRelaxation(sg, twoWindows))
    }

    @Test
    fun turningControlOffIsRelaxation() {
        assertTrue("停用管控 = 放宽", Engine.isRelaxation(sg, sg.copy(enabled = false)))
        assertFalse("重新开启管控 = 收紧", Engine.isRelaxation(sg.copy(enabled = false), sg.copy(enabled = true)))
        assertTrue(Engine.isRelaxation(sg, sg.copy(kickAtWindowEnd = false)))
        assertTrue(Engine.isRelaxation(sg, sg.copy(countGapMin = 30)))
    }

    // ---------------- 会话续接（决定会不会偷偷重新给满时长） ----------------

    @Test
    fun resumeWithinGap() {
        val u = PkgUsage(opens = 2, sessionMs = 30 * 60_000L, lastExitAt = 1_000_000L)
        assertTrue(Engine.isResumingSession(sg, u, 1_000_000L + 2 * 60_000L))
        assertFalse(Engine.isResumingSession(sg, u, 1_000_000L + 6 * 60_000L))
    }

    @Test
    fun warnedAlwaysResumesSoTimerNeverResets() {
        // 到点提醒后被踢回桌面，过很久再回来：仍接着倒计时，而不是重新给 60 分钟
        val u = PkgUsage(opens = 1, sessionMs = 61 * 60_000L, warned = true, lastExitAt = 0L)
        assertTrue(Engine.isResumingSession(sg, u, 9_999_999_999L))
    }

    @Test
    fun finishedNeverResumes() {
        val u = PkgUsage(opens = 1, sessionMs = 61 * 60_000L, warned = true, finished = true)
        assertFalse(Engine.isResumingSession(sg, u, 1L))
        assertFalse(Engine.isResumingSession(sg, null, 1L))
        assertFalse(Engine.isResumingSession(sg, PkgUsage(), 1L))
    }

    @Test
    fun supersetCheck() {
        val big = listOf(TimeWindow(TimeWindow.EVERY_DAY, 0, 24 * 60))
        val small = listOf(TimeWindow(TimeWindow.EVERY_DAY, 20 * 60, 23 * 60))
        assertTrue(Engine.supersetOf(big, small))
        assertFalse(Engine.supersetOf(small, big))
        assertTrue("空集合被任何集合覆盖", Engine.supersetOf(small, emptyList()))
    }
}
