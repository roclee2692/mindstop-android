package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindstopStage5Test {

    // Elastic Focus Mode tests (SPEC §15)

    @Test
    fun focusPausesClockDuringInterruptAndResumesCleanly() {
        val cfg = FocusConfig(goal = "Read OS chapter", targetFocusMinutes = 60, interruptBudgetMinutes = 15, whitelistPackages = setOf("com.dict"))
        val t0 = 1_000_000L
        var state = FocusEngine.start(cfg, t0, "2026-11-26")

        // Focus for 20 minutes
        val t20 = t0 + 20 * 60_000L
        state = FocusEngine.tick(state, t20).state
        assertEquals(20 * 60_000L, state.focusedMs)
        assertEquals(40 * 60_000L, state.focusRemainingMs)

        // Pause for 5 minutes to look something up -> focus clock stays frozen at 20m
        state = FocusEngine.pauseForInterrupt(state, t20)
        assertEquals(FocusStatus.PAUSED, state.status)
        val t25 = t20 + 5 * 60_000L
        val pausedTick = FocusEngine.tick(state, t25)
        assertEquals(20 * 60_000L, pausedTick.state.focusedMs)
        assertEquals(5 * 60_000L, pausedTick.state.interruptMs)
        assertNull(pausedTick.interruptNode) // 5/15 = 33% < 50%

        // Early return praises once
        val resumed1 = FocusEngine.resumeFromInterrupt(pausedTick.state, t25)
        assertTrue(resumed1.showEarlyReturnPraise)
        assertEquals(FocusStatus.FOCUSING, resumed1.state.status)

        // Second pause/resume does NOT praise again (anti-farming)
        val paused2 = FocusEngine.pauseForInterrupt(resumed1.state, t25 + 60_000L)
        val resumed2 = FocusEngine.resumeFromInterrupt(paused2, t25 + 120_000L)
        assertFalse(resumed2.showEarlyReturnPraise)

        // Completing remaining 39 minutes triggers justCompleted
        val done = FocusEngine.tick(resumed2.state, t25 + 120_000L + 39 * 60_000L)
        assertTrue(done.justCompleted)
        assertEquals(FocusStatus.COMPLETED, done.state.status)
        assertEquals(60 * 60_000L, done.state.focusedMs)
    }

    @Test
    fun focusInterruptProgressiveRemindersFireAt50_80_100Percent() {
        val cfg = FocusConfig(targetFocusMinutes = 30, interruptBudgetMinutes = 10)
        val t0 = 1_000_000L
        var state = FocusEngine.pauseForInterrupt(FocusEngine.start(cfg, t0, "2026-11-26"), t0)

        val r50 = FocusEngine.tick(state, t0 + 5 * 60_000L)
        assertEquals(FocusInterruptNode.HALF, r50.interruptNode)

        val r80 = FocusEngine.tick(r50.state, t0 + 8 * 60_000L)
        assertEquals(FocusInterruptNode.EIGHTY, r80.interruptNode)

        val r100 = FocusEngine.tick(r80.state, t0 + 10 * 60_000L)
        assertEquals(FocusInterruptNode.EXHAUSTED, r100.interruptNode)
        assertTrue(r100.state.interruptExceeded)
    }

    @Test
    fun focusStateEncodeDecodeRoundTrips() {
        val state = FocusEngine.start(
            FocusConfig("Paper draft", 45, 10, setOf("com.notes", "com.dict")),
            1_700_000_000L,
            "2026-11-26"
        )
        val decoded = FocusEngine.decode(FocusEngine.encode(state))
        assertEquals(state, decoded)
    }

    // Bedtime Mode tests (SPEC §16)

    @Test
    fun bedtimeOvernightWindowEvaluatesAccurately() {
        val settings = BedtimeSettings(enabled = true, startMinutesOfDay = 22 * 60 + 30, endMinutesOfDay = 7 * 60)

        // Daytime 14:00 -> outside
        assertFalse(BedtimeEngine.isInBedtimeWindow(settings, 14 * 60))
        // 22:15 -> 15 min before -> reminder BEFORE_15M
        assertEquals(BedtimeReminderNode.BEFORE_15M, BedtimeEngine.approachingReminder(settings, 22 * 60 + 15))
        // 22:26 -> 4 min before -> reminder BEFORE_5M
        assertEquals(BedtimeReminderNode.BEFORE_5M, BedtimeEngine.approachingReminder(settings, 22 * 60 + 26))
        // 22:30 -> inside
        assertTrue(BedtimeEngine.isInBedtimeWindow(settings, 22 * 60 + 30))
        // 01:15 -> inside (after midnight!)
        assertTrue(BedtimeEngine.isInBedtimeWindow(settings, 1 * 60 + 15))
        // 06:59 -> inside
        assertTrue(BedtimeEngine.isInBedtimeWindow(settings, 6 * 60 + 59))
        // 07:00 -> outside (morning restored)
        assertFalse(BedtimeEngine.isInBedtimeWindow(settings, 7 * 60))
    }

    @Test
    fun bedtimeSettingsEncodeDecodeRoundTripsAndClamps() {
        val custom = BedtimeSettings(
            enabled = true,
            startMinutesOfDay = 23 * 60,
            endMinutesOfDay = 6 * 60 + 30,
            pauseEntertainment = true,
            maxBedtimeRenewals = 0,
            bedtimeCooldownMinutes = 180,
            reduceStimulation = true,
            plannedWakeMinutesOfDay = 6 * 60 + 45
        )
        assertEquals(custom, BedtimeSettings.decode(BedtimeSettings.encode(custom)))
        assertEquals(BedtimeSettings(), BedtimeSettings.decode(null))
        assertEquals("22:30", BedtimeEngine.formatMinuteOfDay(22 * 60 + 30))
    }

    // Reflection tests (SPEC §14)

    @Test
    fun reflectionReasonsEncodeDecodeCleanly() {
        val set = setOf(ReflectionReason.RECOMMENDATIONS, ReflectionReason.FATIGUE)
        val encoded = ReflectionReason.encodeList(set)
        assertEquals(set, ReflectionReason.decodeList(encoded))
        assertTrue(ReflectionReason.decodeList("").isEmpty())
    }
}
