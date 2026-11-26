package app.mindstop.service

import android.content.Context
import app.mindstop.core.ActiveFocusState
import app.mindstop.core.BedtimeEvaluation
import app.mindstop.core.BedtimeSettings
import app.mindstop.core.DayKeys
import app.mindstop.core.FocusEngine
import app.mindstop.core.FocusStatus
import app.mindstop.core.IntentPurpose
import app.mindstop.core.ReflectionOutcome
import app.mindstop.core.ReflectionReason
import app.mindstop.core.ReflectionRecord
import app.mindstop.data.BedtimeLogEntity
import app.mindstop.data.EventEntity
import app.mindstop.data.FocusSessionEntity
import app.mindstop.data.MetaEntity
import app.mindstop.data.MindstopDatabase
import app.mindstop.data.ReflectionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local persistence for Stage 5 features:
 * - Lightweight reflections (SPEC §14)
 * - Elastic Focus Mode active state + completed/abandoned history (SPEC §15)
 * - Bedtime Mode configuration (SPEC §16)
 */
class Stage5Repository(context: Context) {

    private val db = MindstopDatabase.getInstance(context)
    private val rewards = RewardRepository(context)

    // Bedtime settings

    suspend fun loadBedtime(): BedtimeSettings =
        BedtimeSettings.decode(db.metaDao().get(KEY_BEDTIME_SETTINGS))

