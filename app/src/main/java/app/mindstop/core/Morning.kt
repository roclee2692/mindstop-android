package app.mindstop.core

import java.util.Calendar

/**
 * Pure Kotlin state machine, policy & analytics math for Module 2: Morning Activation V2.
 *
 * Open-source architecture & reliability references (all compatible with GPL-3.0-or-later):
 * - WakeNudge (Apache-2.0, https://github.com/riyadmondol2006/WakeNudge):
 *   WakeCheckController sub-state flow, hardware TYPE_STEP_COUNTER baseline diff (WakeProofPolicy),
 *   token-guarded stale event suppression, and reboot recovery patterns.
 * - FossifyOrg/Clock (GPL-3.0) & Purgator/Upupup (GPL-3.0): exact alarm scheduling & lock-screen flow.
 * - Simple Alarm Clock (https://github.com/yuriykulikov/AlarmClock): gradual volume & hold-to-confirm UX.
 *
 * Strict sequential state flow (single active primary stage at any instant):
 *   SCHEDULED -> RINGING / STAGE1_WAKE (15m window, 5m unlock gate, 2nd ring unlocks extended sleep)
 *     <-> EXTENDED_SLEEP (intentional sleep extension via 3s hold, capped by optional latest wake time)
 *     -> STAGE2_PREP (30m prep + internal Silent Wake Check sub-state WAITING/PROMPTED/CONFIRMED)
 *         [wake check timeout rolls back atomically to STAGE1_WAKE]
 *     -> STAGE3_REWARD (strictly mutually exclusive Choice A Instant / Choice B Delayed / Choice C Skip)
 *     -> COMPLETED | RETURNED_TO_BED | UNVERIFIED
 */
enum class MorningState(val code: String) {
    SCHEDULED("SCHEDULED"),
    RINGING("RINGING"),
    ACKNOWLEDGED("ACKNOWLEDGED"),
    /** Legacy v0.3.0-rc state kept for backward compatibility with existing DB rows. */
    ACTIVATION("ACTIVATION"),
    STAGE1_WAKE("STAGE1_WAKE"),
    /** Intentional sleep extension after 2nd ring (paused morning flow until new wake time). */
    EXTENDED_SLEEP("EXTENDED_SLEEP"),
    STAGE2_PREP("STAGE2_PREP"),
    STAGE3_REWARD("STAGE3_REWARD"),
    COMPLETED("COMPLETED"),
    RETURNED_TO_BED("RETURNED_TO_BED"),
    UNVERIFIED("UNVERIFIED");

    val isInActivation: Boolean
        get() = this == ACTIVATION ||
            this == STAGE1_WAKE ||
            this == EXTENDED_SLEEP ||
            this == STAGE2_PREP ||
            this == STAGE3_REWARD

    val isStage1: Boolean
        get() = this == STAGE1_WAKE || this == ACTIVATION

    val isTerminal: Boolean
        get() = this == COMPLETED || this == RETURNED_TO_BED || this == UNVERIFIED

    companion object {
        fun fromCode(raw: String?): MorningState =
            entries.firstOrNull { it.code == raw } ?: UNVERIFIED
    }
}

/** Internal sub-state of Stage 2 Silent Wake Check (does NOT create a 4th primary stage). */
enum class WakeCheckSubState(val code: String) {
    /** Not yet in Stage 2 or disabled. */
    NONE("NONE"),
    /** Waiting for trigger delay (default 5 min after Stage 2 starts). */
    WAITING("WAITING"),
    /** Silent confirmation window is open (default 5 min); no ringtone plays. */
    PROMPTED("PROMPTED"),
    /** User confirmed awake (manually or via optional 30-step hardware counter). */
    CONFIRMED("CONFIRMED");

    companion object {
        fun fromCode(raw: String?): WakeCheckSubState =
            entries.firstOrNull { it.code == raw } ?: NONE
    }
}

/** Strictly mutually exclusive Stage 3 reward choices (max 1 per morning session). */
enum class MorningRewardChoice(val code: String) {
    NONE("NONE"),
    /** Choice A: Bounded low-distraction instant activity (e.g. 10m music/market/breakfast). */
    INSTANT("INSTANT"),
    /** Choice B: +15m daily entertainment quota unlocked after morning restriction ends. */
    DELAYED("DELAYED"),
    /** Choice C: Decline reward and start the day right away. */
    SKIPPED("SKIPPED");

    companion object {
        fun fromCode(raw: String?): MorningRewardChoice =
            entries.firstOrNull { it.code == raw } ?: NONE
    }
}

/** Built-in gentle body-wake & morning prep presets (Stage 2). */
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

/** Built-in low-distraction morning expectation / joy presets (Stage 3, excludes short video). */
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

/**
 * User-configurable Morning Activation V2 parameters (all time thresholds remain tunable).
 * Stored locally in MetaDao without requiring a Room schema migration.
 */
