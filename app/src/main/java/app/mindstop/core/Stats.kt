package app.mindstop.core

import java.util.Calendar
import java.util.Locale

/**
 * Pure Kotlin behavioral statistics and RFC-4180 CSV exporter (SPEC §17).
 * Computes genuine local metrics without fabricating values or conflating screen-time drops
 * with self-control ability.
 */
enum class StatsRange(val code: String) {
    TODAY("TODAY"),
    LAST_7_DAYS("LAST_7_DAYS"),
    ALL("ALL")
}

data class UsageRecordInput(
    val dayKey: String,
    val sessionId: Long,
    val packageName: String,
    val startWall: Long,
    val endWall: Long,
    val durationMs: Long,
    val mode: String?
)

data class IntentionRecordInput(
    val sessionId: Long,
    val packageName: String,
    val purpose: IntentPurpose,
    val goal: String,
    val plannedMinutes: Int?,
    val status: IntentStatus,
    val wall: Long,
    val endedWall: Long?
)

data class RewardRecordInput(
    val dayKey: String,
    val sessionId: Long,
    val packageName: String,
    val trigger: RewardTrigger,
    val status: RewardStatus,
    val energy: Int,
    val createdWall: Long
)

data class FocusRecordInput(
    val dayKey: String,
    val goal: String,
    val targetFocusMs: Long,
    val focusedMs: Long,
    val interruptMs: Long,
    val interruptCount: Int,
    val status: FocusStatus,
    val startedWall: Long,
    val endedWall: Long
)

data class EventRecordInput(
    val wall: Long,
    val type: String,
    val detail: String
)

data class AppUsageStat(
    val packageName: String,
    val durationMs: Long
)

data class DailyTrendPoint(
    val dayKey: String,
    val funMs: Long,
    val studyMs: Long,
    val focusMs: Long
)

data class MindstopStatsSummary(
    val range: StatsRange,
    val funTotalMs: Long,
    val studyTotalMs: Long,
    val focusCompletedMs: Long,
    val focusInterruptMs: Long,
    val focusCompletedCount: Int,
    val topFunApps: List<AppUsageStat>,
    val dailyTrend: List<DailyTrendPoint>,
    /** Active exits (ACTIVE_EXIT + INTENTION_DECLINE) vs total endings (active + forced blocks). Null if no endings. */
    val voluntaryExitRate: Float?,
    /** Average overtime across sessions with a finite planned time that exceeded their plan. Null if no planned sessions. */
    val avgOvertimeMs: Long?,
    val plannedSessionCount: Int,
    val overtimeSessionCount: Int,
    /** Cancelled rewards / (Confirmed + Cancelled rewards). Null if no resolved verification windows. */
    val reentryRate: Float?,
    /** Self-reported COMPLETED / total study reflections. Null if no study reflections. */
    val studyCompletionRate: Float?,
    val studyReflectionCount: Int,
    val studyCompletedCount: Int,
    val studyPartialCount: Int,
    /** Forced blocks / (Active exits + Forced blocks). Null if no endings. */
    val interventionRelianceRate: Float?,
    val activeExitCount: Int,
    val forcedBlockCount: Int,
    val challengePassedCount: Int,
    val cooldownCount: Int,
    val bedtimeUnplannedCount: Int,
    val topReasons: List<Pair<ReflectionReason, Int>>
)

object StatsCalculator {

