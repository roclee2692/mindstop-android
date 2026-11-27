package app.mindstop.core

import app.mindstop.service.MindstopAlarmReceiver
import app.mindstop.service.MindstopAlarmScheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Comprehensive unit tests covering all 10 V2 Morning Activation acceptance scenarios plus
 * V1 06:30->07:05 end-to-end timeline, token isolation, step-proof fallback, and local stats.
 */
class MindstopMorningTest {

    private fun localTime(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 27, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun nextTriggerWallRespectsOneShotAndWeekdayBitmask() {
        val thuMorning = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 26, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val oneshot = MorningAlarm(id = 1L, hour = 7, minute = 0, repeatDaysMask = MorningAlarm.ONCE_MASK)
        val nextOne = Calendar.getInstance().apply { timeInMillis = MorningAlarmPlanner.nextTriggerWall(oneshot, thuMorning) }
        assertEquals(27, nextOne.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, nextOne.get(Calendar.HOUR_OF_DAY))

        val wknd = MorningAlarm(id = 2L, hour = 8, minute = 30, repeatDaysMask = MorningAlarm.WEEKEND_MASK)
        val nextWknd = Calendar.getInstance().apply { timeInMillis = MorningAlarmPlanner.nextTriggerWall(wknd, thuMorning) }
        assertEquals(Calendar.SATURDAY, nextWknd.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, nextWknd.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, nextWknd.get(Calendar.MINUTE))
    }

    /** Acceptance #1 & #5 + V1 06:30 -> 06:35 -> 06:37 -> 06:55 -> 07:05 timeline. */
    @Test
    fun acceptanceTimeline0630To0705AndOldAlarmTokenInvalidation() {
        val alarm = MorningAlarm(id = 10L, hour = 6, minute = 30)
        val settings = MorningV2Settings()
        val t0630 = localTime(6, 30)
        val t0634 = localTime(6, 34, 59)
        val t0635 = localTime(6, 35, 0)
        val t0637 = localTime(6, 37, 0)
        val t0642 = localTime(6, 42, 0)
        val t0643 = localTime(6, 43, 0)
        val t0655 = localTime(6, 55, 0)
        val t0705 = localTime(7, 5, 0)

        // 06:30: Stage 1 starts immediately when alarm fires
        var s = MorningActivationEngine.onRingStarted(alarm, t0630, t0630)
        var m = MorningActivationEngine.initialMetaForRing(alarm, t0630)
        assertEquals(MorningState.STAGE1_WAKE, s.status)
        val stage1Token = m.stageToken

        // Temporary mute at 06:30:25 does NOT mark complete and keeps 06:30 baseline
        s = MorningActivationEngine.onAlarmAcknowledged(s, t0630 + 25_000L, rewardId = 5L)
        assertEquals(MorningState.STAGE1_WAKE, s.status)
        assertEquals(25, s.responseDelaySec)

        // 06:34:59: still locked before 5 min elapsed
        assertFalse(MorningActivationEngine.canEnterStage2(s, t0634, settings.stage1UnlockMs, m))

        // 06:35:00: unlocks "I am awake" button
        assertTrue(MorningActivationEngine.canEnterStage2(s, t0635, settings.stage1UnlockMs, m))

        // 06:37: User confirms wake-up -> Stage 2 starts (deadline 07:07) and stageToken advances
        val s2Res = MorningActivationEngine.enterStage2WithMeta(s, m, settings, t0637)
        assertTrue(s2Res.changed)
        s = s2Res.session
        m = s2Res.meta
        assertEquals(MorningState.STAGE2_PREP, s.status)
        assertEquals(t0637, m.stage2StartWall)
        assertEquals(WakeCheckSubState.WAITING, m.wakeCheckState)
        assertTrue(m.stageToken > stage1Token)

        // Stale Stage 1 repeat ring with old token is ignored cleanly!
        val staleRing = MorningActivationEngine.onStage1RepeatRing(s, m, expectedStageToken = stage1Token, nowWall = localTime(6, 45))
        assertFalse(staleRing.changed)
        assertEquals(MorningState.STAGE2_PREP, staleRing.session.status)

        // Cannot finish Stage 2 before silent wake check is confirmed
        assertFalse(MorningActivationEngine.canCompleteStage2(s, m, settings, t0642))

        // 06:42: Silent wake check opens -> 06:43 user confirms awake
        val promptRes = MorningActivationEngine.triggerSilentWakeCheckPrompt(s, m, m.wakeCheckToken, settings, t0642)
        assertTrue(promptRes.changed)
        assertEquals(WakeCheckSubState.PROMPTED, promptRes.meta.wakeCheckState)
        val confirmRes = MorningActivationEngine.confirmSilentWakeCheck(promptRes.session, promptRes.meta, promptRes.meta.wakeCheckToken, t0643)
        assertTrue(confirmRes.changed)
        s = confirmRes.session
        m = confirmRes.meta
        assertEquals(WakeCheckSubState.CONFIRMED, m.wakeCheckState)

        // Late wake-check deadline after confirmation MUST NOT roll back!
        val lateDeadline = MorningActivationEngine.onSilentWakeCheckTimeout(s, m, m.wakeCheckToken, localTime(6, 47))
        assertFalse(lateDeadline.changed)
        assertEquals(MorningState.STAGE2_PREP, lateDeadline.session.status)

        // 06:55: Early completion of Stage 2 (before 07:07) -> Stage 3 starts
        val s3Res = MorningActivationEngine.enterStage3WithMeta(s, m, settings, t0655)
        assertTrue(s3Res.changed)
        s = s3Res.session
        m = s3Res.meta
        assertEquals(MorningState.STAGE3_REWARD, s.status)

        // Choose Option A (10 min instant reward) -> ends at 07:05 -> COMPLETED
        val optA = MorningActivationEngine.chooseInstantReward(s, m, settings, t0655)
        assertTrue(optA.changed)
        assertEquals(MorningRewardChoice.INSTANT, optA.meta.rewardChoice)
        assertEquals(t0705, optA.meta.instantRewardEndWall)
        val done = MorningActivationEngine.onActivationCompleted(optA.session, t0705)
        assertEquals(MorningState.COMPLETED, done.status)
        assertEquals(t0705, done.completedWall)
    }

    /** Acceptance #2 & #3: Extended sleep hidden on 1st ring, unlocked on 2nd ring, respects cap, resumes cleanly. */
    @Test
    fun extendedSleepHiddenOnFirstRingUnlockedOnSecondAndResumes() {
        val alarm = MorningAlarm(id = 12L, hour = 6, minute = 30)
        val settings = MorningV2Settings(latestWakeMinuteOfDay = 7 * 60 + 15) // Cap at 07:15
        val t0630 = localTime(6, 30)
        val t0635 = localTime(6, 35)

        var s = MorningActivationEngine.onRingStarted(alarm, t0630, t0630)
        var m = MorningActivationEngine.initialMetaForRing(alarm, t0630)

        // 1st ring: ordinary extended sleep MUST NOT be available
        assertFalse(m.canOfferExtendedSleep)
        assertNull(MorningActivationEngine.computeExtendedSleepTargetWall(s, m, 30, settings, t0630))
        assertFalse(MorningActivationEngine.enterExtendedSleep(s, m, 30, settings, t0630).changed)

        // 2nd ring at 06:35 -> unlocks extended sleep
        val ring2 = MorningActivationEngine.onStage1RepeatRing(s, m, m.stageToken, t0635)
        assertTrue(ring2.changed)
        s = ring2.session
        m = ring2.meta
        assertEquals(2, m.ringCount)
        assertTrue(m.canOfferExtendedSleep)

        // Request 60m at 06:35 -> capped cleanly to 07:15!
        val cappedTarget = MorningActivationEngine.computeExtendedSleepTargetWall(s, m, 60, settings, t0635)
        assertEquals(localTime(7, 15), cappedTarget)

        val ext = MorningActivationEngine.enterExtendedSleep(s, m, 60, settings, t0635)
        assertTrue(ext.changed)
        assertEquals(MorningState.EXTENDED_SLEEP, ext.session.status)
        assertEquals(1, ext.meta.extendedSleepCount)
        assertEquals(40, ext.meta.extendedSleepTotalMin)
        assertEquals(localTime(7, 15), ext.meta.extendedSleepResumeWall)

        // Resume at 07:15 -> fresh STAGE1_WAKE with ringCount = 1 and new stageToken
        val resumed = MorningActivationEngine.resumeFromExtendedSleep(ext.session, ext.meta, ext.meta.stageToken, localTime(7, 15))
        assertTrue(resumed.changed)
        assertEquals(MorningState.STAGE1_WAKE, resumed.session.status)
        assertEquals(1, resumed.meta.ringCount)
        assertEquals(localTime(7, 15), resumed.meta.stage1StartWall)
        assertTrue(resumed.meta.stageToken > ext.meta.stageToken)
    }

    /** Acceptance #4 & #6: Stage 2 silent wake-check timeout rolls back to Stage 1; repeated cycles stay token-isolated. */
    @Test
    fun silentWakeCheckTimeoutRollsBackToStage1AndPreventsDuplicateTriggers() {
        val alarm = MorningAlarm(id = 13L, hour = 6, minute = 30)
        val settings = MorningV2Settings()
        val t0 = localTime(6, 30)
        val tS2 = localTime(6, 36)
        val tPrompt = localTime(6, 41)
        val tDeadline = localTime(6, 46)

        val s1 = MorningActivationEngine.onRingStarted(alarm, t0, t0)
        val m1 = MorningActivationEngine.initialMetaForRing(alarm, t0)
        val s2 = MorningActivationEngine.enterStage2WithMeta(s1, m1, settings, tS2)
        val firstCheckToken = s2.meta.wakeCheckToken

        val prompted = MorningActivationEngine.triggerSilentWakeCheckPrompt(s2.session, s2.meta, firstCheckToken, settings, tPrompt)
        assertEquals(WakeCheckSubState.PROMPTED, prompted.meta.wakeCheckState)

        // Unanswered deadline at 06:46 rolls back cleanly to STAGE1_WAKE
        val rolledBack = MorningActivationEngine.onSilentWakeCheckTimeout(prompted.session, prompted.meta, firstCheckToken, tDeadline)
        assertTrue(rolledBack.changed)
        assertEquals(MorningState.STAGE1_WAKE, rolledBack.session.status)
        assertEquals(1, rolledBack.meta.wakeCheckTimeouts)
        assertTrue(rolledBack.meta.stageToken > prompted.meta.stageToken)

        // Re-entering Stage 2 creates a brand-new wakeCheckToken; old check token cannot trigger again
        val reS2 = MorningActivationEngine.enterStage2WithMeta(rolledBack.session, rolledBack.meta, settings, localTime(6, 52))
        assertTrue(reS2.changed)
        assertTrue(reS2.meta.wakeCheckToken > firstCheckToken)
        assertFalse(
            MorningActivationEngine.onSilentWakeCheckTimeout(reS2.session, reS2.meta, firstCheckToken, localTime(6, 58)).changed
        )
    }

    /** Acceptance #7: Optional 30-step hardware counter auto-passes; unavailable/rebooted counter falls back to manual. */
    @Test
    fun stepCounterProofAutoPassesAndFallsBackCleanlyWhenUnavailableOrRebooted() {
        assertTrue(MorningActivationEngine.stepsSatisfyWakeCheck(baseline = 1000f, currentSteps = 1030f, requiredSteps = 30))
        assertFalse(MorningActivationEngine.stepsSatisfyWakeCheck(baseline = 1000f, currentSteps = 1029f, requiredSteps = 30))
        assertFalse(MorningActivationEngine.stepsSatisfyWakeCheck(baseline = -1f, currentSteps = 1050f, requiredSteps = 30))
        assertFalse(MorningActivationEngine.stepsSatisfyWakeCheck(baseline = 1000f, currentSteps = null, requiredSteps = 30))
        // Device rebooted (counter reset below baseline) -> must NOT false-pass
        assertFalse(MorningActivationEngine.stepsSatisfyWakeCheck(baseline = 1000f, currentSteps = 50f, requiredSteps = 30))

        // Sensor unavailable (null) -> opens manual PROMPTED window cleanly
        val alarm = MorningAlarm(id = 14L, hour = 7, minute = 0)
        val settings = MorningV2Settings(stepProofEnabled = true, stepProofThreshold = 30)
        val s2 = MorningActivationEngine.enterStage2WithMeta(
            MorningActivationEngine.onRingStarted(alarm, localTime(7, 0), localTime(7, 0)),
            MorningActivationEngine.initialMetaForRing(alarm, localTime(7, 0)),
            settings,
            localTime(7, 6),
            stepBaseline = -1f
        )
        val prompted = MorningActivationEngine.triggerSilentWakeCheckPrompt(
            s2.session, s2.meta, s2.meta.wakeCheckToken, settings, localTime(7, 11), currentSteps = null
        )
        assertEquals(WakeCheckSubState.PROMPTED, prompted.meta.wakeCheckState)
        val manual = MorningActivationEngine.confirmSilentWakeCheck(prompted.session, prompted.meta, prompted.meta.wakeCheckToken, localTime(7, 12))
        assertTrue(manual.changed)
        assertEquals(WakeCheckSubState.CONFIRMED, manual.meta.wakeCheckState)
    }

    /** Acceptance #8: Settings, RuntimeMeta & StatRecord round-trip cleanly across process death / reboot. */
    @Test
    fun persistedSettingsMetaAndStatsSurviveEncodingRoundTrip() {
        val cfg = MorningV2Settings(
            stage1WindowMin = 20,
            stage1UnlockMin = 6,
            stage2DurationMin = 25,
            wakeCheckDelayMin = 4,
            wakeCheckWindowMin = 6,
            stepProofEnabled = true,
            stepProofThreshold = 35,
            stage3InstantMin = 12,
            morningRestrictEndMinute = 11 * 60 + 30,
            latestWakeMinuteOfDay = 8 * 60 + 30,
            customPrepItems = "铺床,吃早饭"
        )
        assertEquals(cfg, MorningV2Settings.decode(cfg.encode()))

        val meta = MorningRuntimeMeta(
            stageToken = 7L,
            wakeCheckToken = 3L,
            stage1StartWall = 1000L,
            stage2StartWall = 2000L,
            stage3StartWall = 3000L,
            ringCount = 2,
            extendedSleepCount = 1,
            extendedSleepTotalMin = 30,
            extendedSleepResumeWall = 4000L,
            wakeCheckState = WakeCheckSubState.PROMPTED,
            wakeCheckPromptWall = 2300L,
            wakeCheckDeadlineWall = 2600L,
            wakeCheckTimeouts = 1,
            stepBaseline = 1234.5f,
            rewardChoice = MorningRewardChoice.INSTANT,
            instantRewardEndWall = 3600L,
            selectedJoy = MorningJoyPreset.MARKET.code,
            bonusClaimed = false,
            bonusAfterNoon = true,
            wakefulnessRating = 4
        )
        assertEquals(meta, MorningRuntimeMeta.decode(meta.encode()))

        val stat = MorningStatRecord("2026-11-27", 99L, 420, 1, 15, 1, true, 18, 2, 4)
        assertEquals(stat, MorningStatRecord.decode(stat.encode()))
        val summary = MorningStatsAggregator.summarize(listOf(stat), 7, "2026-11-21")
        assertEquals(100, summary.completionRatePercent)
        assertEquals(7, summary.avgWakeConfirmMin)
        assertEquals(18, summary.avgPrepMin)
        assertEquals(1, summary.totalExtendedCount)
        assertEquals(1, summary.totalWakeCheckTimeouts)
    }

    /** Acceptance #9: Stage 3 reward choices A / B / C are strictly mutually exclusive (no double-claim). */
    @Test
    fun stage3RewardChoicesAreStrictlyMutuallyExclusive() {
        val settings = MorningV2Settings()
        val baseSession = MorningSessionRecord(
            id = 50L, dayKey = "2026-11-27", alarmId = 1L,
            scheduledWall = 1000L, ringStartedWall = 1000L, status = MorningState.STAGE3_REWARD
        )
        val baseMeta = MorningRuntimeMeta(stageToken = 4L)

        // Pick A (Instant) -> cannot also pick B (Delayed) or C (Skip)
        val afterA = MorningActivationEngine.chooseInstantReward(baseSession, baseMeta, settings, 5000L)
        assertTrue(afterA.changed)
        assertFalse(MorningActivationEngine.chooseDelayedRewardAndComplete(afterA.session, afterA.meta, 6000L).changed)
        assertFalse(MorningActivationEngine.chooseSkipRewardAndComplete(afterA.session, afterA.meta, 6000L).changed)

        // Pick B (Delayed) -> immediately COMPLETED and cannot pick A or B again
        val afterB = MorningActivationEngine.chooseDelayedRewardAndComplete(baseSession, baseMeta, 5000L)
        assertTrue(afterB.changed)
        assertEquals(MorningState.COMPLETED, afterB.session.status)
        assertFalse(MorningActivationEngine.chooseDelayedRewardAndComplete(afterB.session, afterB.meta, 6000L).changed)
        assertFalse(MorningActivationEngine.chooseInstantReward(afterB.session, afterB.meta, settings, 6000L).changed)
    }

    /** Acceptance #10: Emergency exit & back-to-bed are always available and NEVER record COMPLETED. */
    @Test
    fun emergencyExitAndReturnedToBedNeverRecordCompleted() {
        val alarm = MorningAlarm(id = 15L, hour = 7, minute = 0)
        val t0 = localTime(7, 0)
        val active = MorningActivationEngine.onRingStarted(alarm, t0, t0)

        val emergency = MorningActivationEngine.onEmergencyExit(active, t0 + 120_000L)
        assertEquals(MorningState.UNVERIFIED, emergency.status)
        assertNotEquals(MorningState.COMPLETED, emergency.status)
        assertNull(emergency.completedWall)

        val back = MorningActivationEngine.onReturnedToBed(active, t0 + 120_000L)
        assertEquals(MorningState.RETURNED_TO_BED, back.status)
        assertNotEquals(MorningState.COMPLETED, back.status)

        // Subsequent alarms auto-skip once in Stage 2/3/Extended Sleep or completed within 3h
        assertTrue(MorningActivationEngine.shouldSkipSubsequentAlarm(t0, null, t0 + 15 * 60_000L))
        assertTrue(MorningActivationEngine.shouldSkipSubsequentAlarm(null, MorningState.EXTENDED_SLEEP, t0))
        assertFalse(MorningActivationEngine.shouldSkipSubsequentAlarm(null, MorningState.STAGE1_WAKE, t0))
    }

    @Test
    fun sleepDeprivationGuardPreventsExtremeEarlyWakeReward() {
        assertFalse(MorningActivationEngine.eligibleForMorningReward(240))
        assertTrue(MorningActivationEngine.eligibleForMorningReward(300))
        assertTrue(MorningActivationEngine.eligibleForMorningReward(480))
    }

    @Test
    fun lateMuteCannotReopenAnotherStageOrTerminalSession() {
        val first = MorningActivationEngine.onRingStarted(MorningAlarm(), 100L, 100L)
        for (state in listOf(MorningState.STAGE2_PREP, MorningState.EXTENDED_SLEEP,
            MorningState.STAGE3_REWARD, MorningState.COMPLETED, MorningState.UNVERIFIED)) {
            val before = first.copy(status = state)
            assertEquals(before, MorningActivationEngine.onAlarmAcknowledged(before, 200L))
        }
    }

    @Test
    fun wakeConfirmationRequiresMatchingTokenAndAnUnexpiredWindow() {
        val session = MorningActivationEngine.onRingStarted(MorningAlarm(), 1L, 1L)
            .copy(status = MorningState.STAGE2_PREP)
        val meta = MorningRuntimeMeta(wakeCheckToken = 4L, wakeCheckDeadlineWall = 500L,
            wakeCheckState = WakeCheckSubState.PROMPTED)
        for (token in listOf(0L, -1L, 3L)) {
            assertFalse(MorningActivationEngine.confirmSilentWakeCheck(session, meta, token, 499L).changed)
        }
        assertTrue(MorningActivationEngine.confirmSilentWakeCheck(session, meta, 4L, 499L).changed)
        assertFalse(MorningActivationEngine.confirmSilentWakeCheck(session, meta, 4L, 500L).changed)
        assertFalse(MorningActivationEngine.confirmSilentWakeCheck(session, meta, 4L, 501L).changed)
    }

    @Test
    fun customPrepTextPreservesPipesCommasAndLineBreaks() {
        val cfg = MorningV2Settings(customPrepItems = "A|B,C\nD")
        assertEquals(cfg, MorningV2Settings.decode(cfg.encode()))
    }

    @Test
    fun stageOneRestoresOnlyTheOutstandingRingAtTheBoundary() {
        val cfg = MorningV2Settings()
        val start = 1000L
        val unlock = start + cfg.stage1UnlockMs
        val end = start + cfg.stage1WindowMs
        for (now in listOf(unlock - 1, unlock, unlock + 1)) {
            assertEquals(maxOf(now, unlock) to end, MorningActivationEngine.stage1RingTimes(start, cfg, now, false))
        }
        assertEquals(null to end, MorningActivationEngine.stage1RingTimes(start, cfg, unlock, true))
        assertEquals(null to end + 42L, MorningActivationEngine.stage1RingTimes(start, cfg, end + 42L, false))
    }

    @Test
    fun instantRewardRestoresBeforeAtAndAfterDeadlineOnlyForChoiceA() {
        val meta = MorningRuntimeMeta(rewardChoice = MorningRewardChoice.INSTANT, instantRewardEndWall = 550L)
        for (now in listOf(549L, 550L, 551L, 999_999L)) {
            assertEquals(maxOf(now, 550L), MorningActivationEngine.instantRewardTimerWall(meta, now))
            assertNull(MorningActivationEngine.instantRewardTimerWall(meta.copy(rewardChoice = MorningRewardChoice.NONE), now))
        }
    }

    @Test
    fun secondRingIsConsumedOnceAndLegacyRingCountsRecover() {
        val session = MorningActivationEngine.onRingStarted(MorningAlarm(), 100L, 100L)
        val meta = MorningRuntimeMeta()
        val ring = MorningActivationEngine.onStage1RepeatRing(session, meta, meta.stageToken, 500L, secondRingOnly = true)
        assertTrue(ring.changed)
        val restored = MorningRuntimeMeta.decode(ring.meta.encode())
        assertFalse(MorningActivationEngine.onStage1RepeatRing(ring.session, restored, restored.stageToken, 501L, true).changed)
        val legacy = MorningRuntimeMeta.decode(ring.meta.encode().substringBeforeLast('|'))
        assertTrue(legacy.stage1ReminderDelivered)
        assertTrue(MorningActivationEngine.onStage1RepeatRing(ring.session, restored, restored.stageToken, 900L).changed)
    }

    @Test
    fun bonusRecoveryAcceptsOnlyOutstandingQuotaForTheLocalDay() {
        val noon = localTime(12, 0)
        val day = DayKeys.dayKey(noon)
        val raw = "$day|$noon|15|0"
        val pending = PendingMorningBonus(day, noon, 15)
        assertEquals(pending, PendingMorningBonus.forToday(raw, noon - 1L))
        assertEquals(pending, PendingMorningBonus.forToday(raw, noon + 1L))
        for (invalid in listOf("$day|$noon|15|1", "$day|$noon|0|0", "$day|broken|15|0", null)) {
            assertNull(PendingMorningBonus.forToday(invalid, noon))
        }
        assertNull(PendingMorningBonus.forToday(raw, noon + 86_400_000L))
    }

    @Test
    fun earlyDeadlineDeliveryDoesNotRollBackAnOpenPrompt() {
        val session = MorningActivationEngine.onRingStarted(MorningAlarm(), 1L, 1L)
            .copy(status = MorningState.STAGE2_PREP)
        val meta = MorningRuntimeMeta(wakeCheckToken = 4L, wakeCheckState = WakeCheckSubState.PROMPTED,
            wakeCheckDeadlineWall = 500L)
        assertFalse(MorningActivationEngine.onSilentWakeCheckTimeout(session, meta, 4L, 499L).changed)
        assertTrue(MorningActivationEngine.onSilentWakeCheckTimeout(session, meta, 4L, 500L).changed)
    }

    @Test
    fun invalidStepCounterValuesCannotConfirmWakefulness() {
        assertFalse(MorningActivationEngine.stepsSatisfyWakeCheck(100f, Float.POSITIVE_INFINITY))
        assertFalse(MorningActivationEngine.stepsSatisfyWakeCheck(Float.NaN, 150f))
    }

    @Test
    fun cleanupKeepsOnlyTimersForTheCommittedStage() {
        val meta = MorningRuntimeMeta(wakeCheckState = WakeCheckSubState.PROMPTED)
        val deadline = MindstopAlarmReceiver.ACTION_WAKE_CHECK_DEADLINE
        val resume = MindstopAlarmReceiver.ACTION_EXTENDED_SLEEP_RESUME
        val end = MindstopAlarmReceiver.ACTION_STAGE3_END
        assertTrue(MindstopAlarmScheduler.keepStageTimer(deadline, MorningState.STAGE2_PREP, meta))
        assertFalse(MindstopAlarmScheduler.keepStageTimer(deadline, MorningState.STAGE2_PREP,
            meta.copy(wakeCheckState = WakeCheckSubState.CONFIRMED)))
        assertTrue(MindstopAlarmScheduler.keepStageTimer(resume, MorningState.EXTENDED_SLEEP, meta))
        assertFalse(MindstopAlarmScheduler.keepStageTimer(deadline, MorningState.EXTENDED_SLEEP, meta))
        assertTrue(MindstopAlarmScheduler.keepStageTimer(end, MorningState.STAGE3_REWARD,
            meta.copy(rewardChoice = MorningRewardChoice.INSTANT)))
        assertFalse(MindstopAlarmScheduler.keepStageTimer(end, MorningState.COMPLETED, meta))
    }
}