data class MorningV2Settings(
    val stage1WindowMin: Int = 15,
    val stage1UnlockMin: Int = 5,
    val stage1RepeatIntervalMin: Int = 5,
    val stage2DurationMin: Int = 30,
    /** Optional minimum Stage 2 prep time before early completion (default 0 = no forced wait). */
    val stage2MinPrepMin: Int = 0,
    /** Silent wake check trigger delay after Stage 2 starts (default 5 min). */
    val wakeCheckDelayMin: Int = 5,
    /** Silent wake check confirmation window duration (default 5 min). */
    val wakeCheckWindowMin: Int = 5,
    /** Optional low-distraction hardware step counter assist (default OFF, never mandatory). */
    val stepProofEnabled: Boolean = false,
    val stepProofThreshold: Int = 30,
    /** Stage 3 enable toggle & instant reward duration (default 10 min). */
    val stage3Enabled: Boolean = true,
    val stage3InstantMin: Int = 10,
    /** Delayed bonus quota minutes (default 15m) & unlock time in minutes of day (default 12:00 = 720). */
    val delayedBonusMinutes: Int = 15,
    val morningRestrictEndMinute: Int = 12 * 60,
    /** Optional latest wake-up cap in minutes of day (-1 = disabled, e.g. 540 = 09:00). */
    val latestWakeMinuteOfDay: Int = -1,
    /** Optional custom comma-separated Stage 2 prep items. */
    val customPrepItems: String = ""
) {
    val stage1WindowMs: Long get() = stage1WindowMin.coerceIn(5, 60) * 60_000L
    val stage1UnlockMs: Long get() = stage1UnlockMin.coerceIn(1, stage1WindowMin.coerceAtLeast(1)) * 60_000L
    val stage1RepeatMs: Long get() = stage1RepeatIntervalMin.coerceIn(2, 30) * 60_000L
    val stage2DurationMs: Long get() = stage2DurationMin.coerceIn(5, 90) * 60_000L
    val stage2MinPrepMs: Long get() = stage2MinPrepMin.coerceIn(0, 60) * 60_000L
    val wakeCheckDelayMs: Long get() = wakeCheckDelayMin.coerceIn(1, 30) * 60_000L
    val wakeCheckWindowMs: Long get() = wakeCheckWindowMin.coerceIn(1, 20) * 60_000L
    val stage3InstantMs: Long get() = stage3InstantMin.coerceIn(1, 30) * 60_000L

    fun encode(): String = listOf(
        stage1WindowMin,
        stage1UnlockMin,
        stage1RepeatIntervalMin,
        stage2DurationMin,
        stage2MinPrepMin,
        wakeCheckDelayMin,
        wakeCheckWindowMin,
        if (stepProofEnabled) 1 else 0,
        stepProofThreshold,
        if (stage3Enabled) 1 else 0,
        stage3InstantMin,
        delayedBonusMinutes,
        morningRestrictEndMinute,
        latestWakeMinuteOfDay,
        customPrepItems
    ).joinToString("|")

    companion object {
        fun decode(raw: String?): MorningV2Settings {
            if (raw.isNullOrBlank()) return MorningV2Settings()
            val p = raw.split('|', limit = 15)
            if (p.size < 14) return MorningV2Settings()
            return MorningV2Settings(
                stage1WindowMin = p[0].toIntOrNull()?.coerceIn(5, 60) ?: 15,
                stage1UnlockMin = p[1].toIntOrNull()?.coerceIn(1, 30) ?: 5,
                stage1RepeatIntervalMin = p[2].toIntOrNull()?.coerceIn(2, 30) ?: 5,
                stage2DurationMin = p[3].toIntOrNull()?.coerceIn(5, 90) ?: 30,
                stage2MinPrepMin = p[4].toIntOrNull()?.coerceIn(0, 60) ?: 0,
                wakeCheckDelayMin = p[5].toIntOrNull()?.coerceIn(1, 30) ?: 5,
                wakeCheckWindowMin = p[6].toIntOrNull()?.coerceIn(1, 20) ?: 5,
                stepProofEnabled = p[7] == "1",
                stepProofThreshold = p[8].toIntOrNull()?.coerceIn(10, 200) ?: 30,
                stage3Enabled = p[9] != "0",
                stage3InstantMin = p[10].toIntOrNull()?.coerceIn(1, 30) ?: 10,
                delayedBonusMinutes = p[11].toIntOrNull()?.coerceIn(5, 60) ?: 15,
                morningRestrictEndMinute = p[12].toIntOrNull()?.coerceIn(0, 1439) ?: (12 * 60),
                latestWakeMinuteOfDay = p[13].toIntOrNull()?.coerceIn(-1, 1439) ?: -1,
                customPrepItems = p.getOrNull(14).orEmpty()
            )
        }
    }
}

/**
 * Per-session runtime state persisted cleanly alongside [MorningSessionRecord].
 * Every stage instance and wake-check window carries an independent monotonic token so any
 * stale or late-delivered AlarmManager broadcast is rejected deterministically.
 */
