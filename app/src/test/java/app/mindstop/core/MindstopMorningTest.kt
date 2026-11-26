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
    fun dismissingAlarmEntersActivationNotCompletedAndMotionNeverAutoCompletes() {
        val alarm = MorningAlarm(id = 10L, hour = 7, minute = 0)
        val t0 = 1_760_000_000_000L
        val ringing = MorningActivationEngine.onRingStarted(alarm, t0, t0)
        assertEquals(MorningState.RINGING, ringing.status)

        val ack = MorningActivationEngine.onAlarmAcknowledged(ringing, t0 + 25_000L, rewardId = 5L)
        assertEquals(MorningState.ACTIVATION, ack.status)
        assertNotEquals(MorningState.COMPLETED, ack.status)
        assertEquals(25, ack.responseDelaySec)

        // Auxiliary motion hint must NOT auto-complete wake-up
        val withMotion = MorningActivationEngine.onMotionHint(ack, t0 + 120_000L)
        assertTrue(withMotion.motionHintSeen)
        assertEquals(MorningState.ACTIVATION, withMotion.status)

        val checked5m = MorningActivationEngine.onCheck5mConfirmed(withMotion, t0 + 300_000L)
        assertTrue(checked5m.check5mConfirmed)

        val done = MorningActivationEngine.onActivationCompleted(checked5m, t0 + 900_000L)
        assertEquals(MorningState.COMPLETED, done.status)
        assertEquals(t0 + 900_000L, done.completedWall)
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