    /** Returns [count] consecutive local dayKeys ending at [nowWall] in chronological order (oldest -> newest). */
    fun recentDayKeys(nowWall: Long, count: Int = 7): List<String> {
        val safeCount = count.coerceAtLeast(1)
        val out = ArrayList<String>(safeCount)
        val cal = Calendar.getInstance()
        for (offset in (safeCount - 1) downTo 0) {
            cal.timeInMillis = nowWall
            cal.add(Calendar.DAY_OF_MONTH, -offset)
            out.add(
                String.format(
                    Locale.US,
                    "%04d-%02d-%02d",
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH)
                )
            )
        }
        return out
    }

    fun compute(
        range: StatsRange,
        nowWall: Long,
        segments: List<UsageRecordInput>,
        intentions: List<IntentionRecordInput>,
        rewards: List<RewardRecordInput>,
        reflections: List<ReflectionRecord>,
        focusSessions: List<FocusRecordInput>,
        events: List<EventRecordInput>
    ): MindstopStatsSummary {
        val last7Days = recentDayKeys(nowWall, 7)
        val todayKey = last7Days.last()
        val allowedDays: Set<String>? = when (range) {
            StatsRange.TODAY -> setOf(todayKey)
            StatsRange.LAST_7_DAYS -> last7Days.toSet()
            StatsRange.ALL -> null
        }

        fun inRangeDay(dayKey: String): Boolean = allowedDays == null || dayKey in allowedDays
        fun inRangeWall(wall: Long): Boolean = inRangeDay(DayKeys.dayKey(wall))

        val rSegments = segments.filter { inRangeDay(it.dayKey) }
        val rIntentions = intentions.filter { inRangeWall(it.wall) }
        val rRewards = rewards.filter { inRangeDay(it.dayKey) }
        val rReflections = reflections.filter { inRangeDay(it.dayKey) }
        val rFocus = focusSessions.filter { inRangeDay(it.dayKey) }
        val rEvents = events.filter { inRangeWall(it.wall) }

        var funTotal = 0L
        var studyTotal = 0L
        val funByApp = HashMap<String, Long>()
        for (seg in rSegments) {
            if (seg.mode == "STUDY") {
                studyTotal += seg.durationMs
            } else {
                funTotal += seg.durationMs
                funByApp[seg.packageName] = (funByApp[seg.packageName] ?: 0L) + seg.durationMs
            }
        }
        val topFunApps = funByApp.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { AppUsageStat(it.key, it.value) }

        val completedFocus = rFocus.filter { it.status == FocusStatus.COMPLETED }
        val focusCompletedMs = completedFocus.sumOf { it.focusedMs }
        val focusInterruptMs = rFocus.sumOf { it.interruptMs }

        // 7-day trend always computed across last7Days for a consistent trend chart.
        val funByDay = HashMap<String, Long>()
        val studyByDay = HashMap<String, Long>()
        val focusByDay = HashMap<String, Long>()
        val trendSet = last7Days.toSet()
        for (seg in segments) {
            if (seg.dayKey !in trendSet) continue
            if (seg.mode == "STUDY") {
                studyByDay[seg.dayKey] = (studyByDay[seg.dayKey] ?: 0L) + seg.durationMs
            } else {
                funByDay[seg.dayKey] = (funByDay[seg.dayKey] ?: 0L) + seg.durationMs
            }
        }
        for (fs in focusSessions) {
            if (fs.dayKey in trendSet && fs.status == FocusStatus.COMPLETED) {
                focusByDay[fs.dayKey] = (focusByDay[fs.dayKey] ?: 0L) + fs.focusedMs
            }
        }
        val dailyTrend = last7Days.map { dk ->
            DailyTrendPoint(
                dayKey = dk,
                funMs = funByDay[dk] ?: 0L,
                studyMs = studyByDay[dk] ?: 0L,
                focusMs = focusByDay[dk] ?: 0L
            )
        }

        // Planned vs actual overtime across sessions that had a finite plan.
        val actualBySession = HashMap<Long, Long>()
        for (seg in segments) {
            actualBySession[seg.sessionId] = (actualBySession[seg.sessionId] ?: 0L) + seg.durationMs
        }
        var plannedCount = 0
        var overtimeCount = 0
        var overtimeSumMs = 0L
        for (intent in rIntentions) {
            val planMin = intent.plannedMinutes ?: continue
            if (planMin <= 0) continue
            plannedCount++
            val actualMs = actualBySession[intent.sessionId] ?: 0L
            val planMs = planMin * 60_000L
            if (actualMs > planMs) {
                overtimeCount++
                overtimeSumMs += (actualMs - planMs)
            }
        }
        val avgOvertimeMs = when {
            plannedCount == 0 -> null
            overtimeCount == 0 -> 0L
            else -> overtimeSumMs / overtimeCount
        }

        // Event counts
        val activeExits = rEvents.count { it.type == "session_exit" || it.type == "intention_left" }
        val forcedBlocks = rEvents.count { it.type == "block_shown" || it.type == "bedtime_block_shown" }
        val challengePassed = rEvents.count { it.type == "challenge_passed" }
        val cooldowns = rEvents.count { it.type == "cooldown_started" }
        val bedtimeUnplanned = rEvents.count { it.type == "bedtime_unplanned_use" }

        val endings = activeExits + forcedBlocks
        val voluntaryExitRate = if (endings > 0) activeExits.toFloat() / endings else null
        val interventionRelianceRate = if (endings > 0) forcedBlocks.toFloat() / endings else null

        // Post-exit re-entry rate from resolved verification windows (excluding focus rewards).
        val exitRewards = rRewards.filter { it.trigger != RewardTrigger.FOCUS_COMPLETE }
        val confirmed = exitRewards.count { it.status == RewardStatus.CONFIRMED }
        val cancelled = exitRewards.count { it.status == RewardStatus.CANCELLED }
        val resolved = confirmed + cancelled
        val reentryRate = if (resolved > 0) cancelled.toFloat() / resolved else null

        // Self-reported study reflection completion rate + top deviation reasons.
        val studyRefs = rReflections.filter { it.purpose == IntentPurpose.STUDY }
        val studyCompleted = studyRefs.count { it.outcome == ReflectionOutcome.COMPLETED }
        val studyPartial = studyRefs.count { it.outcome == ReflectionOutcome.PARTIAL }
        val studyCompletionRate = if (studyRefs.isNotEmpty()) studyCompleted.toFloat() / studyRefs.size else null

        val reasonCounts = HashMap<ReflectionReason, Int>()
        for (ref in rReflections) {
            for (reason in ref.reasons) {
                reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
            }
        }
        val topReasons = reasonCounts.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

        return MindstopStatsSummary(
            range = range,
            funTotalMs = funTotal,
            studyTotalMs = studyTotal,
            focusCompletedMs = focusCompletedMs,
            focusInterruptMs = focusInterruptMs,
            focusCompletedCount = completedFocus.size,
            topFunApps = topFunApps,
            dailyTrend = dailyTrend,
            voluntaryExitRate = voluntaryExitRate,
            avgOvertimeMs = avgOvertimeMs,
            plannedSessionCount = plannedCount,
            overtimeSessionCount = overtimeCount,
            reentryRate = reentryRate,
            studyCompletionRate = studyCompletionRate,
            studyReflectionCount = studyRefs.size,
            studyCompletedCount = studyCompleted,
            studyPartialCount = studyPartial,
            interventionRelianceRate = interventionRelianceRate,
            activeExitCount = activeExits,
            forcedBlockCount = forcedBlocks,
            challengePassedCount = challengePassed,
            cooldownCount = cooldowns,
            bedtimeUnplannedCount = bedtimeUnplanned,
            topReasons = topReasons
        )
    }
}

