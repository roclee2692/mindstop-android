package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MindstopMorningTest {

    @Test
    fun nextTriggerWallRespectsOneShotAndWeekdayBitmask() {
        // Thursday 2026-11-26 08:00 local
        val thuMorning = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 26, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // One-shot 07:00 -> tomorrow Friday 07:00
        val oneshot = MorningAlarm(id = 1L, hour = 7, minute = 0, repeatDaysMask = MorningAlarm.ONCE_MASK)
        val nextOne = Calendar.getInstance().apply { timeInMillis = MorningAlarmPlanner.nextTriggerWall(oneshot, thuMorning) }
        assertEquals(27, nextOne.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, nextOne.get(Calendar.HOUR_OF_DAY))

        // Weekend-only 08:30 -> Saturday Nov 28 08:30
        val wknd = MorningAlarm(id = 2L, hour = 8, minute = 30, repeatDaysMask = MorningAlarm.WEEKEND_MASK)
        val nextWknd = Calendar.getInstance().apply { timeInMillis = MorningAlarmPlanner.nextTriggerWall(wknd, thuMorning) }
        assertEquals(Calendar.SATURDAY, nextWknd.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, nextWknd.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, nextWknd.get(Calendar.MINUTE))
    }

    @Test
    fun strictSequentialThreeStageFlowAndAntiCheatGate() {
        val alarm = MorningAlarm(id = 10L, hour = 7, minute = 0)
        val t0 = 1_760_000_000_000L
        val ringing = MorningActivationEngine.onRingStarted(alarm, t0, t0)
        assertEquals(MorningState.RINGING, ringing.status)

        // Dismissing alarm enters STAGE1_WAKE, never COMPLETED
        val stage1 = MorningActivationEngine.onAlarmAcknowledged(ringing, t0 + 25_000L, rewardId = 5L)
        assertEquals(MorningState.STAGE1_WAKE, stage1.status)
        assertNotEquals(MorningState.COMPLETED, stage1.status)
        assertEquals(25, stage1.responseDelaySec)

        // Cannot cheat by jumping straight to COMPLETED or STAGE2_PREP before 5 min elapsed
        assertFalse(MorningActivationEngine.canEnterStage2(stage1, t0 + 25_000L + 299_000L))
        assertEquals(MorningState.STAGE1_WAKE, MorningActivationEngine.enterStage2(stage1, t0 + 25_000L + 299_000L).status)
        assertEquals(MorningState.STAGE1_WAKE, MorningActivationEngine.onActivationCompleted(stage1, t0 + 60_000L).status)

        // Auxiliary motion hint must NOT auto-complete wake-up
        val withMotion = MorningActivationEngine.onMotionHint(stage1, t0 + 120_000L)
        assertTrue(withMotion.motionHintSeen)
        assertEquals(MorningState.STAGE1_WAKE, withMotion.status)

        // At 5m00s, Stage 2 unlocks cleanly
        val tStage2 = t0 + 25_000L + MorningActivationEngine.STAGE1_UNLOCK_MS
        assertTrue(MorningActivationEngine.canEnterStage2(withMotion, tStage2))
        val stage2 = MorningActivationEngine.enterStage2(withMotion, tStage2)
        assertEquals(MorningState.STAGE2_PREP, stage2.status)
        assertTrue(stage2.check5mConfirmed)

        // Cannot skip Stage 3 directly from Stage 2
        assertEquals(MorningState.STAGE2_PREP, MorningActivationEngine.onActivationCompleted(stage2, tStage2 + 60_000L).status)

        // Enter Stage 3 after finishing wash-up & morning prep
        val tStage3 = tStage2 + 20 * 60_000L
        val stage3 = MorningActivationEngine.enterStage3(stage2, tStage3)
        assertEquals(MorningState.STAGE3_REWARD, stage3.status)

        // Complete all 3 stages
        val tDone = tStage3 + 10 * 60_000L
        val done = MorningActivationEngine.onActivationCompleted(stage3, tDone)
        assertEquals(MorningState.COMPLETED, done.status)
        assertEquals(tDone, done.completedWall)
    }

    @Test
    fun subsequentAlarmsAutoSkipOnceAwakeOrInStage2Or3() {
        val tCompleted = 1_760_000_000_000L

        // Within 3h after COMPLETED -> skip subsequent alarm
        assertTrue(
            MorningActivationEngine.shouldSkipSubsequentAlarm(
                latestCompletedWall = tCompleted,
                activeState = null,
                nowWall = tCompleted + 15 * 60_000L
            )
        )
        // After 3h window -> do NOT skip (e.g. afternoon nap alarm)
        assertFalse(
            MorningActivationEngine.shouldSkipSubsequentAlarm(
                latestCompletedWall = tCompleted,
                activeState = null,
                nowWall = tCompleted + MorningActivationEngine.SKIP_REPEAT_ALARM_WINDOW_MS + 1_000L
            )
        )
        // Already actively washing up (Stage 2) or enjoying reward (Stage 3) -> skip backup alarm
        assertTrue(
            MorningActivationEngine.shouldSkipSubsequentAlarm(
                latestCompletedWall = null,
                activeState = MorningState.STAGE2_PREP,
                nowWall = tCompleted
            )
        )
        assertTrue(
            MorningActivationEngine.shouldSkipSubsequentAlarm(
                latestCompletedWall = null,
                activeState = MorningState.STAGE3_REWARD,
                nowWall = tCompleted
            )
        )
        // Still in Stage 1 (could drift back to bed!) -> do NOT skip backup alarm
        assertFalse(
            MorningActivationEngine.shouldSkipSubsequentAlarm(
                latestCompletedWall = null,
                activeState = MorningState.STAGE1_WAKE,
                nowWall = tCompleted
            )
        )
    }

    @Test
    fun returnedToBedAndUnverifiedTimeoutSettleHonestly() {
        val alarm = MorningAlarm(id = 11L, hour = 7, minute = 0)
        val t0 = 1_760_000_000_000L
        val active = MorningActivationEngine.onAlarmAcknowledged(
            MorningActivationEngine.onRingStarted(alarm, t0, t0),
            t0 + 10_000L
        )
        val back = MorningActivationEngine.onReturnedToBed(active, t0 + 600_000L)
        assertEquals(MorningState.RETURNED_TO_BED, back.status)

        val expired = MorningActivationEngine.settleUnverifiedIfExpired(
            active,
            t0 + 10_000L + MorningActivationEngine.UNVERIFIED_TIMEOUT_MS
        )
        assertEquals(MorningState.UNVERIFIED, expired.status)
    }

    @Test
    fun sleepDeprivationGuardPreventsExtremeEarlyWakeReward() {
        assertFalse(MorningActivationEngine.eligibleForMorningReward(240)) // 4h -> not rewarded
        assertTrue(MorningActivationEngine.eligibleForMorningReward(300))  // 5h -> allowed
        assertTrue(MorningActivationEngine.eligibleForMorningReward(480))  // 8h -> allowed
    }
}
