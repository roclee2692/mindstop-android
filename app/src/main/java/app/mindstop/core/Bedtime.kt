package app.mindstop.core

import java.util.Calendar
import java.util.Locale

/**
 * Unified personal sleep schedule for a single day type (workday or weekend) (v0.3 SPEC §4–§5).
 * Shared by Module 1 (Bedtime Wind-down) and Module 2 (Morning Activation).
 */
data class SleepDaySchedule(
    /** Planned bedtime (minutes from local midnight, default 23:00 = 1380). */
    val plannedSleepMinutesOfDay: Int = 23 * 60,
    /** Planned wake time (minutes from local midnight, default 07:00 = 420). */
    val plannedWakeMinutesOfDay: Int = 7 * 60,
    /** Latest time entertainment should stop before buffer (default 22:50 = 1370). */
    val funEndMinutesOfDay: Int = 22 * 60 + 50,
    /** Evening free-rest window length before wind-down prep (default 30 min, e.g. 22:00-22:30). */
    val freeRestMinutes: Int = 30,
    /** Buffer minutes between funEnd and plannedSleep (default 10 min, e.g. 22:50-23:00). */
    val bufferMinutes: Int = 10,
    /** First wind-down prep reminder lead time before funEnd (default 20 min -> 22:30). */
    val windDownLeadMinutes: Int = 20,
    /** Final countdown reminder lead time before funEnd (default 5 min -> 22:45). */
    val countdownLeadMinutes: Int = 5
) {
    /** Planned sleep opportunity in minutes (overnight safe, e.g. 23:00 -> 07:00 = 480m). */
    val plannedSleepOpportunityMinutes: Int
        get() {
            val diff = (plannedWakeMinutesOfDay - plannedSleepMinutesOfDay + MINUTES_PER_DAY) % MINUTES_PER_DAY
            return if (diff == 0) MINUTES_PER_DAY else diff
        }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun defaultWorkday() = SleepDaySchedule(
            plannedSleepMinutesOfDay = 23 * 60,
            plannedWakeMinutesOfDay = 7 * 60,
            funEndMinutesOfDay = 22 * 60 + 50,
            freeRestMinutes = 30,
            bufferMinutes = 10,
            windDownLeadMinutes = 20,
            countdownLeadMinutes = 5
        )

        fun defaultWeekend() = SleepDaySchedule(
            plannedSleepMinutesOfDay = 23 * 60 + 30,
            plannedWakeMinutesOfDay = 8 * 60,
            funEndMinutesOfDay = 23 * 60 + 20,
            freeRestMinutes = 40,
            bufferMinutes = 10,
            windDownLeadMinutes = 20,
            countdownLeadMinutes = 5
        )
    }
}

/**
 * Bedtime Wind-down & Unified Sleep Plan settings (SPEC §16 + v0.3 §4–§8).
 * Backwards-compatible with Stage 5 `bedtime_settings` encoding in `meta`.
 */
