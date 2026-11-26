package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindstopBedtimeV03Test {

    private fun defaultEnabledSettings() = BedtimeSettings(
        enabled = true,
        startMinutesOfDay = 23 * 60,        // 23:00 sleep
        endMinutesOfDay = 7 * 60,           // 07:00 wake
        funEndMinutesOfDay = 22 * 60 + 50,  // 22:50 fun end
        bufferMinutes = 10,
        freeRestMinutes = 30,               // 22:00-22:30 free rest
        windDownLeadMinutes = 20,           // 22:30-22:45 prep
        countdownLeadMinutes = 5,           // 22:45-22:50 countdown
        weekendSeparate = true,
        weekendSleepMinutesOfDay = 23 * 60 + 30,
        weekendWakeMinutesOfDay = 8 * 60,
        weekendFunEndMinutesOfDay = 23 * 60 + 20,
        weekendBufferMinutes = 10,
        weekendFreeRestMinutes = 40
    )

    @Test
    fun progressiveWindDownPhasesMatchSpecExampleTimeline() {
        val s = defaultEnabledSettings()

        // 21:30 -> DAYTIME
        val eDay = BedtimeEngine.evaluate(s, 21 * 60 + 30, isWeekend = false, nightKey = "2026-11-26")
        assertEquals(BedtimePhase.DAYTIME, eDay.phase)
        assertNull(eDay.minutesUntilFunEnd)
        assertEquals(480, eDay.plannedSleepOpportunityMinutes)

        // 22:00 -> FREE_REST (50m until funEnd)
        val eFree = BedtimeEngine.evaluate(s, 22 * 60, isWeekend = false, nightKey = "2026-11-26")
        assertEquals(BedtimePhase.FREE_REST, eFree.phase)
        assertEquals(50, eFree.minutesUntilFunEnd)

        // 22:30 -> WIND_DOWN_PREP (20m until funEnd)
        val ePrep = BedtimeEngine.evaluate(s, 22 * 60 + 30, isWeekend = false, nightKey = "2026-11-26")
        assertEquals(BedtimePhase.WIND_DOWN_PREP, ePrep.phase)
        assertEquals(20, ePrep.minutesUntilFunEnd)

        // 22:45 -> WIND_DOWN_COUNTDOWN (5m until funEnd)
        val eCount = BedtimeEngine.evaluate(s, 22 * 60 + 45, isWeekend = false, nightKey = "2026-11-26")
        assertEquals(BedtimePhase.WIND_DOWN_COUNTDOWN, eCount.phase)
        assertEquals(5, eCount.minutesUntilFunEnd)

        // 22:50 -> BUFFER (0m past sleep, 0m until funEnd, restricted)
        val eBuf = BedtimeEngine.evaluate(s, 22 * 60 + 50, isWeekend = false, nightKey = "2026-11-26")
        assertEquals(BedtimePhase.BUFFER, eBuf.phase)
        assertTrue(eBuf.isNightRestrictedPhase)
        assertEquals(0, eBuf.minutesPastSleep)
        assertEquals(480, eBuf.remainingSleepOpportunityMinutes)

        // 23:20 -> SLEEP_PROTECTION (20m past planned sleep -> remaining sleep opportunity 460m)
        val eSleep = BedtimeEngine.evaluate(s, 23 * 60 + 20, isWeekend = false, nightKey = "2026-11-26")
        assertEquals(BedtimePhase.SLEEP_PROTECTION, eSleep.phase)
        assertEquals(30, eSleep.minutesPastFunEnd)
        assertEquals(20, eSleep.minutesPastSleep)
        assertEquals(460, eSleep.remainingSleepOpportunityMinutes)

        // 01:30 after midnight -> SLEEP_PROTECTION (150m past planned sleep -> remaining 330m)
        val eLate = BedtimeEngine.evaluate(s, 1 * 60 + 30, isWeekend = false, nightKey = "2026-11-26")
        assertEquals(BedtimePhase.SLEEP_PROTECTION, eLate.phase)
        assertEquals(150, eLate.minutesPastSleep)
        assertEquals(330, eLate.remainingSleepOpportunityMinutes)

        // 07:00 -> DAYTIME (morning wake reached)
        val eWake = BedtimeEngine.evaluate(s, 7 * 60, isWeekend = false, nightKey = "2026-11-26")
        assertEquals(BedtimePhase.DAYTIME, eWake.phase)
        assertFalse(eWake.isNightRestrictedPhase)
    }

    @Test
    fun weekendScheduleAppliesIndependently() {
        val s = defaultEnabledSettings()
        // At 23:05 on workday -> SLEEP_PROTECTION, on weekend (funEnd 23:20) -> WIND_DOWN_PREP
        val wd = BedtimeEngine.evaluate(s, 23 * 60 + 5, isWeekend = false, nightKey = "2026-11-26")
        val we = BedtimeEngine.evaluate(s, 23 * 60 + 5, isWeekend = true, nightKey = "2026-11-27")
        assertEquals(BedtimePhase.SLEEP_PROTECTION, wd.phase)
        assertEquals(BedtimePhase.WIND_DOWN_PREP, we.phase)
        assertEquals(510, we.plannedSleepOpportunityMinutes) // 23:30 -> 08:00 = 8h30m = 510m
    }

    @Test
    fun nightAppTargetingHonorsRestrictedAllowedAndExempt() {
        val s = defaultEnabledSettings().copy(
            nightRestrictedPackages = setOf("com.ss.android.ugc.aweme", "com.taobao.taobao"),
            nightAllowedPackages = setOf("com.kindle.reader", "com.netease.cloudmusic")
        )
        val exempt = setOf("com.tencent.mm")

        assertTrue(BedtimeEngine.isRestrictedAtNight(s, "com.ss.android.ugc.aweme", supervisedByRules = true, exemptPackages = exempt))
        assertTrue(BedtimeEngine.isRestrictedAtNight(s, "com.taobao.taobao", supervisedByRules = false, exemptPackages = exempt))
        // Reading / music explicitly allowed
        assertFalse(BedtimeEngine.isRestrictedAtNight(s, "com.kindle.reader", supervisedByRules = true, exemptPackages = exempt))
        assertFalse(BedtimeEngine.isRestrictedAtNight(s, "com.netease.cloudmusic", supervisedByRules = true, exemptPackages = exempt))
        // Exempt essential communication never blocked
        assertFalse(BedtimeEngine.isRestrictedAtNight(s, "com.tencent.mm", supervisedByRules = true, exemptPackages = exempt))
        // Unlisted app when explicit nightRestrictedPackages is configured -> not restricted
        assertFalse(BedtimeEngine.isRestrictedAtNight(s, "tv.danmaku.bili", supervisedByRules = true, exemptPackages = exempt))
    }

    @Test
    fun bedtimeSettingsEncodeDecodeRoundTripAndLegacyCompatibility() {
        val original = defaultEnabledSettings().copy(
            maxBedtimeRenewals = 0,
            restrictUntilMorning = true,
            grayscalePreferred = true,
            nightRestrictedPackages = setOf("pkg.video", "pkg.shop"),
            nightAllowedPackages = setOf("pkg.read")
        )
        val decoded = BedtimeSettings.decode(BedtimeSettings.encode(original))
        assertEquals(original, decoded)

        // Stage 5 legacy string (without v0.3 fields) decodes cleanly with sensible defaults
        val legacy = BedtimeSettings.decode("en=1;start=1350;end=420;pause=1;renew=1;cd=90;calm=1;wake=420")
        assertTrue(legacy.enabled)
        assertEquals(1350, legacy.startMinutesOfDay)
        assertEquals(420, legacy.endMinutesOfDay)
        assertEquals(1340, legacy.funEndMinutesOfDay) // auto-derived 10m before sleep
        assertEquals(1, legacy.maxBedtimeRenewals)
        assertEquals(90, legacy.bedtimeCooldownMinutes)
    }

    @Test
    fun bedtimeRewardsSupportOnTimeFullAndLateRecoveryPartialWithAntiFarming() {
        val now = 1_760_000_000_000L
        val onTimeReq = RewardRequest(
            dayKey = "2026-11-26",
            groupKey = "bedtime:2026-11-26",
            packageName = "com.ss.android.ugc.aweme",
            sessionId = 100L,
            trigger = RewardTrigger.BEDTIME_ON_TIME,
            nowWall = now
        )
        val grant = RewardEngine.evaluateGrant(onTimeReq, existing = emptyList())
        assertTrue(grant is RewardGrantDecision.Grant)
        val pending = (grant as RewardGrantDecision.Grant).pending
        assertEquals(12, pending.energy)
        assertTrue(pending.isFull)

        // Second bedtime on-time attempt same night is denied by daily group cap
        val dup = RewardEngine.evaluateGrant(
            onTimeReq.copy(sessionId = 101L),
            existing = listOf(pending)
        )
        assertTrue(dup is RewardGrantDecision.Deny)

        // Late stop partial reward earns recovery energy without clearing confirmed history
        val lateReq = onTimeReq.copy(sessionId = 200L, trigger = RewardTrigger.BEDTIME_LATE_STOP)
        val lateGrant = RewardEngine.evaluateGrant(lateReq, existing = emptyList())
        assertTrue(lateGrant is RewardGrantDecision.Grant)
        assertEquals(5, (lateGrant as RewardGrantDecision.Grant).pending.energy)
        assertFalse(lateGrant.pending.isFull)
    }
}