/** Exports genuine local Mindstop behavioral tables into a UTF-8 BOM RFC-4180 CSV file. */
object StatsCsvExporter {

    private const val UTF8_BOM = "\uFEFF"
    const val HEADER =
        "record_type,day_key,timestamp_ms,package_name,purpose_or_mode,planned_min,actual_or_focus_ms,outcome_or_status,detail"

    data class ExportResult(
        val csv: String,
        val rowCount: Int
    )

    fun build(
        segments: List<UsageRecordInput>,
        intentions: List<IntentionRecordInput>,
        rewards: List<RewardRecordInput>,
        reflections: List<ReflectionRecord>,
        focusSessions: List<FocusRecordInput>,
        events: List<EventRecordInput>
    ): ExportResult {
        val sb = StringBuilder(4096)
        sb.append(UTF8_BOM).append(HEADER).append("\r\n")
        var rows = 0

        fun addRow(
            type: String,
            dayKey: String,
            ts: Long,
            pkg: String,
            mode: String,
            plannedMin: String,
            durationMs: String,
            status: String,
            detail: String
        ) {
            sb.append(escape(type)).append(',')
                .append(escape(dayKey)).append(',')
                .append(ts).append(',')
                .append(escape(pkg)).append(',')
                .append(escape(mode)).append(',')
                .append(escape(plannedMin)).append(',')
                .append(escape(durationMs)).append(',')
                .append(escape(status)).append(',')
                .append(escape(detail)).append("\r\n")
            rows++
        }

        for (seg in segments) {
            addRow(
                type = "usage_segment",
                dayKey = seg.dayKey,
                ts = seg.startWall,
                pkg = seg.packageName,
                mode = seg.mode ?: "FUN",
                plannedMin = "",
                durationMs = seg.durationMs.toString(),
                status = "RECORDED",
                detail = "session=${seg.sessionId}"
            )
        }
        for (it in intentions) {
            addRow(
                type = "intention",
                dayKey = DayKeys.dayKey(it.wall),
                ts = it.wall,
                pkg = it.packageName,
                mode = it.purpose.code,
                plannedMin = it.plannedMinutes?.toString().orEmpty(),
                durationMs = "",
                status = it.status.name,
                detail = it.goal
            )
        }
        for (rw in rewards) {
            addRow(
                type = "reward",
                dayKey = rw.dayKey,
                ts = rw.createdWall,
                pkg = rw.packageName,
                mode = rw.trigger.code,
                plannedMin = "",
                durationMs = "",
                status = rw.status.code,
                detail = "energy=${rw.energy}"
            )
        }
        for (rf in reflections) {
            addRow(
                type = "reflection",
                dayKey = rf.dayKey,
                ts = rf.createdWall,
                pkg = rf.packageName,
                mode = rf.purpose.code,
                plannedMin = rf.plannedMinutes?.toString().orEmpty(),
                durationMs = rf.actualMs.toString(),
                status = rf.outcome.code,
                detail = listOfNotNull(
                    rf.goal.takeIf { it.isNotBlank() },
                    ReflectionReason.encodeList(rf.reasons).takeIf { it.isNotBlank() }?.let { "reasons=$it" }
                ).joinToString(" | ")
            )
        }
        for (fs in focusSessions) {
            addRow(
                type = "focus_session",
                dayKey = fs.dayKey,
                ts = fs.startedWall,
                pkg = "",
                mode = "FOCUS",
                plannedMin = (fs.targetFocusMs / 60_000L).toString(),
                durationMs = fs.focusedMs.toString(),
                status = fs.status.code,
                detail = "interrupt_ms=${fs.interruptMs};count=${fs.interruptCount};goal=${fs.goal}"
            )
        }
        for (ev in events) {
            addRow(
                type = "event",
                dayKey = DayKeys.dayKey(ev.wall),
                ts = ev.wall,
                pkg = "",
                mode = ev.type,
                plannedMin = "",
                durationMs = "",
                status = "",
                detail = ev.detail
            )
        }

        return ExportResult(sb.toString(), rows)
    }

    internal fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