    suspend fun saveBedtime(settings: BedtimeSettings, nowWall: Long) {
        val encoded = BedtimeSettings.encode(settings)
        db.metaDao().put(MetaEntity(KEY_BEDTIME_SETTINGS, encoded))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "bedtime_settings_changed", detail = encoded))
    }

    // Nightly Bedtime Wind-down behavioral logs (v0.3 SPEC §8)

    fun observeRecentBedtimeLogs(limit: Int = 7): Flow<List<BedtimeLogEntity>> =
        db.bedtimeLogDao().observeRecent(limit)

    suspend fun recordNightUsage(eval: BedtimeEvaluation, nowWall: Long) {
        val cur = getOrCreateNightLog(eval, nowWall)
        val overtime = maxOf(cur.overtimeMinutes, eval.minutesPastFunEnd)
        db.bedtimeLogDao().upsert(
            cur.copy(
                lastSupervisedWall = nowWall,
                overtimeMinutes = overtime,
                updatedWall = nowWall
            )
        )
    }

    suspend fun recordNightReopen(eval: BedtimeEvaluation, nowWall: Long) {
        val cur = getOrCreateNightLog(eval, nowWall)
        db.bedtimeLogDao().upsert(
            cur.copy(
                lastSupervisedWall = nowWall,
                overtimeMinutes = maxOf(cur.overtimeMinutes, eval.minutesPastFunEnd),
                reopenCount = cur.reopenCount + 1,
                updatedWall = nowWall
            )
        )
    }

    suspend fun recordNightVoluntaryStop(eval: BedtimeEvaluation, nowWall: Long) {
        val cur = getOrCreateNightLog(eval, nowWall)
        db.bedtimeLogDao().upsert(
            cur.copy(
                voluntaryStopCount = cur.voluntaryStopCount + 1,
                updatedWall = nowWall
            )
        )
    }

    suspend fun recordNightForcedBlock(eval: BedtimeEvaluation, nowWall: Long) {
        val cur = getOrCreateNightLog(eval, nowWall)
        db.bedtimeLogDao().upsert(
            cur.copy(
                overtimeMinutes = maxOf(cur.overtimeMinutes, eval.minutesPastFunEnd),
                forcedBlockCount = cur.forcedBlockCount + 1,
                updatedWall = nowWall
            )
        )
    }

    suspend fun saveSelfReportedSleep(nightKey: String, sleepWall: Long, eval: BedtimeEvaluation, nowWall: Long) {
        val existing = db.bedtimeLogDao().forNight(nightKey)
        val base = existing ?: BedtimeLogEntity(
            nightKey = nightKey,
            isWeekend = eval.isWeekend,
            plannedFunEndMin = eval.schedule.funEndMinutesOfDay,
            plannedSleepMin = eval.schedule.plannedSleepMinutesOfDay,
            plannedWakeMin = eval.schedule.plannedWakeMinutesOfDay,
            lastSupervisedWall = null,
            overtimeMinutes = 0,
            reopenCount = 0,
            voluntaryStopCount = 0,
            forcedBlockCount = 0,
            selfReportedSleepWall = null,
            updatedWall = nowWall
        )
        db.bedtimeLogDao().upsert(base.copy(selfReportedSleepWall = sleepWall, updatedWall = nowWall))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "bedtime_sleep_reported", detail = "night=$nightKey wall=$sleepWall"))
    }

    private suspend fun getOrCreateNightLog(eval: BedtimeEvaluation, nowWall: Long): BedtimeLogEntity =
        db.bedtimeLogDao().forNight(eval.nightKey) ?: BedtimeLogEntity(
            nightKey = eval.nightKey,
            isWeekend = eval.isWeekend,
            plannedFunEndMin = eval.schedule.funEndMinutesOfDay,
            plannedSleepMin = eval.schedule.plannedSleepMinutesOfDay,
            plannedWakeMin = eval.schedule.plannedWakeMinutesOfDay,
            lastSupervisedWall = null,
            overtimeMinutes = 0,
            reopenCount = 0,
            voluntaryStopCount = 0,
            forcedBlockCount = 0,
            selfReportedSleepWall = null,
            updatedWall = nowWall
        )

    // Active & historical Elastic Focus sessions

    suspend fun loadActiveFocus(): ActiveFocusState? =
        FocusEngine.decode(db.metaDao().get(KEY_ACTIVE_FOCUS))

    suspend fun saveActiveFocus(state: ActiveFocusState) {
        db.metaDao().put(MetaEntity(KEY_ACTIVE_FOCUS, FocusEngine.encode(state)))
    }

    suspend fun clearActiveFocus() {
        db.metaDao().put(MetaEntity(KEY_ACTIVE_FOCUS, ""))
    }

    suspend fun loadFocusWhitelist(): Set<String> =
        db.metaDao().get(KEY_FOCUS_WHITELIST)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    suspend fun saveFocusWhitelist(packages: Set<String>) {
        db.metaDao().put(MetaEntity(KEY_FOCUS_WHITELIST, packages.sorted().joinToString(",")))
    }

    suspend fun finishFocusSession(state: ActiveFocusState, endedWall: Long): Boolean {
        clearActiveFocus()
        val finalStatus = if (state.status == FocusStatus.COMPLETED) FocusStatus.COMPLETED else FocusStatus.ABANDONED
        db.focusSessionDao().insert(
            FocusSessionEntity(
                dayKey = state.dayKey,
                goal = state.goal,
                targetFocusMs = state.targetFocusMs,
                interruptBudgetMs = state.interruptBudgetMs,
                focusedMs = state.focusedMs,
                interruptMs = state.interruptMs,
                interruptCount = state.interruptCount,
                whitelistPackages = state.whitelistPackages.sorted().joinToString(","),
                status = finalStatus.code,
                startedWall = state.startedWall,
                endedWall = endedWall
            )
        )
        db.eventDao().insert(
            EventEntity(
                wall = endedWall,
                type = if (finalStatus == FocusStatus.COMPLETED) "focus_completed" else "focus_abandoned",
                detail = "focused=${state.focusedMs / 60_000}m interrupt=${state.interruptMs / 60_000}m count=${state.interruptCount}"
            )
        )
        if (finalStatus == FocusStatus.COMPLETED) {
            rewards.grantFocusCompleteReward(state.sessionId, endedWall)
            return true
        }
        return false
    }

    suspend fun completedFocusTodayMs(dayKey: String): Long =
        db.focusSessionDao().completedTotalMsForDay(dayKey)

    fun observeRecentFocus(limit: Int = 10): Flow<List<FocusSessionEntity>> =
        db.focusSessionDao().observeRecent(limit)

    // Reflections

    suspend fun saveReflection(
        sessionId: Long,
        packageName: String,
        purpose: IntentPurpose,
        goal: String,
        plannedMinutes: Int?,
        actualMs: Long,
        outcome: ReflectionOutcome,
        reasons: Set<ReflectionReason>,
        nowWall: Long
    ) {
        val dayKey = DayKeys.dayKey(nowWall)
        val reasonsStr = ReflectionReason.encodeList(reasons)
        db.reflectionDao().insert(
            ReflectionEntity(
                dayKey = dayKey,
                sessionId = sessionId,
                packageName = packageName,
                purpose = purpose.code,
                goal = goal,
                plannedMinutes = plannedMinutes,
                actualMs = actualMs,
                outcome = outcome.code,
                reasons = reasonsStr,
                createdWall = nowWall
            )
        )
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "reflection_saved",
                detail = "$packageName ${purpose.code} outcome=${outcome.code} reasons=$reasonsStr"
            )
        )
    }

    suspend fun logReflectionSkipped(packageName: String, purpose: IntentPurpose, nowWall: Long) {
        db.eventDao().insert(
            EventEntity(wall = nowWall, type = "reflection_skipped", detail = "$packageName ${purpose.code}")
        )
    }

    fun observeRecentReflections(limit: Int = 20): Flow<List<ReflectionRecord>> =
        db.reflectionDao().observeRecent(limit).map { rows ->
            rows.map {
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
        }

    companion object {
        const val KEY_BEDTIME_SETTINGS = "bedtime_settings"
        const val KEY_ACTIVE_FOCUS = "active_focus"
        const val KEY_FOCUS_WHITELIST = "focus_whitelist"
    }
}
