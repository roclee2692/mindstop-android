package app.mindstop.core

import java.util.Calendar

/**
 * Pure Kotlin state machine & scheduler math for Module 2: Morning Activation (v0.3 SPEC §9–§14).
 *
 * Alarm architecture inspired by Purgator/Upupup (GPL-3.0) and FossifyOrg/Clock (GPL-3.0).
 * State flow:
 *   SCHEDULED -> RINGING -> ACKNOWLEDGED -> ACTIVATION -> COMPLETED | RETURNED_TO_BED | UNVERIFIED
 */
enum class MorningState(val code: String) {
    SCHEDULED("SCHEDULED"),
    RINGING("RINGING"),
    ACKNOWLEDGED("ACKNOWLEDGED"),
    ACTIVATION("ACTIVATION"),
    COMPLETED("COMPLETED"),
    RETURNED_TO_BED("RETURNED_TO_BED"),
    UNVERIFIED("UNVERIFIED");

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
 * Pure Morning Activation state machine (v0.3 SPEC §11–§13).
 * Closing an alarm NEVER marks wake-up as completed; it enters ACKNOWLEDGED -> ACTIVATION.
 * Extreme sleep deprivation (< 5 hours planned sleep opportunity) does NOT earn early-wake rewards.
 */
object MorningActivationEngine {

    /** Minimum planned sleep opportunity (300m = 5h) required to qualify for morning wake reward. */
    const val MIN_HEALTHY_SLEEP_OPPORTUNITY_MIN = 300

    /** Timeout after which an unconfirmed activation settles honestly as UNVERIFIED (default 45m). */
    const val UNVERIFIED_TIMEOUT_MS = 45 * 60_000L

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
     * User dismissed the alarm: transitions RINGING -> ACTIVATION (via ACKNOWLEDGED) and computes
     * response delay. Never marks wake-up as COMPLETED yet.
     */
    fun onAlarmAcknowledged(
        session: MorningSessionRecord,
        nowWall: Long,
        rewardId: Long? = null
    ): MorningSessionRecord {
        val delaySec = ((nowWall - session.ringStartedWall).coerceAtLeast(0L) / 1_000L).toInt()
        return session.copy(
            acknowledgedWall = nowWall,
            status = MorningState.ACTIVATION,
            responseDelaySec = delaySec,
            rewardId = rewardId ?: session.rewardId,
            updatedWall = nowWall
        )
    }

    fun onCheck5mConfirmed(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status != MorningState.ACTIVATION) return session
        return session.copy(check5mConfirmed = true, updatedWall = nowWall)
    }

    fun onMotionHint(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status != MorningState.ACTIVATION) return session
        // Motion is only an auxiliary signal; it never auto-completes wake-up!
        return session.copy(motionHintSeen = true, updatedWall = nowWall)
    }

    fun onActivationCompleted(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status.isTerminal) return session
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
        if (session.status != MorningState.ACTIVATION) return session
        val base = session.acknowledgedWall ?: session.ringStartedWall
        return if (nowWall - base >= timeoutMs) {
            session.copy(status = MorningState.UNVERIFIED, updatedWall = nowWall)
        } else {
            session
        }
    }

    /** Guardrail (§13): never reward extreme early wake-up under sleep deprivation (< 5h planned opportunity). */
    fun eligibleForMorningReward(plannedSleepOpportunityMinutes: Int): Boolean =
        plannedSleepOpportunityMinutes >= MIN_HEALTHY_SLEEP_OPPORTUNITY_MIN
}