data class MorningRuntimeMeta(
    val stageToken: Long = 1L,
    val wakeCheckToken: Long = 0L,
    val stage1StartWall: Long = 0L,
    val stage2StartWall: Long = 0L,
    val stage3StartWall: Long = 0L,
    val ringCount: Int = 1,
    val extendedSleepCount: Int = 0,
    val extendedSleepTotalMin: Int = 0,
    val extendedSleepResumeWall: Long = 0L,
    val wakeCheckState: WakeCheckSubState = WakeCheckSubState.NONE,
    val wakeCheckPromptWall: Long = 0L,
    val wakeCheckDeadlineWall: Long = 0L,
    val wakeCheckTimeouts: Int = 0,
    /** Hardware TYPE_STEP_COUNTER reading captured at Stage 2 start (-1f = unavailable). */
    val stepBaseline: Float = -1f,
    val rewardChoice: MorningRewardChoice = MorningRewardChoice.NONE,
    val instantRewardEndWall: Long = 0L,
    val selectedJoy: String = "",
    val bonusClaimed: Boolean = false,
    val bonusAfterNoon: Boolean = true,
    /** Optional self-rated wakefulness 0 (unset) or 1..5. */
    val wakefulnessRating: Int = 0,
    val stage1ReminderDelivered: Boolean = ringCount >= 2
) {
    val canOfferExtendedSleep: Boolean
        get() = ringCount >= 2

    fun encode(): String = listOf(
        stageToken,
        wakeCheckToken,
        stage1StartWall,
        stage2StartWall,
        stage3StartWall,
        ringCount,
        extendedSleepCount,
        extendedSleepTotalMin,
        extendedSleepResumeWall,
        wakeCheckState.code,
        wakeCheckPromptWall,
        wakeCheckDeadlineWall,
        wakeCheckTimeouts,
        stepBaseline,
        rewardChoice.code,
        instantRewardEndWall,
        selectedJoy.replace("|", ""),
        if (bonusClaimed) 1 else 0,
        if (bonusAfterNoon) 1 else 0,
        wakefulnessRating,
        if (stage1ReminderDelivered) 1 else 0
    ).joinToString("|")

    companion object {
        fun decode(raw: String?): MorningRuntimeMeta {
            if (raw.isNullOrBlank()) return MorningRuntimeMeta()
            val p = raw.split('|')
            // Backward compatibility with V1 6-field format: s1|s2|s3|claimed|afternoon|joy
            if (p.size <= 6) {
                return MorningRuntimeMeta(
                    stage1StartWall = p.getOrNull(0)?.toLongOrNull() ?: 0L,
                    stage2StartWall = p.getOrNull(1)?.toLongOrNull() ?: 0L,
                    stage3StartWall = p.getOrNull(2)?.toLongOrNull() ?: 0L,
                    bonusClaimed = p.getOrNull(3) == "1",
                    bonusAfterNoon = p.getOrNull(4)?.let { it == "1" } ?: true,
                    selectedJoy = p.getOrNull(5).orEmpty()
                )
            }
            return MorningRuntimeMeta(
                stageToken = p.getOrNull(0)?.toLongOrNull() ?: 1L,
                wakeCheckToken = p.getOrNull(1)?.toLongOrNull() ?: 0L,
                stage1StartWall = p.getOrNull(2)?.toLongOrNull() ?: 0L,
                stage2StartWall = p.getOrNull(3)?.toLongOrNull() ?: 0L,
                stage3StartWall = p.getOrNull(4)?.toLongOrNull() ?: 0L,
                ringCount = p.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                extendedSleepCount = p.getOrNull(6)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                extendedSleepTotalMin = p.getOrNull(7)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                extendedSleepResumeWall = p.getOrNull(8)?.toLongOrNull() ?: 0L,
                wakeCheckState = WakeCheckSubState.fromCode(p.getOrNull(9)),
                wakeCheckPromptWall = p.getOrNull(10)?.toLongOrNull() ?: 0L,
                wakeCheckDeadlineWall = p.getOrNull(11)?.toLongOrNull() ?: 0L,
                wakeCheckTimeouts = p.getOrNull(12)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                stepBaseline = p.getOrNull(13)?.toFloatOrNull() ?: -1f,
                rewardChoice = MorningRewardChoice.fromCode(p.getOrNull(14)),
                instantRewardEndWall = p.getOrNull(15)?.toLongOrNull() ?: 0L,
                selectedJoy = p.getOrNull(16).orEmpty(),
                bonusClaimed = p.getOrNull(17) == "1",
                bonusAfterNoon = p.getOrNull(18)?.let { it == "1" } ?: true,
                wakefulnessRating = p.getOrNull(19)?.toIntOrNull()?.coerceIn(0, 5) ?: 0,
                stage1ReminderDelivered = p.getOrNull(20)?.let { it == "1" }
                    ?: ((p.getOrNull(5)?.toIntOrNull() ?: 1) >= 2)
            )
        }
    }
}

/** Undelivered daily quota stored in the existing day|unlock|minutes|delivered meta value. */
internal data class PendingMorningBonus(val dayKey: String, val unlockWall: Long, val minutes: Int) {
    companion object {
        fun forToday(raw: String?, nowWall: Long): PendingMorningBonus? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 4 || parts[3] != "0" || parts[0] != DayKeys.dayKey(nowWall)) return null
            val unlock = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val minutes = parts[2].toIntOrNull()?.takeIf { it in 1..60 } ?: return null
            return PendingMorningBonus(parts[0], unlock, minutes)
        }
    }
}

/** Local observational morning telemetry record (no shaming scores, pure self-awareness). */
data class MorningStatRecord(
    val dayKey: String,
    val sessionId: Long,
    val firstRingToWakeSec: Int,
    val extendedSleepCount: Int,
    val extendedSleepMinutes: Int,
    val wakeCheckTimeouts: Int,
    val completed: Boolean,
    val prepDurationMin: Int,
    val postWakeDistractionMin: Int,
    val wakefulnessRating: Int = 0
) {
    fun encode(): String = listOf(
        dayKey,
        sessionId,
        firstRingToWakeSec,
        extendedSleepCount,
        extendedSleepMinutes,
        wakeCheckTimeouts,
        if (completed) 1 else 0,
        prepDurationMin,
        postWakeDistractionMin,
        wakefulnessRating
    ).joinToString(",")

    companion object {
        fun decode(raw: String): MorningStatRecord? {
            val p = raw.split(',')
            if (p.size < 9) return null
            return MorningStatRecord(
                dayKey = p[0],
                sessionId = p[1].toLongOrNull() ?: return null,
                firstRingToWakeSec = p[2].toIntOrNull() ?: 0,
                extendedSleepCount = p[3].toIntOrNull() ?: 0,
                extendedSleepMinutes = p[4].toIntOrNull() ?: 0,
                wakeCheckTimeouts = p[5].toIntOrNull() ?: 0,
                completed = p[6] == "1",
                prepDurationMin = p[7].toIntOrNull() ?: 0,
                postWakeDistractionMin = p[8].toIntOrNull() ?: 0,
                wakefulnessRating = p.getOrNull(9)?.toIntOrNull()?.coerceIn(0, 5) ?: 0
            )
        }
    }
}

