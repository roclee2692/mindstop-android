package app.mindstop.core

import java.util.Calendar

/**
 * Pure Kotlin state machine & scheduler math for Module 2: Morning Activation (v0.3 SPEC §9–§14).
 *
 * Alarm architecture inspired by Purgator/Upupup (GPL-3.0) and FossifyOrg/Clock (GPL-3.0).
 * Strict sequential 3-stage state flow:
 *   SCHEDULED -> RINGING -> STAGE1_WAKE (15m window, 5m unlock gate)
 *     -> STAGE2_PREP (30m wash/prep) -> STAGE3_REWARD (morning joy + optional bonus)
 *     -> COMPLETED | RETURNED_TO_BED | UNVERIFIED
 */
enum class MorningState(val code: String) {
    SCHEDULED("SCHEDULED"),
    RINGING("RINGING"),
    ACKNOWLEDGED("ACKNOWLEDGED"),
    /** Legacy v0.3.0-rc state kept for backward compatibility with existing DB rows. */
    ACTIVATION("ACTIVATION"),
    STAGE1_WAKE("STAGE1_WAKE"),
    STAGE2_PREP("STAGE2_PREP"),
    STAGE3_REWARD("STAGE3_REWARD"),
    COMPLETED("COMPLETED"),
    RETURNED_TO_BED("RETURNED_TO_BED"),
    UNVERIFIED("UNVERIFIED");

    val isInActivation: Boolean
        get() = this == ACTIVATION || this == STAGE1_WAKE || this == STAGE2_PREP || this == STAGE3_REWARD

    val isStage1: Boolean
        get() = this == STAGE1_WAKE || this == ACTIVATION

    val isTerminal: Boolean
        get() = this == COMPLETED || this == RETURNED_TO_BED || this == UNVERIFIED

    companion object {
        fun fromCode(raw: String?): MorningState =
            entries.firstOrNull { it.code == raw } ?: UNVERIFIED
    }
}

/** Built-in gentle body-wake presets (v0.3 §11 Stage 2). */
enum class BodyWakeActivity(val code: String) {
    WARM_CLOTHES("warm_clothes"),
    WASH_FACE("wash_face"),
    DRINK_WATER("drink_water"),
    TURN_ON_LIGHT("turn_on_light"),
    LIGHT_STRETCH("light_stretch");

    companion object {
        fun decodeSet(raw: String?): Set<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

        fun encodeSet(items: Set<String>): String = items.sorted().joinToString(",")

        val DEFAULT_CODES = setOf(WARM_CLOTHES.code, WASH_FACE.code, DRINK_WATER.code, TURN_ON_LIGHT.code)
    }
}

/** Built-in morning expectation / joy presets (v0.3 §11 Stage 3). */
enum class MorningJoyPreset(val code: String) {
    MUSIC("music"),
    AUDIOBOOK("audiobook"),
    BREAKFAST("breakfast"),
    SHORT_GAME("short_game"),
    MARKET("market"),
    GROWTH_GARDEN("growth_garden"),
    CUSTOM("custom");

    companion object {
        fun fromCode(raw: String?): MorningJoyPreset =
            entries.firstOrNull { it.code == raw } ?: MUSIC
    }
}

data class MorningAlarm(
    val id: Long = 0L,
    val label: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    /** Bitmask: bit 0 = Mon .. bit 6 = Sun. 0 = one-shot alarm. */
    val repeatDaysMask: Int = WORKDAYS_MASK,
    val enabled: Boolean = true,
    val ringtoneUri: String = "",
    /** Gradual volume ramp duration in seconds (0 = instant). */
    val gradualVolumeSeconds: Int = 30,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 9,
    val dismissMethod: WakeupConfirmMethod = WakeupConfirmMethod.MANUAL,
    /** Required count for MATH (questions) or STEPS (steps). */
    val challengeCount: Int = 2,
    /** Optional expected NFC tag id or QR text when dismissMethod is NFC/QR. */
    val targetTagOrQr: String = "",
    /** Optional gentle backup reminder at end of activation window if still unconfirmed. */
    val backupReminderEnabled: Boolean = true,
    val bodyWakeMinutes: Int = 5,
    val morningJoyMinutes: Int = 10,
    val bodyActivities: Set<String> = BodyWakeActivity.DEFAULT_CODES,
    val joyActivity: String = MorningJoyPreset.MUSIC.code,
    val joyAllowanceMinutes: Int = 10
) {
    val totalActivationMinutes: Int
        get() = (bodyWakeMinutes + morningJoyMinutes).coerceIn(5, 60)

    val minutesOfDay: Int
        get() = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)

    companion object {
        const val MON = 1 shl 0
        const val TUE = 1 shl 1
        const val WED = 1 shl 2
        const val THU = 1 shl 3
        const val FRI = 1 shl 4
        const val SAT = 1 shl 5
        const val SUN = 1 shl 6

        const val ONCE_MASK = 0
        const val WORKDAYS_MASK = MON or TUE or WED or THU or FRI
        const val WEEKEND_MASK = SAT or SUN
        const val EVERYDAY_MASK = WORKDAYS_MASK or WEEKEND_MASK
    }
}