data class BedtimeSettings(
    val enabled: Boolean = false,
    /** Primary (workday) planned sleep start (minutes from midnight; backwards-compatible alias). */
    val startMinutesOfDay: Int = 23 * 60,
    /** Primary (workday) planned wake / morning end (minutes from midnight). */
    val endMinutesOfDay: Int = 7 * 60,
    /** Pause restricted entertainment apps during buffer & sleep protection window. */
    val pauseEntertainment: Boolean = true,
    /**
     * Stricter challenge renewal cap at night. Default 0: high-intensity math is not recommended
     * before sleep (v0.3 §6). User can still manually override via emergency unlock.
     */
    val maxBedtimeRenewals: Int = 0,
    /** Longer cooldown duration during bedtime (minutes). */
    val bedtimeCooldownMinutes: Int = 120,
    /** Reduce animations & visual stimulation at night (low-brightness calm palette). */
    val reduceStimulation: Boolean = true,
    /** Reserved / shared planned wake time (workday). */
    val plannedWakeMinutesOfDay: Int = 7 * 60,
    // --- v0.3 Phase 1 additions (all have safe defaults for v6 migration) ---
    /** Latest entertainment end time on workdays (default 22:50). */
    val funEndMinutesOfDay: Int = 22 * 60 + 50,
    /** Buffer minutes between entertainment end and sleep protection (default 10m). */
    val bufferMinutes: Int = 10,
    /** Preserved evening free-rest minutes before wind-down reminder (default 30m). */
    val freeRestMinutes: Int = 30,
    /** Wind-down prep reminder lead minutes before funEnd (default 20m -> 22:30). */
    val windDownLeadMinutes: Int = 20,
    /** Final remaining-time reminder lead minutes before funEnd (default 5m -> 22:45). */
    val countdownLeadMinutes: Int = 5,
    /** Enable separate weekend (Fri night / Sat night -> Sat / Sun) schedule. */
    val weekendSeparate: Boolean = false,
    val weekendSleepMinutesOfDay: Int = 23 * 60 + 30,
    val weekendWakeMinutesOfDay: Int = 8 * 60,
    val weekendFunEndMinutesOfDay: Int = 23 * 60 + 20,
    val weekendBufferMinutes: Int = 10,
    val weekendFreeRestMinutes: Int = 40,
    /** When true, nighttime restriction extends all the way until morning wake time (not just cooldown). */
    val restrictUntilMorning: Boolean = true,
    /** Optional grayscale / low-stimulation hint at night (requires system/Shizuku support). */
    val grayscalePreferred: Boolean = false,
    /**
     * Optional explicit set of packages restricted at night (e.g. short video, games, shopping).
     * When empty, falls back to all enabled supervised rules (excluding exempt & nightAllowedPackages).
     */
    val nightRestrictedPackages: Set<String> = emptySet(),
    /** Packages explicitly allowed during night (e.g. e-book readers, music, alarm, podcasts). */
    val nightAllowedPackages: Set<String> = emptySet()
) {
    fun workdaySchedule(): SleepDaySchedule = SleepDaySchedule(
        plannedSleepMinutesOfDay = startMinutesOfDay,
        plannedWakeMinutesOfDay = endMinutesOfDay,
        funEndMinutesOfDay = funEndMinutesOfDay,
        freeRestMinutes = freeRestMinutes,
        bufferMinutes = bufferMinutes,
        windDownLeadMinutes = windDownLeadMinutes,
        countdownLeadMinutes = countdownLeadMinutes
    )

    fun weekendSchedule(): SleepDaySchedule = if (!weekendSeparate) {
        workdaySchedule()
    } else {
        SleepDaySchedule(
            plannedSleepMinutesOfDay = weekendSleepMinutesOfDay,
            plannedWakeMinutesOfDay = weekendWakeMinutesOfDay,
            funEndMinutesOfDay = weekendFunEndMinutesOfDay,
            freeRestMinutes = weekendFreeRestMinutes,
            bufferMinutes = weekendBufferMinutes,
            windDownLeadMinutes = windDownLeadMinutes,
            countdownLeadMinutes = countdownLeadMinutes
        )
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun encode(s: BedtimeSettings): String = listOf(
            "en=${if (s.enabled) 1 else 0}",
            "start=${s.startMinutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)}",
            "end=${s.endMinutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)}",
            "pause=${if (s.pauseEntertainment) 1 else 0}",
            "renew=${s.maxBedtimeRenewals.coerceIn(0, 5)}",
            "cd=${s.bedtimeCooldownMinutes.coerceIn(1, 720)}",
            "calm=${if (s.reduceStimulation) 1 else 0}",
            "wake=${s.plannedWakeMinutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)}",
            "funEnd=${s.funEndMinutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)}",
            "buf=${s.bufferMinutes.coerceIn(0, 120)}",
            "free=${s.freeRestMinutes.coerceIn(0, 180)}",
            "wLead=${s.windDownLeadMinutes.coerceIn(5, 120)}",
            "cLead=${s.countdownLeadMinutes.coerceIn(1, 60)}",
            "wknd=${if (s.weekendSeparate) 1 else 0}",
            "wSleep=${s.weekendSleepMinutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)}",
            "wWake=${s.weekendWakeMinutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)}",
            "wFunEnd=${s.weekendFunEndMinutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)}",
            "wBuf=${s.weekendBufferMinutes.coerceIn(0, 120)}",
            "wFree=${s.weekendFreeRestMinutes.coerceIn(0, 180)}",
            "morn=${if (s.restrictUntilMorning) 1 else 0}",
            "gray=${if (s.grayscalePreferred) 1 else 0}",
            "nPkgs=${s.nightRestrictedPackages.sorted().joinToString(",")}",
            "aPkgs=${s.nightAllowedPackages.sorted().joinToString(",")}"
        ).joinToString(";")

        fun decode(raw: String?): BedtimeSettings {
            if (raw.isNullOrBlank()) return BedtimeSettings()
            val map = HashMap<String, String>()
            for (part in raw.split(';')) {
                val eq = part.indexOf('=')
                if (eq > 0) map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            val start = map["start"]?.toIntOrNull()?.coerceIn(0, MINUTES_PER_DAY - 1) ?: (23 * 60)
            val end = map["end"]?.toIntOrNull()?.coerceIn(0, MINUTES_PER_DAY - 1) ?: (7 * 60)
            val defaultFunEnd = (start - 10 + MINUTES_PER_DAY) % MINUTES_PER_DAY
            val funEnd = map["funEnd"]?.toIntOrNull()?.coerceIn(0, MINUTES_PER_DAY - 1) ?: defaultFunEnd
            val wSleep = map["wSleep"]?.toIntOrNull()?.coerceIn(0, MINUTES_PER_DAY - 1) ?: (23 * 60 + 30)
            val defaultWFunEnd = (wSleep - 10 + MINUTES_PER_DAY) % MINUTES_PER_DAY
            return BedtimeSettings(
                enabled = map["en"] == "1",
                startMinutesOfDay = start,
                endMinutesOfDay = end,
                pauseEntertainment = map["pause"]?.let { it != "0" } ?: true,
                maxBedtimeRenewals = map["renew"]?.toIntOrNull()?.coerceIn(0, 5) ?: 0,
                bedtimeCooldownMinutes = map["cd"]?.toIntOrNull()?.coerceIn(1, 720) ?: 120,
                reduceStimulation = map["calm"]?.let { it != "0" } ?: true,
                plannedWakeMinutesOfDay = map["wake"]?.toIntOrNull()?.coerceIn(0, MINUTES_PER_DAY - 1) ?: end,
                funEndMinutesOfDay = funEnd,
                bufferMinutes = map["buf"]?.toIntOrNull()?.coerceIn(0, 120) ?: 10,
                freeRestMinutes = map["free"]?.toIntOrNull()?.coerceIn(0, 180) ?: 30,
                windDownLeadMinutes = map["wLead"]?.toIntOrNull()?.coerceIn(5, 120) ?: 20,
                countdownLeadMinutes = map["cLead"]?.toIntOrNull()?.coerceIn(1, 60) ?: 5,
                weekendSeparate = map["wknd"] == "1",
                weekendSleepMinutesOfDay = wSleep,
                weekendWakeMinutesOfDay = map["wWake"]?.toIntOrNull()?.coerceIn(0, MINUTES_PER_DAY - 1) ?: (8 * 60),
                weekendFunEndMinutesOfDay = map["wFunEnd"]?.toIntOrNull()?.coerceIn(0, MINUTES_PER_DAY - 1) ?: defaultWFunEnd,
                weekendBufferMinutes = map["wBuf"]?.toIntOrNull()?.coerceIn(0, 120) ?: 10,
                weekendFreeRestMinutes = map["wFree"]?.toIntOrNull()?.coerceIn(0, 180) ?: 40,
                restrictUntilMorning = map["morn"]?.let { it != "0" } ?: true,
                grayscalePreferred = map["gray"] == "1",
                nightRestrictedPackages = map["nPkgs"].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                nightAllowedPackages = map["aPkgs"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
            )
        }
    }
}

/** Reserved / shared data structure for Module 2 Wake-up Confirmation (SPEC §16 / v0.3 §10–§12). */
enum class WakeupConfirmMethod(val code: String) {
    MANUAL("MANUAL"),
    MATH("MATH"),
    STEPS("STEPS"),
    QR("QR"),
    NFC("NFC")
}

data class WakeupPlan(
    val enabled: Boolean = false,
    val plannedWakeMinutesOfDay: Int = 7 * 60,
    val confirmMethod: WakeupConfirmMethod = WakeupConfirmMethod.MANUAL
)

/** Stage 5 legacy reminder node (kept for full compatibility). */
enum class BedtimeReminderNode(val code: String, val minutesBefore: Int) {
    BEFORE_15M("bedtime_15m", 15),
    BEFORE_5M("bedtime_5m", 5)
}

/** Progressive evening wind-down phases (v0.3 §5). */
enum class BedtimePhase(val code: String) {
    DAYTIME("daytime"),
    FREE_REST("free_rest"),
    WIND_DOWN_PREP("wind_down_prep"),
    WIND_DOWN_COUNTDOWN("wind_down_countdown"),
    BUFFER("buffer"),
    SLEEP_PROTECTION("sleep_protection")
}

data class BedtimeEvaluation(
    val phase: BedtimePhase,
    val schedule: SleepDaySchedule,
    val isWeekend: Boolean,
    /** Evening date key (e.g. 2026-11-26 for the night of Nov 26 -> morning of Nov 27). */
    val nightKey: String,
    /** Minutes remaining until funEnd (0 if already past funEnd, null in DAYTIME). */
    val minutesUntilFunEnd: Int?,
    /** Minutes past funEnd (0 if not yet past funEnd). */
    val minutesPastFunEnd: Int,
    /** Minutes past plannedSleep (0 if not yet past plannedSleep). */
    val minutesPastSleep: Int,
    /** Planned sleep opportunity in minutes based on schedule. */
    val plannedSleepOpportunityMinutes: Int,
    /** Remaining planned sleep opportunity if the user sleeps right now (always <= planned). */
    val remainingSleepOpportunityMinutes: Int
) {
    val isNightRestrictedPhase: Boolean
        get() = phase == BedtimePhase.BUFFER || phase == BedtimePhase.SLEEP_PROTECTION

    val isEveningWindDownOrNight: Boolean
        get() = phase != BedtimePhase.DAYTIME
}

object BedtimeEngine {

    /** Local minutes from midnight [0, 1439] for [wallMs]. */
    fun minuteOfDay(wallMs: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = wallMs
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /**
     * Determines whether the night belonging to [wallMs] uses the weekend schedule.
     * For evening hours (>= 12:00): Friday & Saturday nights lead into Saturday & Sunday mornings.
     * For early morning hours (< 12:00): Saturday & Sunday mornings belong to the weekend night.
     */
    fun isWeekendNight(wallMs: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = wallMs
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return if (hour >= 12) {
            dow == Calendar.FRIDAY || dow == Calendar.SATURDAY
        } else {
            dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        }
    }

    /**
     * Stable evening date key for a night spanning midnight:
     * times before 12:00 noon belong to the previous calendar date's night.
     */
    fun nightKeyFor(wallMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = wallMs
        if (cal.get(Calendar.HOUR_OF_DAY) < 12) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun activeSchedule(settings: BedtimeSettings, isWeekend: Boolean): SleepDaySchedule =
        if (isWeekend) settings.weekendSchedule() else settings.workdaySchedule()

    /** Evaluates the complete progressive wind-down state for [settings] at [minuteOfDay]. */
    fun evaluate(
        settings: BedtimeSettings,
        minuteOfDay: Int,
        isWeekend: Boolean,
        nightKey: String
    ): BedtimeEvaluation {
        val schedule = activeSchedule(settings, isWeekend)
        val plannedOpp = schedule.plannedSleepOpportunityMinutes
        if (!settings.enabled) {
            return BedtimeEvaluation(
                phase = BedtimePhase.DAYTIME,
                schedule = schedule,
                isWeekend = isWeekend,
                nightKey = nightKey,
                minutesUntilFunEnd = null,
                minutesPastFunEnd = 0,
                minutesPastSleep = 0,
                plannedSleepOpportunityMinutes = plannedOpp,
                remainingSleepOpportunityMinutes = plannedOpp
            )
        }

        val wake = schedule.plannedWakeMinutesOfDay
        val sleep = schedule.plannedSleepMinutesOfDay
        val funEnd = schedule.funEndMinutesOfDay

        // Compute signed distance relative to funEnd using an overnight-safe 24h ring.
        // We anchor the evening around funEnd: [-freeWindow .. 0) is evening wind-down,
        // [0 .. untilWake) is buffer + sleep protection.
        val untilWakeFromFunEnd = ringForward(funEnd, wake)
        val sinceFunEnd = ringForward(funEnd, minuteOfDay)
        val inBufferOrSleep = sinceFunEnd < untilWakeFromFunEnd && untilWakeFromFunEnd > 0

        if (inBufferOrSleep) {
            val bufferSpan = ringForward(funEnd, sleep).coerceAtMost(untilWakeFromFunEnd)
            val pastSleep = (sinceFunEnd - bufferSpan).coerceAtLeast(0)
            val phase = if (sinceFunEnd < bufferSpan) BedtimePhase.BUFFER else BedtimePhase.SLEEP_PROTECTION
            val remainingOpp = (untilWakeFromFunEnd - sinceFunEnd).coerceIn(0, plannedOpp)
            return BedtimeEvaluation(
                phase = phase,
                schedule = schedule,
                isWeekend = isWeekend,
                nightKey = nightKey,
                minutesUntilFunEnd = 0,
                minutesPastFunEnd = sinceFunEnd,
                minutesPastSleep = pastSleep,
                plannedSleepOpportunityMinutes = plannedOpp,
                remainingSleepOpportunityMinutes = remainingOpp
            )
        }

        val untilFunEnd = ringForward(minuteOfDay, funEnd)
        val countdownLead = schedule.countdownLeadMinutes.coerceAtLeast(1)
        val windDownLead = schedule.windDownLeadMinutes.coerceAtLeast(countdownLead + 1)
        val freeRestEdge = windDownLead + schedule.freeRestMinutes.coerceAtLeast(0)

        val phase = when {
            untilFunEnd <= countdownLead -> BedtimePhase.WIND_DOWN_COUNTDOWN
            untilFunEnd <= windDownLead -> BedtimePhase.WIND_DOWN_PREP
            untilFunEnd <= freeRestEdge -> BedtimePhase.FREE_REST
            else -> BedtimePhase.DAYTIME
        }
        return BedtimeEvaluation(
            phase = phase,
            schedule = schedule,
            isWeekend = isWeekend,
            nightKey = nightKey,
            minutesUntilFunEnd = if (phase == BedtimePhase.DAYTIME) null else untilFunEnd,
            minutesPastFunEnd = 0,
            minutesPastSleep = 0,
            plannedSleepOpportunityMinutes = plannedOpp,
            remainingSleepOpportunityMinutes = plannedOpp
        )
    }

    fun evaluateAtWall(settings: BedtimeSettings, wallMs: Long): BedtimeEvaluation =
        evaluate(
            settings = settings,
            minuteOfDay = minuteOfDay(wallMs),
            isWeekend = isWeekendNight(wallMs),
            nightKey = nightKeyFor(wallMs)
        )

    /**
     * Decides whether [packageName] is subject to nighttime restriction (v0.3 §6).
     * Exempt essential apps and explicitly allowed night apps (reading/music/alarm) are never blocked.
     */
    fun isRestrictedAtNight(
        settings: BedtimeSettings,
        packageName: String,
        supervisedByRules: Boolean,
        exemptPackages: Set<String>
    ): Boolean {
        if (!settings.enabled || !settings.pauseEntertainment) return false
        if (packageName in exemptPackages) return false
        if (packageName in settings.nightAllowedPackages) return false
        return if (settings.nightRestrictedPackages.isNotEmpty()) {
            packageName in settings.nightRestrictedPackages
        } else {
            supervisedByRules
        }
    }

    /** True when bedtime mode is enabled and [minuteOfDay] falls inside [start, end) (handling overnight wrap). */
    fun isInBedtimeWindow(settings: BedtimeSettings, minuteOfDay: Int): Boolean {
        if (!settings.enabled) return false
        val start = settings.startMinutesOfDay
        val end = settings.endMinutesOfDay
        if (start == end) return false
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    /** Minutes until bedtime starts (0 when already inside bedtime window, null when disabled). */
    fun minutesUntilStart(settings: BedtimeSettings, minuteOfDay: Int): Int? {
        if (!settings.enabled) return null
        if (isInBedtimeWindow(settings, minuteOfDay)) return 0
        val diff = settings.startMinutesOfDay - minuteOfDay
        return if (diff >= 0) diff else diff + BedtimeSettings.MINUTES_PER_DAY
    }

    /** Legacy helper kept for Stage 5 compatibility. */
    fun approachingReminder(settings: BedtimeSettings, minuteOfDay: Int): BedtimeReminderNode? {
        val until = minutesUntilStart(settings, minuteOfDay) ?: return null
        if (until <= 0) return null
        return when {
            until <= 5 -> BedtimeReminderNode.BEFORE_5M
            until <= 15 -> BedtimeReminderNode.BEFORE_15M
            else -> null
        }
    }

    fun formatMinuteOfDay(minutes: Int): String {
        val clamped = ((minutes % BedtimeSettings.MINUTES_PER_DAY) + BedtimeSettings.MINUTES_PER_DAY) % BedtimeSettings.MINUTES_PER_DAY
        return String.format(Locale.US, "%02d:%02d", clamped / 60, clamped % 60)
    }

    fun formatHoursMinutes(minutes: Int): Pair<Int, Int> {
        val safe = minutes.coerceAtLeast(0)
        return (safe / 60) to (safe % 60)
    }

    private fun ringForward(fromMin: Int, toMin: Int): Int =
        ((toMin - fromMin) % BedtimeSettings.MINUTES_PER_DAY + BedtimeSettings.MINUTES_PER_DAY) % BedtimeSettings.MINUTES_PER_DAY
}