data class MorningStatsSummary(
    val daysWindow: Int,
    val totalSessions: Int,
    val completionRatePercent: Int,
    val avgWakeConfirmMin: Int,
    val totalExtendedCount: Int,
    val totalExtendedMinutes: Int,
    val totalWakeCheckTimeouts: Int,
    val avgPrepMin: Int,
    val avgPostWakeDistractionMin: Int,
    val avgWakefulnessRating: Float
)

object MorningStatsAggregator {
    fun summarize(records: List<MorningStatRecord>, daysWindow: Int, cutoffDayKey: String): MorningStatsSummary {
        val inWindow = records.filter { it.dayKey >= cutoffDayKey }
        if (inWindow.isEmpty()) {
            return MorningStatsSummary(daysWindow, 0, 0, 0, 0, 0, 0, 0, 0, 0f)
        }
        val completedCount = inWindow.count { it.completed }
        val completionPct = (completedCount * 100) / inWindow.size
        val avgWakeMin = ((inWindow.map { it.firstRingToWakeSec }.average()) / 60.0).toInt()
        val prepSamples = inWindow.filter { it.prepDurationMin > 0 }
        val avgPrep = if (prepSamples.isEmpty()) 0 else prepSamples.map { it.prepDurationMin }.average().toInt()
        val avgDistract = inWindow.map { it.postWakeDistractionMin }.average().toInt()
        val rated = inWindow.filter { it.wakefulnessRating in 1..5 }
        val avgRating = if (rated.isEmpty()) 0f else rated.map { it.wakefulnessRating }.average().toFloat()
        return MorningStatsSummary(
            daysWindow = daysWindow,
            totalSessions = inWindow.size,
            completionRatePercent = completionPct,
            avgWakeConfirmMin = avgWakeMin,
            totalExtendedCount = inWindow.sumOf { it.extendedSleepCount },
            totalExtendedMinutes = inWindow.sumOf { it.extendedSleepMinutes },
            totalWakeCheckTimeouts = inWindow.sumOf { it.wakeCheckTimeouts },
            avgPrepMin = avgPrep,
            avgPostWakeDistractionMin = avgDistract,
            avgWakefulnessRating = avgRating
        )
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
    /** Enables progressive 2nd ring & overtime backup reminder during Stage 1. */
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

    /** Computes today's wall timestamp for [minuteOfDay] (0..1439) in local timezone. */
    fun localWallForMinuteOfDay(nowWall: Long, minuteOfDay: Int): Long = Calendar.getInstance().apply {
        timeInMillis = nowWall
        set(Calendar.HOUR_OF_DAY, (minuteOfDay / 60).coerceIn(0, 23))
        set(Calendar.MINUTE, (minuteOfDay % 60).coerceIn(0, 59))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * Pure sequential 3-stage Morning Activation V2 state machine & policy engine.
 *
 * Key invariants enforced at the pure-logic level:
 * 1. Single active primary stage at any instant; timers never auto-complete a stage.
 * 2. Stage 1 starts timing immediately when the alarm fires (ringStartedWall / stage1StartWall);
 *    silencing audio keeps Stage 1 running without resetting the 5-min anti-cheat gate.
 * 3. Intentional extended sleep unlocks ONLY starting from the 2nd ring (ringCount >= 2),
 *    respects optional latest wake-up time cap, pauses the morning flow, and restarts Stage 1.
 * 4. Stage 2 contains an internal Silent Wake Check sub-state (WAITING -> PROMPTED -> CONFIRMED).
 *    Stage 2 cannot be completed until wake check is CONFIRMED; timeout rolls back to Stage 1.
 *    Hardware step counter proof (adapted from WakeNudge WakeProofPolicy, Apache-2.0) verifies
 *    only newly accumulated steps in Stage 2 (and handles reboot where counter < baseline).
 * 5. Stage 3 reward choice is strictly mutually exclusive (A Instant / B Delayed / C Skip),
 *    bound to flow completion (never to ring count or extended sleep count), and idempotent.
 */
object MorningActivationEngine {

    /** Minimum planned sleep opportunity (300m = 5h) required to qualify for Ginkgo wake reward. */
    const val MIN_HEALTHY_SLEEP_OPPORTUNITY_MIN = 300

    /** Default Stage 1 anti-cheat lock before "I am awake" unlocks (5 min). */
    const val STAGE1_UNLOCK_MS = 5 * 60_000L

    /** Default Stage 1 planned wake-up window before overtime reminders begin (15 min). */
    const val STAGE1_WINDOW_MS = 15 * 60_000L

    /** Default Stage 2 wash-up and morning preparation duration (30 min). */
    const val STAGE2_DURATION_MS = 30 * 60_000L

    /** Default Stage 3 instant joy duration (10 min). */
    const val STAGE3_DEFAULT_MS = 10 * 60_000L

    /** Default Stage 2 silent wake check trigger delay (5 min) and response window (5 min). */
    const val WAKE_CHECK_DELAY_MS = 5 * 60_000L
    const val WAKE_CHECK_WINDOW_MS = 5 * 60_000L

    /** Default optional step proof threshold (adapted from WakeNudge WakeProofPolicy.STEPS_NEEDED). */
    const val DEFAULT_STEP_PROOF_STEPS = 30

    /** Required press-and-hold duration (3s) to confirm intentional extended sleep. */
    const val EXTENDED_SLEEP_HOLD_MS = 3_000L

    /** Default delayed bonus daily entertainment quota minutes offered in Stage 3 Choice B. */
    const val BONUS_QUOTA_MINUTES = 15

    /** Same-morning window (3h) in which subsequent scheduled alarms auto-skip once awake. */
    const val SKIP_REPEAT_ALARM_WINDOW_MS = 3 * 60 * 60_000L

    /** Generous ceiling (4h, accommodates extended sleep + 3 stages) before settling UNVERIFIED. */
    const val UNVERIFIED_TIMEOUT_MS = 240 * 60_000L

    data class StageTransition(
        val session: MorningSessionRecord,
        val meta: MorningRuntimeMeta,
        val changed: Boolean
    )

    fun onRingStarted(alarm: MorningAlarm, scheduledWall: Long, nowWall: Long): MorningSessionRecord =
        MorningSessionRecord(
            dayKey = DayKeys.dayKey(nowWall),
            alarmId = alarm.id,
            scheduledWall = scheduledWall,
            ringStartedWall = nowWall,
            status = MorningState.STAGE1_WAKE,
            joyActivity = alarm.joyActivity,
            updatedWall = nowWall
        )

    fun initialMetaForRing(alarm: MorningAlarm, nowWall: Long): MorningRuntimeMeta =
        MorningRuntimeMeta(
            stageToken = 1L,
            wakeCheckToken = 0L,
            stage1StartWall = nowWall,
            ringCount = 1,
            selectedJoy = alarm.joyActivity
        )

    /**
     * User muted / dismissed the ringing screen during Stage 1.
     * Enters/stays in STAGE1_WAKE, preserves original [stage1StartWall] so the 5m unlock gate
     * and 15m planned wake window count continuously from when the alarm first rang!
     */
    fun onAlarmAcknowledged(
        session: MorningSessionRecord,
        nowWall: Long,
        rewardId: Long? = null
    ): MorningSessionRecord {
        if (!session.status.isStage1 && session.status != MorningState.RINGING && session.status != MorningState.ACKNOWLEDGED) return session
        val delaySec = ((nowWall - session.ringStartedWall).coerceAtLeast(0L) / 1_000L).toInt()
        return session.copy(
            acknowledgedWall = session.acknowledgedWall ?: nowWall,
            status = MorningState.STAGE1_WAKE,
            responseDelaySec = if (session.acknowledgedWall == null) delaySec else session.responseDelaySec,
            rewardId = rewardId ?: session.rewardId,
            updatedWall = nowWall
        )
    }

    /** Records a subsequent Stage 1 ring (2nd ring at +5m or overtime repeat ring). */
    fun onStage1RepeatRing(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        expectedStageToken: Long,
        nowWall: Long,
        secondRingOnly: Boolean = false
    ): StageTransition {
        if (!session.status.isStage1 || meta.stageToken != expectedStageToken || (secondRingOnly && meta.stage1ReminderDelivered)) {
            return StageTransition(session, meta, changed = false)
        }
        return StageTransition(
            session = session.copy(updatedWall = nowWall),
            meta = meta.copy(ringCount = meta.ringCount + 1, stage1ReminderDelivered = true),
            changed = true
        )
    }

    /** Restore at most one missed ring. Overtime takes precedence once the wake window ends. */
    fun stage1RingTimes(start: Long, settings: MorningV2Settings, now: Long, reminderDelivered: Boolean): Pair<Long?, Long> {
        val unlock = start + settings.stage1UnlockMs
        val end = start + settings.stage1WindowMs
        val second = if (!reminderDelivered && now < end && unlock < end) maxOf(unlock, now) else null
        return second to maxOf(end, now)
    }

    fun instantRewardTimerWall(meta: MorningRuntimeMeta, now: Long): Long? =
        meta.instantRewardEndWall.takeIf { meta.rewardChoice == MorningRewardChoice.INSTANT && it > 0L }
            ?.coerceAtLeast(now)

    fun stage1BaseWall(session: MorningSessionRecord, meta: MorningRuntimeMeta? = null): Long =
        meta?.stage1StartWall?.takeIf { it > 0L } ?: session.ringStartedWall

    fun stage1UnlockRemainingMs(
        session: MorningSessionRecord,
        nowWall: Long,
        unlockMs: Long = STAGE1_UNLOCK_MS,
        meta: MorningRuntimeMeta? = null
    ): Long {
        if (!session.status.isStage1) return 0L
        val start = stage1BaseWall(session, meta)
        return (start + unlockMs - nowWall).coerceAtLeast(0L)
    }

    fun stage1WindowRemainingMs(
        session: MorningSessionRecord,
        nowWall: Long,
        windowMs: Long = STAGE1_WINDOW_MS,
        meta: MorningRuntimeMeta? = null
    ): Long {
        if (!session.status.isStage1) return 0L
        val start = stage1BaseWall(session, meta)
        return (start + windowMs - nowWall).coerceAtLeast(0L)
    }

    fun stage1OvertimeMs(
        session: MorningSessionRecord,
        nowWall: Long,
        windowMs: Long = STAGE1_WINDOW_MS,
        meta: MorningRuntimeMeta? = null
    ): Long {
        if (!session.status.isStage1) return 0L
        val start = stage1BaseWall(session, meta)
        return (nowWall - (start + windowMs)).coerceAtLeast(0L)
    }

    fun canEnterStage2(
        session: MorningSessionRecord,
        nowWall: Long,
        unlockMs: Long = STAGE1_UNLOCK_MS,
        meta: MorningRuntimeMeta? = null
    ): Boolean = session.status.isStage1 && stage1UnlockRemainingMs(session, nowWall, unlockMs, meta) == 0L

    /**
     * Checks whether intentional extended sleep is currently unlocked (ONLY from 2nd ring onward)
     * and computes the capped target resume timestamp respecting [MorningV2Settings.latestWakeMinuteOfDay].
     * Returns null if extended sleep is not permitted or latest wake cap has already passed.
     */
    fun computeExtendedSleepTargetWall(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        requestedMinutes: Int,
        settings: MorningV2Settings,
        nowWall: Long
    ): Long? {
        if (!session.status.isStage1 || !meta.canOfferExtendedSleep) return null
        val mins = requestedMinutes.coerceIn(5, 180)
        var target = nowWall + mins * 60_000L
        if (settings.latestWakeMinuteOfDay in 0..1439) {
            val capWall = MorningAlarmPlanner.localWallForMinuteOfDay(nowWall, settings.latestWakeMinuteOfDay)
            if (nowWall >= capWall - 60_000L) return null
            if (target > capWall) target = capWall
        }
        return target
    }

    /** Transitions STAGE1_WAKE -> EXTENDED_SLEEP after 3-second hold confirmation. */
    fun enterExtendedSleep(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        requestedMinutes: Int,
        settings: MorningV2Settings,
        nowWall: Long
    ): StageTransition {
        val targetWall = computeExtendedSleepTargetWall(session, meta, requestedMinutes, settings, nowWall)
            ?: return StageTransition(session, meta, changed = false)
        val actualMin = (((targetWall - nowWall) + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
        val nextToken = meta.stageToken + 1L
        return StageTransition(
            session = session.copy(status = MorningState.EXTENDED_SLEEP, updatedWall = nowWall),
            meta = meta.copy(
                stageToken = nextToken,
                extendedSleepCount = meta.extendedSleepCount + 1,
                extendedSleepTotalMin = meta.extendedSleepTotalMin + actualMin,
                extendedSleepResumeWall = targetWall,
                wakeCheckState = WakeCheckSubState.NONE
            ),
            changed = true
        )
    }

    /** Resumes EXTENDED_SLEEP -> fresh STAGE1_WAKE when the scheduled extended sleep ends. */
    fun resumeFromExtendedSleep(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        expectedStageToken: Long,
        nowWall: Long
    ): StageTransition {
        if (session.status != MorningState.EXTENDED_SLEEP || meta.stageToken != expectedStageToken) {
            return StageTransition(session, meta, changed = false)
        }
        val nextToken = meta.stageToken + 1L
        return StageTransition(
            session = session.copy(status = MorningState.STAGE1_WAKE, updatedWall = nowWall),
            meta = meta.copy(
                stageToken = nextToken,
                stage1StartWall = nowWall,
                ringCount = 1,
                stage1ReminderDelivered = false,
                extendedSleepResumeWall = 0L,
                wakeCheckState = WakeCheckSubState.NONE
            ),
            changed = true
        )
    }

    /** Strict sequential transition: STAGE1_WAKE -> STAGE2_PREP once the 5-minute gate passes. */
    fun enterStage2(
        session: MorningSessionRecord,
        nowWall: Long,
        unlockMs: Long = STAGE1_UNLOCK_MS,
        meta: MorningRuntimeMeta? = null
    ): MorningSessionRecord {
        if (!canEnterStage2(session, nowWall, unlockMs, meta)) return session
        return session.copy(
            status = MorningState.STAGE2_PREP,
            check5mConfirmed = true,
            updatedWall = nowWall
        )
    }

    fun enterStage2WithMeta(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        nowWall: Long,
        stepBaseline: Float = -1f
    ): StageTransition {
        if (!canEnterStage2(session, nowWall, settings.stage1UnlockMs, meta)) {
            return StageTransition(session, meta, changed = false)
        }
        val nextToken = meta.stageToken + 1L
        val nextCheckToken = meta.wakeCheckToken + 1L
        val promptWall = nowWall + settings.wakeCheckDelayMs
        return StageTransition(
            session = session.copy(
                status = MorningState.STAGE2_PREP,
                check5mConfirmed = true,
                updatedWall = nowWall
            ),
            meta = meta.copy(
                stageToken = nextToken,
                wakeCheckToken = nextCheckToken,
                stage2StartWall = nowWall,
                wakeCheckState = WakeCheckSubState.WAITING,
                wakeCheckPromptWall = promptWall,
                wakeCheckDeadlineWall = 0L,
                stepBaseline = stepBaseline
            ),
            changed = true
        )
    }

    /**
     * Hardware step counter verification adapted from WakeNudge WakeProofPolicy (Apache-2.0).
     * Verifies ONLY steps accumulated during Stage 2; returns false if unavailable or after reboot.
     */
    fun stepsSatisfyWakeCheck(baseline: Float, currentSteps: Float?, requiredSteps: Int = DEFAULT_STEP_PROOF_STEPS): Boolean =
        baseline >= 0f &&
            currentSteps != null &&
            currentSteps.isFinite() &&
            currentSteps >= baseline &&
            (currentSteps - baseline) >= requiredSteps.coerceAtLeast(1)

    /** Opens the silent wake-check confirmation window (or auto-passes via optional step counter). */
    fun triggerSilentWakeCheckPrompt(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        expectedCheckToken: Long,
        settings: MorningV2Settings,
        nowWall: Long,
        currentSteps: Float? = null
    ): StageTransition {
        if (session.status != MorningState.STAGE2_PREP ||
            meta.wakeCheckToken != expectedCheckToken ||
            meta.wakeCheckState != WakeCheckSubState.WAITING
        ) {
            return StageTransition(session, meta, changed = false)
        }
        if (settings.stepProofEnabled && stepsSatisfyWakeCheck(meta.stepBaseline, currentSteps, settings.stepProofThreshold)) {
            return StageTransition(
                session = session.copy(updatedWall = nowWall),
                meta = meta.copy(
                    wakeCheckState = WakeCheckSubState.CONFIRMED,
                    wakeCheckDeadlineWall = 0L
                ),
                changed = true
            )
        }
        return StageTransition(
            session = session.copy(updatedWall = nowWall),
            meta = meta.copy(
                wakeCheckState = WakeCheckSubState.PROMPTED,
                wakeCheckDeadlineWall = nowWall + settings.wakeCheckWindowMs
            ),
            changed = true
        )
    }

    /** User manually confirms awake (or step counter reaches threshold) during Stage 2. */
    fun confirmSilentWakeCheck(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        expectedCheckToken: Long?,
        nowWall: Long
    ): StageTransition {
        if (session.status != MorningState.STAGE2_PREP) {
            return StageTransition(session, meta, changed = false)
        }
        if (expectedCheckToken != null && (expectedCheckToken != meta.wakeCheckToken || expectedCheckToken < 0L)) {
            return StageTransition(session, meta, changed = false)
        }
        if (meta.wakeCheckState == WakeCheckSubState.CONFIRMED ||
            (meta.wakeCheckState == WakeCheckSubState.PROMPTED && nowWall >= meta.wakeCheckDeadlineWall)
        ) {
            return StageTransition(session, meta, changed = false)
        }
        return StageTransition(
            session = session.copy(updatedWall = nowWall),
            meta = meta.copy(
                wakeCheckState = WakeCheckSubState.CONFIRMED,
                wakeCheckDeadlineWall = 0L
            ),
            changed = true
        )
    }

    /**
     * Silent wake-check window timed out unanswered: atomically cancels Stage 2, increments
     * stageToken/wakeCheckTimeouts, and rolls back to STAGE1_WAKE to ring again.
     */
    fun onSilentWakeCheckTimeout(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        expectedCheckToken: Long,
        nowWall: Long
    ): StageTransition {
        if (session.status != MorningState.STAGE2_PREP ||
            meta.wakeCheckToken != expectedCheckToken ||
            meta.wakeCheckState != WakeCheckSubState.PROMPTED ||
            nowWall < meta.wakeCheckDeadlineWall
        ) {
            return StageTransition(session, meta, changed = false)
        }
        val nextToken = meta.stageToken + 1L
        return StageTransition(
            session = session.copy(status = MorningState.STAGE1_WAKE, updatedWall = nowWall),
            meta = meta.copy(
                stageToken = nextToken,
                stage1StartWall = nowWall,
                ringCount = (meta.ringCount + 1).coerceAtLeast(2),
                stage1ReminderDelivered = false,
                wakeCheckState = WakeCheckSubState.NONE,
                wakeCheckPromptWall = 0L,
                wakeCheckDeadlineWall = 0L,
                wakeCheckTimeouts = meta.wakeCheckTimeouts + 1,
                stepBaseline = -1f
            ),
            changed = true
        )
    }

    fun stage2RemainingMs(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        nowWall: Long
    ): Long {
        if (session.status != MorningState.STAGE2_PREP) return 0L
        val start = meta.stage2StartWall.takeIf { it > 0L } ?: session.updatedWall
        return (start + settings.stage2DurationMs - nowWall).coerceAtLeast(0L)
    }

    fun stage2OvertimeMs(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        nowWall: Long
    ): Long {
        if (session.status != MorningState.STAGE2_PREP) return 0L
        val start = meta.stage2StartWall.takeIf { it > 0L } ?: session.updatedWall
        return (nowWall - (start + settings.stage2DurationMs)).coerceAtLeast(0L)
    }

    fun stage2MinPrepRemainingMs(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        nowWall: Long
    ): Long {
        if (session.status != MorningState.STAGE2_PREP || settings.stage2MinPrepMs <= 0L) return 0L
        val start = meta.stage2StartWall.takeIf { it > 0L } ?: session.updatedWall
        return (start + settings.stage2MinPrepMs - nowWall).coerceAtLeast(0L)
    }

    /** Stage 2 completion requires silent wake check CONFIRMED and optional min prep elapsed. */
    fun canCompleteStage2(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        nowWall: Long
    ): Boolean = session.status == MorningState.STAGE2_PREP &&
        meta.wakeCheckState == WakeCheckSubState.CONFIRMED &&
        stage2MinPrepRemainingMs(session, meta, settings, nowWall) == 0L

    /** Strict sequential transition: STAGE2_PREP -> STAGE3_REWARD (or COMPLETED if Stage 3 disabled). */
    fun enterStage3(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status != MorningState.STAGE2_PREP) return session
        return session.copy(
            status = MorningState.STAGE3_REWARD,
            updatedWall = nowWall
        )
    }

    fun enterStage3WithMeta(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        nowWall: Long
    ): StageTransition {
        if (!canCompleteStage2(session, meta, settings, nowWall)) {
            return StageTransition(session, meta, changed = false)
        }
        val nextToken = meta.stageToken + 1L
        if (!settings.stage3Enabled) {
            return StageTransition(
                session = session.copy(
                    status = MorningState.COMPLETED,
                    completedWall = nowWall,
                    updatedWall = nowWall
                ),
                meta = meta.copy(
                    stageToken = nextToken,
                    rewardChoice = MorningRewardChoice.SKIPPED
                ),
                changed = true
            )
        }
        return StageTransition(
            session = session.copy(
                status = MorningState.STAGE3_REWARD,
                updatedWall = nowWall
            ),
            meta = meta.copy(
                stageToken = nextToken,
                stage3StartWall = nowWall
            ),
            changed = true
        )
    }

    /** Stage 3 Choice A: Start bounded instant low-distraction joy countdown. Strictly mutually exclusive. */
    fun chooseInstantReward(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        settings: MorningV2Settings,
        nowWall: Long
    ): StageTransition {
        if (session.status != MorningState.STAGE3_REWARD || meta.rewardChoice != MorningRewardChoice.NONE) {
            return StageTransition(session, meta, changed = false)
        }
        val nextToken = meta.stageToken + 1L
        return StageTransition(
            session = session.copy(updatedWall = nowWall),
            meta = meta.copy(
                stageToken = nextToken,
                rewardChoice = MorningRewardChoice.INSTANT,
                stage3StartWall = nowWall,
                instantRewardEndWall = nowWall + settings.stage3InstantMs
            ),
            changed = true
        )
    }

    /** Stage 3 Choice B: Record delayed +15m bonus choice & complete morning flow. Strictly mutually exclusive. */
    fun chooseDelayedRewardAndComplete(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        nowWall: Long
    ): StageTransition {
        if (session.status != MorningState.STAGE3_REWARD || meta.rewardChoice != MorningRewardChoice.NONE) {
            return StageTransition(session, meta, changed = false)
        }
        val nextToken = meta.stageToken + 1L
        return StageTransition(
            session = session.copy(
                status = MorningState.COMPLETED,
                completedWall = nowWall,
                updatedWall = nowWall
            ),
            meta = meta.copy(
                stageToken = nextToken,
                rewardChoice = MorningRewardChoice.DELAYED,
                bonusClaimed = true,
                bonusAfterNoon = true
            ),
            changed = true
        )
    }

    /** Stage 3 Choice C: Decline reward and finish morning activation cleanly. Strictly mutually exclusive. */
    fun chooseSkipRewardAndComplete(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        nowWall: Long
    ): StageTransition {
        if (session.status != MorningState.STAGE3_REWARD || meta.rewardChoice != MorningRewardChoice.NONE) {
            return StageTransition(session, meta, changed = false)
        }
        val nextToken = meta.stageToken + 1L
        return StageTransition(
            session = session.copy(
                status = MorningState.COMPLETED,
                completedWall = nowWall,
                updatedWall = nowWall
            ),
            meta = meta.copy(
                stageToken = nextToken,
                rewardChoice = MorningRewardChoice.SKIPPED
            ),
            changed = true
        )
    }

    fun onCheck5mConfirmed(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (!session.status.isInActivation) return session
        return session.copy(check5mConfirmed = true, updatedWall = nowWall)
    }

    fun onMotionHint(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (!session.status.isInActivation) return session
        // Motion hint alone never auto-completes wake-up!
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

    /** Honest back-to-bed exit; never recorded as COMPLETED. */
    fun onReturnedToBed(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status.isTerminal) return session
        return session.copy(
            status = MorningState.RETURNED_TO_BED,
            updatedWall = nowWall
        )
    }

    /** Emergency exit (illness / day off / urgent matter); explicitly recorded as UNVERIFIED (not completed). */
    fun onEmergencyExit(session: MorningSessionRecord, nowWall: Long): MorningSessionRecord {
        if (session.status.isTerminal) return session
        return session.copy(
            status = MorningState.UNVERIFIED,
            updatedWall = nowWall
        )
    }

    fun settleUnverifiedIfExpired(
        session: MorningSessionRecord,
        nowWall: Long,
        timeoutMs: Long = UNVERIFIED_TIMEOUT_MS
    ): MorningSessionRecord {
        if (!session.status.isInActivation) return session
        val base = session.updatedWall.takeIf { it > 0L } ?: session.ringStartedWall
        return if (nowWall - base >= timeoutMs) {
            session.copy(status = MorningState.UNVERIFIED, updatedWall = nowWall)
        } else {
            session
        }
    }

    /**
     * Suppresses redundant subsequent morning alarms when the user has already completed Morning
     * Activation recently (within 3h) or has already progressed into Stage 2 / Stage 3 / Extended Sleep.
     */
    fun shouldSkipSubsequentAlarm(
        latestCompletedWall: Long?,
        activeState: MorningState?,
        nowWall: Long,
        windowMs: Long = SKIP_REPEAT_ALARM_WINDOW_MS
    ): Boolean {
        if (activeState == MorningState.STAGE2_PREP ||
            activeState == MorningState.STAGE3_REWARD ||
            activeState == MorningState.EXTENDED_SLEEP
        ) {
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