data class MorningSessionRecord(
    val id: Long = 0L,
    val dayKey: String,
    val alarmId: Long,
    val scheduledWall: Long,
    val ringStartedWall: Long,
    val acknowledgedWall: Long? = null,
    val completedWall: Long? = null,
    val status: MorningState = MorningState.RINGING,
    val responseDelaySec: Int = 0,
    val check5mConfirmed: Boolean = false,
    val motionHintSeen: Boolean = false,
    val joyActivity: String = "",
    val rewardId: Long? = null,
    val updatedWall: Long = ringStartedWall
)

object MorningAlarmPlanner {

    /**
     * Computes the next exact trigger wall timestamp strictly after [nowWall].
     * Supports both one-shot alarms (repeatDaysMask == 0) and arbitrary day-of-week bitmasks.
     */
    fun nextTriggerWall(alarm: MorningAlarm, nowWall: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowWall
            set(Calendar.HOUR_OF_DAY, alarm.hour.coerceIn(0, 23))
            set(Calendar.MINUTE, alarm.minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowWall) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        if (alarm.repeatDaysMask == MorningAlarm.ONCE_MASK) {
            return cal.timeInMillis
        }
        for (i in 0 until 8) {
            val bit = calendarDayToBit(cal.get(Calendar.DAY_OF_WEEK))
            if ((alarm.repeatDaysMask and bit) != 0) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    fun calendarDayToBit(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
        Calendar.MONDAY -> MorningAlarm.MON
        Calendar.TUESDAY -> MorningAlarm.TUE
        Calendar.WEDNESDAY -> MorningAlarm.WED
        Calendar.THURSDAY -> MorningAlarm.THU
        Calendar.FRIDAY -> MorningAlarm.FRI
        Calendar.SATURDAY -> MorningAlarm.SAT
        Calendar.SUNDAY -> MorningAlarm.SUN
        else -> MorningAlarm.MON
    }
}

/**
 * Pure sequential 3-stage Morning Activation state machine (v0.3 SPEC §11–§13).
 *
 * 1. Dismissing an alarm NEVER marks wake-up as completed; it enters [MorningState.STAGE1_WAKE].
 * 2. Stage 1 runs a 15-minute out-of-bed window with a 5-minute anti-cheat gate before Stage 2
 *    unlocks. Failing to enter Stage 2 within 15 minutes triggers a backup alarm ring.
 * 3. Stage 2 runs a 30-minute wash-up & morning prep countdown.
 * 4. Stage 3 unlocks the morning expectation / joy countdown and an optional +15 min quota reward
 *    (which defaults to afternoon-only after 12:00 so mornings stay calm).
 * 5. Once Stage 2/3 is active or COMPLETED within the same morning window (3h), subsequent backup
 *    alarms are skipped automatically while preserving repeating schedules for the next day.
 * 6. Extreme sleep deprivation (< 5 hours planned sleep opportunity) never earns early-wake rewards.
 */
object MorningActivationEngine {

    /** Minimum planned sleep opportunity (300m = 5h) required to qualify for morning wake reward. */
    const val MIN_HEALTHY_SLEEP_OPPORTUNITY_MIN = 300

    /** Anti-cheat lock before Stage 2 button unlocks (default 5 min). */
    const val STAGE1_UNLOCK_MS = 5 * 60_000L

    /** Out-of-bed window before backup alarm rings again if Stage 2 was not started (default 15 min). */
    const val STAGE1_WINDOW_MS = 15 * 60_000L

    /** Stage 2 wash-up and morning preparation duration (default 30 min). */
    const val STAGE2_DURATION_MS = 30 * 60_000L

    /** Default Stage 3 morning joy duration (10 min). */
    const val STAGE3_DEFAULT_MS = 10 * 60_000L

    /** Optional bonus daily entertainment quota minutes offered in Stage 3. */
    const val BONUS_QUOTA_MINUTES = 15

    /** Same-morning window (3h) in which subsequent scheduled alarms auto-skip once awake. */
    const val SKIP_REPEAT_ALARM_WINDOW_MS = 3 * 60 * 60_000L

    /** Timeout after which an unfinished activation settles honestly as UNVERIFIED (default 120m). */
    const val UNVERIFIED_TIMEOUT_MS = 120 * 60_000L

    fun onRingStarted(alarm: MorningAlarm, scheduledWall: Long, nowWall: Long): MorningSessionRecord =
        MorningSessionRecord(
            dayKey = DayKeys.dayKey(nowWall),
            alarmId = alarm.id,
            scheduledWall = scheduledWall,
            ringStartedWall = nowWall,
            status = MorningState.RINGING,
            joyActivity = alarm.joyActivity,
            updatedWall = nowWall
        )

    /**
     * User dismissed the alarm: transitions RINGING -> STAGE1_WAKE and computes response delay.
     * Never marks wake-up as COMPLETED yet.
     */
    fun onAlarmAcknowledged(
        session: MorningSessionRecord,
        nowWall: Long,
        rewardId: Long? = null
    ): MorningSessionRecord {
        val delaySec = ((nowWall - session.ringStartedWall).coerceAtLeast(0L) / 1_000L).toInt()
        return session.copy(
            acknowledgedWall = nowWall,
            status = MorningState.STAGE1_WAKE,
            responseDelaySec = if (session.acknowledgedWall == null) delaySec else session.responseDelaySec,
            rewardId = rewardId ?: session.rewardId,
            updatedWall = nowWall
        )
    }

    fun stage1UnlockRemainingMs(
        session: MorningSessionRecord,
        nowWall: Long,
        unlockMs: Long = STAGE1_UNLOCK_MS
    ): Long {
        if (!session.status.isStage1) return 0L
        val start = session.acknowledgedWall ?: session.ringStartedWall
        return (start + unlockMs - nowWall).coerceAtLeast(0L)
    }

    fun stage1WindowRemainingMs(
        session: MorningSessionRecord,
        nowWall: Long,
        windowMs: Long = STAGE1_WINDOW_MS
    ): Long {
        if (!session.status.isStage1) return 0L
        val start = session.acknowledgedWall ?: session.ringStartedWall
        return (start + windowMs - nowWall).coerceAtLeast(0L)
    }

    fun canEnterStage2(
        session: MorningSessionRecord,
        nowWall: Long,
        unlockMs: Long = STAGE1_UNLOCK_MS
    ): Boolean = session.status.isStage1 && stage1UnlockRemainingMs(session, nowWall, unlockMs) == 0L

    /** Strict sequential transition: STAGE1_WAKE -> STAGE2_PREP once the 5-minute gate passes. */
    fun enterStage2(
        session: MorningSessionRecord,
        nowWall: Long,
        unlockMs: Long = STAGE1_UNLOCK_MS
    ): MorningSessionRecord {
        if (!canEnterStage2(session, nowWall, unlockMs)) return session
        return session.copy(
            status = MorningState.STAGE2_PREP,
            check5mConfirmed = true,
            updatedWall = nowWall
        )
    }

    /** Strict sequential transition: STAGE2_PREP -> STAGE3_REWARD when wash-up & prep finish. */
    fun enterStage3(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status != MorningState.STAGE2_PREP) return session
        return session.copy(
            status = MorningState.STAGE3_REWARD,
            updatedWall = nowWall
        )
    }

    fun onCheck5mConfirmed(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (!session.status.isInActivation) return session
        return session.copy(check5mConfirmed = true, updatedWall = nowWall)
    }

    fun onMotionHint(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (!session.status.isInActivation) return session
        // Motion is only an auxiliary signal; it never auto-completes wake-up!
        return session.copy(motionHintSeen = true, updatedWall = nowWall)
    }

    /** Strict sequential completion: only completes from STAGE3_REWARD (or legacy ACTIVATION). */
    fun onActivationCompleted(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status != MorningState.STAGE3_REWARD && session.status != MorningState.ACTIVATION) {
            return session
        }
        return session.copy(
            status = MorningState.COMPLETED,
            completedWall = nowWall,
            updatedWall = nowWall
        )
    }

