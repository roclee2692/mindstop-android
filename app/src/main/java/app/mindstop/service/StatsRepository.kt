package app.mindstop.service

import android.content.Context
import app.mindstop.core.EventRecordInput
import app.mindstop.core.FocusRecordInput
import app.mindstop.core.FocusStatus
import app.mindstop.core.IntentPurpose
import app.mindstop.core.IntentStatus
import app.mindstop.core.IntentionRecordInput
import app.mindstop.core.MindstopStatsSummary
import app.mindstop.core.ReflectionOutcome
import app.mindstop.core.ReflectionReason
import app.mindstop.core.ReflectionRecord
import app.mindstop.core.RewardRecordInput
import app.mindstop.core.RewardStatus
import app.mindstop.core.RewardTrigger
import app.mindstop.core.StatsCalculator
import app.mindstop.core.StatsCsvExporter
import app.mindstop.core.StatsRange
import app.mindstop.core.UsageRecordInput
import app.mindstop.data.MindstopDatabase

/**
 * Loads local behavioral tables from Room (`mindstop_db` v6) for statistics and CSV export
 * (SPEC §17). All data stays strictly on device unless the user explicitly exports a CSV file.
 */
class StatsRepository(context: Context) {

    private val db = MindstopDatabase.getInstance(context)

    private data class Dataset(
        val segments: List<UsageRecordInput>,
        val intentions: List<IntentionRecordInput>,
        val rewards: List<RewardRecordInput>,
        val reflections: List<ReflectionRecord>,
        val focusSessions: List<FocusRecordInput>,
        val events: List<EventRecordInput>
    )

    private suspend fun loadDataset(): Dataset {
        val segments = db.usageSegmentDao().allRecent(MAX_ROWS).map {
            UsageRecordInput(
                dayKey = it.dayKey,
                sessionId = it.sessionId,
                packageName = it.packageName,
                startWall = it.startWall,
                endWall = it.endWall,
                durationMs = it.durationMs,
                mode = it.mode
            )
        }
        val intentions = db.intentionDao().recent(MAX_ROWS).map {
            IntentionRecordInput(
                sessionId = it.sessionId,
                packageName = it.packageName,
                purpose = IntentPurpose.fromCode(it.purpose) ?: IntentPurpose.FUN,
                goal = it.goal.orEmpty(),
                plannedMinutes = it.minutes,
                status = IntentStatus.entries.firstOrNull { s -> s.name == it.status } ?: IntentStatus.ACTIVE,
                wall = it.wall,
                endedWall = it.endedWall
            )
        }
        val rewards = db.rewardDao().allRecent(MAX_ROWS).map {
            RewardRecordInput(
                dayKey = it.dayKey,
                sessionId = it.sessionId,
                packageName = it.packageName,
                trigger = RewardTrigger.fromCode(it.trigger),
                status = RewardStatus.fromCode(it.status),
                energy = it.energy,
                createdWall = it.createdWall
            )
        }
        val reflections = db.reflectionDao().allRecent(MAX_ROWS).map {
            ReflectionRecord(
                id = it.id,
                dayKey = it.dayKey,
                sessionId = it.sessionId,
                packageName = it.packageName,
                purpose = IntentPurpose.fromCode(it.purpose) ?: IntentPurpose.FUN,
                goal = it.goal,
                plannedMinutes = it.plannedMinutes,
                actualMs = it.actualMs,
                outcome = ReflectionOutcome.fromCode(it.outcome),
                reasons = ReflectionReason.decodeList(it.reasons),
                createdWall = it.createdWall
            )
        }
        val focus = db.focusSessionDao().allRecent(MAX_ROWS).map {
            FocusRecordInput(
                dayKey = it.dayKey,
                goal = it.goal,
                targetFocusMs = it.targetFocusMs,
                focusedMs = it.focusedMs,
                interruptMs = it.interruptMs,
                interruptCount = it.interruptCount,
                status = FocusStatus.fromCode(it.status),
                startedWall = it.startedWall,
                endedWall = it.endedWall
            )
        }
        val events = db.eventDao().recent(MAX_ROWS).map {
            EventRecordInput(wall = it.wall, type = it.type, detail = it.detail)
        }
        return Dataset(segments, intentions, rewards, reflections, focus, events)
    }

    suspend fun loadSummary(range: StatsRange, nowWall: Long): MindstopStatsSummary {
        val d = loadDataset()
        return StatsCalculator.compute(
            range = range,
            nowWall = nowWall,
            segments = d.segments,
            intentions = d.intentions,
            rewards = d.rewards,
            reflections = d.reflections,
            focusSessions = d.focusSessions,
            events = d.events
        )
    }

    suspend fun exportCsv(): StatsCsvExporter.ExportResult {
        val d = loadDataset()
        return StatsCsvExporter.build(
            segments = d.segments,
            intentions = d.intentions,
            rewards = d.rewards,
            reflections = d.reflections,
            focusSessions = d.focusSessions,
            events = d.events
        )
    }

    companion object {
        private const val MAX_ROWS = 5_000
    }
}