    fun onReturnedToBed(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status.isTerminal) return session
        return session.copy(
            status = MorningState.RETURNED_TO_BED,
            updatedWall = nowWall
        )
    }

    fun settleUnverifiedIfExpired(
        session: MorningSessionRecord,
        nowWall: Long,
        timeoutMs: Long = UNVERIFIED_TIMEOUT_MS
    ): MorningSessionRecord {
        if (!session.status.isInActivation) return session
        val base = session.acknowledgedWall ?: session.ringStartedWall
        return if (nowWall - base >= timeoutMs) {
            session.copy(status = MorningState.UNVERIFIED, updatedWall = nowWall)
        } else {
            session
        }
    }

    /**
     * Suppresses redundant subsequent morning alarms when the user has already completed Morning
     * Activation recently (default within 3 hours) or has already progressed into Stage 2 / Stage 3.
     */
    fun shouldSkipSubsequentAlarm(
        latestCompletedWall: Long?,
        activeState: MorningState?,
        nowWall: Long,
        windowMs: Long = SKIP_REPEAT_ALARM_WINDOW_MS
    ): Boolean {
        if (activeState == MorningState.STAGE2_PREP || activeState == MorningState.STAGE3_REWARD) {
            return true
        }
        if (latestCompletedWall != null && nowWall >= latestCompletedWall && (nowWall - latestCompletedWall) <= windowMs) {
            return true
        }
        return false
    }

    /** Guardrail (§13): never reward extreme early wake-up under sleep deprivation (< 5h planned opportunity). */
    fun eligibleForMorningReward(plannedSleepOpportunityMinutes: Int): Boolean =
        plannedSleepOpportunityMinutes >= MIN_HEALTHY_SLEEP_OPPORTUNITY_MIN
}
