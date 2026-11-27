package app.mindstop.service

import android.content.Context
import androidx.room.withTransaction
import app.mindstop.core.BedtimeEngine
import app.mindstop.core.BodyWakeActivity
import app.mindstop.core.DayKeys
import app.mindstop.core.LimitKind
import app.mindstop.core.MorningActivationEngine
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningAlarmPlanner
import app.mindstop.core.MorningRewardChoice
import app.mindstop.core.MorningRuntimeMeta
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.MorningStatRecord
import app.mindstop.core.MorningState
import app.mindstop.core.MorningStatsAggregator
import app.mindstop.core.MorningStatsSummary
import app.mindstop.core.MorningV2Settings
import app.mindstop.core.PendingMorningBonus
import app.mindstop.core.QuotaGrant
import app.mindstop.core.RuleEngine
import app.mindstop.core.WakeCheckSubState
import app.mindstop.core.WakeupConfirmMethod
import app.mindstop.data.BedtimeLogEntity
import app.mindstop.data.EventEntity
import app.mindstop.data.MetaEntity
import app.mindstop.data.MindstopDatabase
import app.mindstop.data.MorningAlarmEntity
import app.mindstop.data.MorningSessionEntity
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local persistence, atomic state transitions & telemetry for Module 2: Morning Activation V2.
 *
 * Token guards follow WakeNudge `WakeCheckController` (Apache-2.0). Room transactions also
 * serialize the accessibility process and commit session, meta and reward writes together.
 */
class MorningRepository(private val context: Context) {

    private val db = MindstopDatabase.getInstance(context)
    private val stage5 = Stage5Repository(context)
    private val rewards = RewardRepository(context)
    private val quotaRepo = QuotaRepository(context)

    data class NightMorningLink(
        val session: MorningSessionRecord,
        val previousNight: BedtimeLogEntity?
    )

    enum class BonusClaimResult {
        CREDITED_NOW,
        SCHEDULED_AFTERNOON,
        ALREADY_CLAIMED,
        NO_DAILY_RULES
    }

    data class SessionSnapshot(
        val session: MorningSessionRecord,
        val meta: MorningRuntimeMeta,
        val changed: Boolean = true
    )

    companion object {
        private const val KEY_V2_SETTINGS = "morning_v2_settings"
        private const val KEY_STAGE_META_PREFIX = "morning_stage_meta_"
        private const val KEY_PENDING_AFTERNOON_BONUS = "morning_pending_afternoon_bonus"
        private const val KEY_BONUS_GRANTED_DAY_PREFIX = "morning_bonus_granted_day_"
        private const val KEY_MORNING_STATS_LOG = "morning_stats_log_v2"
        private const val MAX_STATS_RECORDS = 90
        private const val STALE_RING_AGE_MS = 12 * 60_000L
    }

    // Settings

    suspend fun loadSettings(): MorningV2Settings =
        MorningV2Settings.decode(db.metaDao().get(KEY_V2_SETTINGS))

    suspend fun saveSettings(settings: MorningV2Settings, nowWall: Long) {
        db.metaDao().put(MetaEntity(KEY_V2_SETTINGS, settings.encode()))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "morning_settings_saved", detail = settings.encode().take(200)))
    }

    // Alarms

    fun observeAlarms(): Flow<List<MorningAlarm>> =
        db.morningAlarmDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun loadAlarms(): List<MorningAlarm> =
        db.morningAlarmDao().getAll().map { it.toDomain() }

    suspend fun getAlarm(id: Long): MorningAlarm? =
        db.morningAlarmDao().byId(id)?.toDomain()

    suspend fun saveAlarm(alarm: MorningAlarm, nowWall: Long): MorningAlarm {
        val existing = if (alarm.id > 0L) db.morningAlarmDao().byId(alarm.id) else null
        val entity = alarm.toEntity(createdWall = existing?.createdWall ?: nowWall, updatedWall = nowWall)
        val savedId = db.morningAlarmDao().upsert(entity)
        val saved = alarm.copy(id = if (alarm.id > 0L) alarm.id else savedId)
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "alarm_saved",
                detail = "id=${saved.id} %02d:%02d en=${saved.enabled} mask=${saved.repeatDaysMask} method=${saved.dismissMethod.code}".format(saved.hour, saved.minute)
            )
        )
        return saved
    }

    suspend fun deleteAlarm(id: Long, nowWall: Long) {
        db.morningAlarmDao().delete(id)
        db.eventDao().insert(EventEntity(wall = nowWall, type = "alarm_deleted", detail = "id=$id"))
    }

    suspend fun syncAlarmsFromSleepPlan(
        workdayLabel: String,
        weekendLabel: String,
        nowWall: Long
    ): List<MorningAlarm> {
        val bedtime = stage5.loadBedtime()
        val existing = loadAlarms()
        val out = ArrayList<MorningAlarm>(2)

        val wdWake = bedtime.endMinutesOfDay
        val wdTarget = existing.firstOrNull { it.repeatDaysMask == MorningAlarm.WORKDAYS_MASK }
        val wdAlarm = (wdTarget ?: MorningAlarm(label = workdayLabel, repeatDaysMask = MorningAlarm.WORKDAYS_MASK)).copy(
            hour = wdWake / 60,
            minute = wdWake % 60,
            enabled = true
        )
        out.add(saveAlarm(wdAlarm, nowWall))

        if (bedtime.weekendSeparate) {
            val weWake = bedtime.weekendWakeMinutesOfDay
            val weTarget = existing.firstOrNull { it.repeatDaysMask == MorningAlarm.WEEKEND_MASK }
            val weAlarm = (weTarget ?: MorningAlarm(label = weekendLabel, repeatDaysMask = MorningAlarm.WEEKEND_MASK)).copy(
                hour = weWake / 60,
                minute = weWake % 60,
                enabled = true
            )
            out.add(saveAlarm(weAlarm, nowWall))
        }
        return out
    }

    // Active Sessions & Guards

    fun observeRecentSessions(limit: Int = 10): Flow<List<MorningSessionRecord>> =
        db.morningSessionDao().observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun activeSession(nowWall: Long): MorningSessionRecord? = db.withTransaction {
        activeSessionLocked(nowWall)
    }

    private suspend fun activeSessionLocked(nowWall: Long): MorningSessionRecord? {
        db.morningSessionDao().settleStaleRinging(
            excludeId = if (MindstopAlarmService.isRinging()) MindstopAlarmService.activeSessionId else -1L,
            staleBeforeWall = nowWall - STALE_RING_AGE_MS,
            nowWall = nowWall
        )
        val raw = db.morningSessionDao().activeSession()?.toDomain() ?: return null
        if (raw.status == MorningState.STAGE3_REWARD) {
            val meta = loadRuntimeMeta(raw.id)
            if (MorningActivationEngine.instantRewardTimerWall(meta, nowWall) == nowWall) {
                completeActivation(raw.id, nowWall, meta.stageToken)
                return null
            }
        }
        val settled = MorningActivationEngine.settleUnverifiedIfExpired(raw, nowWall)
        if (settled.status != raw.status) {
            db.morningSessionDao().upsert(settled.toEntity())
            rewards.cancelMorningReward(settled.rewardId, "unverified_timeout", nowWall)
            recordMorningStatLocked(settled, loadRuntimeMeta(settled.id), completed = false, nowWall = nowWall)
            return null
        }
        return settled
    }

    // Keep the timer snapshot and its installation ordered with competing database transitions.
    suspend fun restoreActiveTimers(now: Long) = db.withTransaction {
        val active = activeSessionLocked(now)
        if (active != null && active.status.isInActivation) {
            val meta = loadRuntimeMeta(active.id)
            val settings = loadSettings()
            MindstopAlarmScheduler.scheduleSessionTimers(context, active, meta, settings, now)
            if (active.status == MorningState.STAGE2_PREP && meta.wakeCheckState == WakeCheckSubState.PROMPTED &&
                meta.wakeCheckDeadlineWall > now
            ) {
                MindstopAlarmScheduler.postSilentWakeCheckNotification(
                    context, active.id, meta.wakeCheckToken,
                    ((meta.wakeCheckDeadlineWall - now + 59_999L) / 60_000L).toInt()
                )
            }
        }
    }

    // Cleanup is optional after commit. Read the current stage again while holding the database
    // transaction so a late caller cannot cancel a newer stage's alarm or notification.
    private suspend fun pruneOldTimers(snapshot: SessionSnapshot?) {
        if (snapshot == null || !snapshot.changed) return
        db.withTransaction {
            val id = snapshot.session.id
            val current = getSession(id) ?: return@withTransaction
            MindstopAlarmScheduler.cancelObsoleteTimers(context, id, current.status, loadRuntimeMeta(id))
        }
    }

    suspend fun getSession(id: Long): MorningSessionRecord? =
        db.morningSessionDao().latestById(id)?.toDomain()

    suspend fun loadRuntimeMeta(sessionId: Long): MorningRuntimeMeta =
        MorningRuntimeMeta.decode(db.metaDao().get("$KEY_STAGE_META_PREFIX$sessionId"))

    private suspend fun saveRuntimeMeta(sessionId: Long, meta: MorningRuntimeMeta) {
        db.metaDao().put(MetaEntity("$KEY_STAGE_META_PREFIX$sessionId", meta.encode()))
    }

    suspend fun logSkippedAlarm(alarmId: Long, nowWall: Long) {
        db.eventDao().insert(EventEntity(wall = nowWall, type = "alarm_skipped_already_awake", detail = "alarm=$alarmId"))
    }

    /**
     * Used by [MindstopEngine]: returns true while Stage 1 or Stage 2 is active so high-distraction
     * entertainment apps (short video, feeds) remain gently redirected back to Morning Activation
     * while essential daily tools stay unrestricted.
     */
    suspend fun isMorningPrepBlockingActive(nowWall: Long): Boolean {
        val active = db.morningSessionDao().activeSession()?.toDomain() ?: return false
        if (MorningActivationEngine.settleUnverifiedIfExpired(active, nowWall).status.isTerminal) return false
        return active.status.isStage1 || active.status == MorningState.STAGE2_PREP
    }

    /**
     * Starts Stage 1 immediately when the alarm fires (ringStartedWall = stage1StartWall),
     * ensuring the 5m unlock gate & 15m planned wake window run from alarm trigger time.
     */
    suspend fun startRinging(alarm: MorningAlarm, scheduledWall: Long, nowWall: Long, isSnooze: Boolean): SessionSnapshot? =
        db.withTransaction {
            if (!isSnooze && alarm.repeatDaysMask == MorningAlarm.ONCE_MASK) {
                val stored = db.morningAlarmDao().byId(alarm.id) ?: return@withTransaction null
                if (!stored.enabled) return@withTransaction null
                db.morningAlarmDao().upsert(stored.copy(enabled = false, updatedWall = nowWall))
            }
            val active = activeSessionLocked(nowWall)
            if (!isSnooze) {
                val completed = db.morningSessionDao().latestCompletedForDay(DayKeys.dayKey(nowWall))
                if (MorningActivationEngine.shouldSkipSubsequentAlarm(completed?.completedWall, active?.status, nowWall)) {
                    return@withTransaction null
                }
            }
            if (active != null && active.status.isInActivation) {
                return@withTransaction SessionSnapshot(active, loadRuntimeMeta(active.id), changed = false)
            }
            db.morningSessionDao().settleStaleRinging(excludeId = -1L, staleBeforeWall = nowWall, nowWall = nowWall)
            val session = MorningActivationEngine.onRingStarted(alarm, scheduledWall, nowWall)
            val id = db.morningSessionDao().upsert(session.toEntity())
            val saved = session.copy(id = id)
            val meta = MorningActivationEngine.initialMetaForRing(alarm, nowWall)
            saveRuntimeMeta(id, meta)
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "alarm_ringing", detail = "alarm=${alarm.id} session=$id token=${meta.stageToken}")
            )
            MindstopAlarmScheduler.scheduleSessionTimers(context, saved, meta, loadSettings(), nowWall)
            SessionSnapshot(saved, meta, changed = true)
        }

    /**
     * Temporarily mutes / dismisses the ringing screen without marking wake-up complete.
     * Preserves original Stage 1 start wall & ring count, and plants the pending Ginkgo reward once.
     */
    suspend fun acknowledgeAlarm(sessionId: Long, nowWall: Long): SessionSnapshot? = db.withTransaction {
        val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
        if (!current.status.isStage1 && current.status != MorningState.RINGING && current.status != MorningState.ACKNOWLEDGED) return@withTransaction null
        val pendingRewardId = if (current.rewardId != null) {
            current.rewardId
        } else {
            val bedtime = stage5.loadBedtime()
            val eval = BedtimeEngine.evaluateAtWall(bedtime, nowWall)
            rewards.issueMorningPendingReward(
                dayKey = current.dayKey,
                sessionId = current.id,
                plannedSleepOpportunityMinutes = eval.plannedSleepOpportunityMinutes,
                nowWall = nowWall
            )?.id
        }
        val updated = MorningActivationEngine.onAlarmAcknowledged(current, nowWall, rewardId = pendingRewardId)
        db.morningSessionDao().upsert(updated.toEntity())
        val existingMeta = loadRuntimeMeta(sessionId)
        val normalizedMeta = if (existingMeta.stage1StartWall <= 0L) {
            existingMeta.copy(
                stage1StartWall = updated.ringStartedWall,
                selectedJoy = existingMeta.selectedJoy.ifBlank { updated.joyActivity }
            )
        } else {
            existingMeta
        }
        saveRuntimeMeta(sessionId, normalizedMeta)
        if (!current.status.isStage1) {
            MindstopAlarmScheduler.scheduleSessionTimers(context, updated, normalizedMeta, loadSettings(), nowWall)
        }
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "alarm_acknowledged",
                detail = "session=$sessionId stage=${updated.status.code} ringCount=${normalizedMeta.ringCount} delay=${updated.responseDelaySec}s"
            )
        )
        SessionSnapshot(updated, normalizedMeta, changed = true)
    }

    /** Atomically handles a 2nd ring (+5m) or overtime repeat ring (+15m/interval) in Stage 1. */
    suspend fun onStage1RepeatRing(sessionId: Long, expectedStageToken: Long, nowWall: Long, secondRingOnly: Boolean = false): SessionSnapshot? =
        db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
            val meta = loadRuntimeMeta(sessionId)
            val result = MorningActivationEngine.onStage1RepeatRing(current, meta, expectedStageToken, nowWall, secondRingOnly)
            if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false)
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            db.eventDao().insert(
                EventEntity(
                    wall = nowWall,
                    type = "morning_stage1_repeat_ring",
                    detail = "session=$sessionId ringCount=${result.meta.ringCount} token=${result.meta.stageToken}"
                )
            )
            if (!secondRingOnly) {
                MindstopAlarmScheduler.scheduleStage1NextOvertimeRing(
                    context, sessionId, current.alarmId, result.meta.stageToken, loadSettings().stage1RepeatMs, nowWall
                )
            }
            SessionSnapshot(result.session, result.meta, changed = true)
        }

    /** Atomically enters intentional Extended Sleep (only allowed starting from 2nd ring). */
    suspend fun enterExtendedSleep(sessionId: Long, requestedMinutes: Int, nowWall: Long): SessionSnapshot? =
        db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
            val meta = loadRuntimeMeta(sessionId)
            val settings = loadSettings()
            val result = MorningActivationEngine.enterExtendedSleep(current, meta, requestedMinutes, settings, nowWall)
            if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false)
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            db.eventDao().insert(
                EventEntity(
                    wall = nowWall,
                    type = "morning_extended_sleep",
                    detail = "session=$sessionId count=${result.meta.extendedSleepCount} resume=${result.meta.extendedSleepResumeWall} token=${result.meta.stageToken}"
                )
            )
            MindstopAlarmScheduler.scheduleSessionTimers(context, result.session, result.meta, settings, nowWall)
            SessionSnapshot(result.session, result.meta, changed = true)
        }.also { pruneOldTimers(it) }

    /** Atomically resumes Stage 1 when an intentional Extended Sleep window ends. */
    suspend fun resumeFromExtendedSleep(sessionId: Long, expectedStageToken: Long, nowWall: Long): SessionSnapshot? =
        db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
            val meta = loadRuntimeMeta(sessionId)
            val result = MorningActivationEngine.resumeFromExtendedSleep(current, meta, expectedStageToken, nowWall)
            if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false)
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            db.eventDao().insert(
                EventEntity(
                    wall = nowWall,
                    type = "morning_extended_sleep_resumed",
                    detail = "session=$sessionId token=${result.meta.stageToken}"
                )
            )
            MindstopAlarmScheduler.scheduleSessionTimers(context, result.session, result.meta, loadSettings(), nowWall)
            SessionSnapshot(result.session, result.meta, changed = true)
        }.also { pruneOldTimers(it) }

    /** Atomically enters Stage 2 and captures optional hardware step counter baseline. */
    suspend fun enterStage2(sessionId: Long, nowWall: Long): SessionSnapshot? {
        val settings = loadSettings()
        val baseline = if (settings.stepProofEnabled) {
            HardwareStepReader.readTotalSteps(context) ?: -1f
        } else {
            -1f
        }
        return db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
            val meta = loadRuntimeMeta(sessionId)
            val result = MorningActivationEngine.enterStage2WithMeta(current, meta, settings, nowWall, baseline)
            if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false)
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            db.eventDao().insert(
                EventEntity(
                    wall = nowWall,
                    type = "morning_stage2_started",
                    detail = "session=$sessionId token=${result.meta.stageToken} checkToken=${result.meta.wakeCheckToken} stepBase=$baseline"
                )
            )
            MindstopAlarmScheduler.scheduleSessionTimers(context, result.session, result.meta, settings, nowWall)
            SessionSnapshot(result.session, result.meta, changed = true)
        }.also { pruneOldTimers(it) }
    }

    /** Opens the silent wake-check window inside Stage 2 (or auto-passes via optional 30-step counter). */
    suspend fun triggerSilentWakeCheckPrompt(
        sessionId: Long,
        expectedCheckToken: Long,
        nowWall: Long
    ) {
        val settings = loadSettings()
        val beforeRead = loadRuntimeMeta(sessionId)
        val sample = settings.stepProofEnabled && beforeRead.stepBaseline >= 0f &&
            beforeRead.wakeCheckToken == expectedCheckToken && beforeRead.wakeCheckState == WakeCheckSubState.WAITING
        val currentSteps = if (sample) HardwareStepReader.readTotalSteps(context) else null
        return db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain()
                ?: return@withTransaction
            val meta = loadRuntimeMeta(sessionId)
            val result = MorningActivationEngine.triggerSilentWakeCheckPrompt(
                current, meta, expectedCheckToken, settings, nowWall, currentSteps
            )
            if (!result.changed) {
                return@withTransaction
            }
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            val autoPassed = result.meta.wakeCheckState == WakeCheckSubState.CONFIRMED
            db.eventDao().insert(
                EventEntity(
                    wall = nowWall,
                    type = if (autoPassed) "morning_wake_check_auto_steps" else "morning_wake_check_prompted",
                    detail = "session=$sessionId checkToken=$expectedCheckToken"
                )
            )
            if (!autoPassed) {
                MindstopAlarmScheduler.scheduleWakeCheckDeadline(context, sessionId, current.alarmId,
                    result.meta.stageToken, result.meta.wakeCheckToken, result.meta.wakeCheckDeadlineWall)
                MindstopAlarmScheduler.postSilentWakeCheckNotification(context, sessionId, result.meta.wakeCheckToken, settings.wakeCheckWindowMin)
            }
        }
    }

    /** Attempts silent step-counter auto-confirmation when Stage 2 is viewed or polled. */
    suspend fun tryAutoConfirmWakeCheckBySteps(sessionId: Long, expectedCheckToken: Long): SessionSnapshot? {
        val settings = loadSettings()
        if (!settings.stepProofEnabled) return null
        val currentSteps = HardwareStepReader.readTotalSteps(context) ?: return null
        return db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
            val meta = loadRuntimeMeta(sessionId)
            if (current.status != MorningState.STAGE2_PREP || meta.wakeCheckState == WakeCheckSubState.CONFIRMED) {
                return@withTransaction SessionSnapshot(current, meta, changed = false)
            }
            if (!MorningActivationEngine.stepsSatisfyWakeCheck(meta.stepBaseline, currentSteps, settings.stepProofThreshold)) {
                return@withTransaction SessionSnapshot(current, meta, changed = false)
            }
            val nowWall = System.currentTimeMillis()
            val result = MorningActivationEngine.confirmSilentWakeCheck(current, meta, expectedCheckToken, nowWall)
            if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false)
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "morning_wake_check_auto_steps", detail = "session=$sessionId")
            )
            SessionSnapshot(result.session, result.meta, changed = true)
        }.also { pruneOldTimers(it) }
    }

    /** Atomically confirms the Stage 2 Silent Wake Check (via notification action or in-app button). */
    suspend fun confirmSilentWakeCheck(sessionId: Long, expectedCheckToken: Long?, nowWall: Long): SessionSnapshot? =
        db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
            val meta = loadRuntimeMeta(sessionId)
            val result = MorningActivationEngine.confirmSilentWakeCheck(current, meta, expectedCheckToken, nowWall)
            if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false)
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "morning_wake_check_confirmed", detail = "session=$sessionId checkToken=${meta.wakeCheckToken}")
            )
            SessionSnapshot(result.session, result.meta, changed = true)
        }.also { pruneOldTimers(it) }

    /** Atomically rolls Stage 2 back to Stage 1 when the silent wake-check window expires unanswered. */
    suspend fun onSilentWakeCheckTimeout(sessionId: Long, expectedCheckToken: Long, nowWall: Long): SessionSnapshot? =
        db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
            val meta = loadRuntimeMeta(sessionId)
            if (current.status == MorningState.STAGE2_PREP && meta.wakeCheckToken == expectedCheckToken &&
                meta.wakeCheckState == WakeCheckSubState.PROMPTED && nowWall < meta.wakeCheckDeadlineWall
            ) {
                MindstopAlarmScheduler.scheduleWakeCheckDeadline(context, sessionId, current.alarmId,
                    meta.stageToken, meta.wakeCheckToken, meta.wakeCheckDeadlineWall)
            }
            val result = MorningActivationEngine.onSilentWakeCheckTimeout(current, meta, expectedCheckToken, nowWall)
            if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false)
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            db.eventDao().insert(
                EventEntity(
                    wall = nowWall,
                    type = "morning_wake_check_timeout_rollback",
                    detail = "session=$sessionId timeouts=${result.meta.wakeCheckTimeouts} newToken=${result.meta.stageToken}"
                )
            )
            MindstopAlarmScheduler.scheduleSessionTimers(context, result.session, result.meta, loadSettings(), nowWall)
            SessionSnapshot(result.session, result.meta, changed = true)
        }.also { pruneOldTimers(it) }

    /** Atomically advances Stage 2 -> Stage 3 (or directly to COMPLETED when Stage 3 is disabled). */
    suspend fun enterStage3(sessionId: Long, nowWall: Long): Pair<SessionSnapshot?, Boolean> = db.withTransaction {
        val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null to false
        val meta = loadRuntimeMeta(sessionId)
        val settings = loadSettings()
        val result = MorningActivationEngine.enterStage3WithMeta(current, meta, settings, nowWall)
        if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false) to false
        db.morningSessionDao().upsert(result.session.toEntity())
        saveRuntimeMeta(sessionId, result.meta)
        var confirmedReward = false
        if (result.session.status == MorningState.COMPLETED) {
            db.morningSessionDao().settleStaleRinging(excludeId = sessionId, staleBeforeWall = nowWall, nowWall = nowWall)
            confirmedReward = rewards.confirmMorningReward(result.session.rewardId, nowWall)
            recordMorningStatLocked(result.session, result.meta, completed = true, nowWall = nowWall)
        }
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = if (result.session.status == MorningState.COMPLETED) "morning_activation_completed" else "morning_stage3_started",
                detail = "session=$sessionId token=${result.meta.stageToken}"
            )
        )
        SessionSnapshot(result.session, result.meta, changed = true) to confirmedReward
    }.also { pruneOldTimers(it.first) }

    suspend fun updateSelectedJoy(sessionId: Long, joyCode: String) = db.withTransaction {
        val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction
        val meta = loadRuntimeMeta(sessionId)
        saveRuntimeMeta(sessionId, meta.copy(selectedJoy = joyCode))
        if (current.joyActivity != joyCode) {
            db.morningSessionDao().upsert(current.copy(joyActivity = joyCode).toEntity())
        }
    }

    /** Stage 3 Choice A: Start bounded instant joy countdown (mutually exclusive with B & C). */
    suspend fun chooseInstantReward(sessionId: Long, nowWall: Long): SessionSnapshot? = db.withTransaction {
        val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
        val meta = loadRuntimeMeta(sessionId)
        val settings = loadSettings()
        val result = MorningActivationEngine.chooseInstantReward(current, meta, settings, nowWall)
        if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false)
        db.morningSessionDao().upsert(result.session.toEntity())
        saveRuntimeMeta(sessionId, result.meta)
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "morning_reward_choice_instant",
                detail = "session=$sessionId min=${settings.stage3InstantMin} end=${result.meta.instantRewardEndWall}"
            )
        )
        MindstopAlarmScheduler.scheduleSessionTimers(context, result.session, result.meta, settings, nowWall)
        SessionSnapshot(result.session, result.meta, changed = true)
    }

    /**
     * Stage 3 Choice B: Grant/schedule delayed +15m daily quota (day-scoped idempotent!) and
     * immediately complete Morning Activation. Mutually exclusive with A & C.
     */
    suspend fun chooseDelayedRewardAndComplete(
        sessionId: Long,
        nowWall: Long
    ): Triple<SessionSnapshot?, Boolean, BonusClaimResult> = db.withTransaction {
        val current = db.morningSessionDao().latestById(sessionId)?.toDomain()
            ?: return@withTransaction Triple(null, false, BonusClaimResult.ALREADY_CLAIMED)
        val meta = loadRuntimeMeta(sessionId)
        val settings = loadSettings()
        val result = MorningActivationEngine.chooseDelayedRewardAndComplete(current, meta, nowWall)
        if (!result.changed) {
            return@withTransaction Triple(SessionSnapshot(current, meta, changed = false), false, BonusClaimResult.ALREADY_CLAIMED)
        }
        val bonusResult = issueDayIdempotentBonusLocked(
            dayKey = DayKeys.dayKey(nowWall),
            bonusMinutes = settings.delayedBonusMinutes,
            unlockMinuteOfDay = settings.morningRestrictEndMinute,
            nowWall = nowWall
        )
        val finalMeta = result.meta.copy(
            bonusClaimed = bonusResult == BonusClaimResult.CREDITED_NOW || bonusResult == BonusClaimResult.SCHEDULED_AFTERNOON,
            bonusAfterNoon = bonusResult == BonusClaimResult.SCHEDULED_AFTERNOON
        )
        db.morningSessionDao().upsert(result.session.toEntity())
        saveRuntimeMeta(sessionId, finalMeta)
        db.morningSessionDao().settleStaleRinging(excludeId = sessionId, staleBeforeWall = nowWall, nowWall = nowWall)
        val confirmed = rewards.confirmMorningReward(result.session.rewardId, nowWall)
        recordMorningStatLocked(result.session, finalMeta, completed = true, nowWall = nowWall)
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "morning_reward_choice_delayed",
                detail = "session=$sessionId bonus=$bonusResult confirmed=$confirmed"
            )
        )
        Triple(SessionSnapshot(result.session, finalMeta, changed = true), confirmed, bonusResult)
    }

    /** Stage 3 Choice C: Decline reward and finish Morning Activation cleanly. */
    suspend fun chooseSkipRewardAndComplete(sessionId: Long, nowWall: Long): Pair<SessionSnapshot?, Boolean> =
        db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null to false
            val meta = loadRuntimeMeta(sessionId)
            val result = MorningActivationEngine.chooseSkipRewardAndComplete(current, meta, nowWall)
            if (!result.changed) return@withTransaction SessionSnapshot(current, meta, changed = false) to false
            db.morningSessionDao().upsert(result.session.toEntity())
            saveRuntimeMeta(sessionId, result.meta)
            db.morningSessionDao().settleStaleRinging(excludeId = sessionId, staleBeforeWall = nowWall, nowWall = nowWall)
            val confirmed = rewards.confirmMorningReward(result.session.rewardId, nowWall)
            recordMorningStatLocked(result.session, result.meta, completed = true, nowWall = nowWall)
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "morning_reward_choice_skip", detail = "session=$sessionId confirmed=$confirmed")
            )
            SessionSnapshot(result.session, result.meta, changed = true) to confirmed
        }

    /** Finishes Choice A instant reward (on timer expiry or user tap) and confirms morning completion. */
    suspend fun completeActivation(sessionId: Long, nowWall: Long, expectedStageToken: Long? = null): Pair<MorningSessionRecord?, Boolean> =
        db.withTransaction {
            val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null to false
            val meta = loadRuntimeMeta(sessionId)
            if (current.status != MorningState.STAGE3_REWARD || meta.rewardChoice != MorningRewardChoice.INSTANT ||
                (expectedStageToken != null && (meta.stageToken != expectedStageToken ||
                    MorningActivationEngine.instantRewardTimerWall(meta, nowWall) != nowWall))
            ) return@withTransaction null to false
            val updated = MorningActivationEngine.onActivationCompleted(current, nowWall)
            val nextMeta = meta.copy(stageToken = meta.stageToken + 1L)
            db.morningSessionDao().upsert(updated.toEntity())
            saveRuntimeMeta(sessionId, nextMeta)
            db.morningSessionDao().settleStaleRinging(excludeId = sessionId, staleBeforeWall = nowWall, nowWall = nowWall)
            val confirmed = rewards.confirmMorningReward(updated.rewardId, nowWall)
            recordMorningStatLocked(updated, nextMeta, completed = true, nowWall = nowWall)
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "morning_activation_completed", detail = "session=$sessionId confirmed=$confirmed")
            )
            updated to confirmed
        }

    /** Honest back-to-bed or emergency exit: never recorded as COMPLETED, cancels pending Ginkgo reward. */
    suspend fun reportExitNotCompleted(
        sessionId: Long,
        returnedToBed: Boolean,
        nowWall: Long
    ): MorningSessionRecord? = db.withTransaction {
        val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction null
        if (current.status.isTerminal) return@withTransaction null
        val updated = if (returnedToBed) {
            MorningActivationEngine.onReturnedToBed(current, nowWall)
        } else {
            MorningActivationEngine.onEmergencyExit(current, nowWall)
        }
        val meta = loadRuntimeMeta(sessionId)
        val nextMeta = meta.copy(stageToken = meta.stageToken + 1L, wakeCheckState = WakeCheckSubState.NONE)
        db.morningSessionDao().upsert(updated.toEntity())
        saveRuntimeMeta(sessionId, nextMeta)
        val reason = if (returnedToBed) "returned_to_bed" else "emergency_exit"
        rewards.cancelMorningReward(updated.rewardId, reason, nowWall)
        recordMorningStatLocked(updated, nextMeta, completed = false, nowWall = nowWall)
        db.eventDao().insert(EventEntity(wall = nowWall, type = "morning_$reason", detail = "session=$sessionId"))
        updated
    }

    suspend fun reportReturnedToBed(sessionId: Long, nowWall: Long): MorningSessionRecord? =
        reportExitNotCompleted(sessionId, returnedToBed = true, nowWall = nowWall)

    suspend fun markMotionHint(sessionId: Long, nowWall: Long) = db.withTransaction {
        val current = db.morningSessionDao().latestById(sessionId)?.toDomain() ?: return@withTransaction
        if (current.motionHintSeen) return@withTransaction
        val updated = MorningActivationEngine.onMotionHint(current, nowWall)
        db.morningSessionDao().upsert(updated.toEntity())
    }

    // Day-level idempotent bonus quota delivery

    private suspend fun issueDayIdempotentBonusLocked(
        dayKey: String,
        bonusMinutes: Int,
        unlockMinuteOfDay: Int,
        nowWall: Long
    ): BonusClaimResult {
        val dayKeyMeta = "$KEY_BONUS_GRANTED_DAY_PREFIX$dayKey"
        if (db.metaDao().get(dayKeyMeta) == "1") return BonusClaimResult.ALREADY_CLAIMED
        val rules = quotaRepo.loadRules().filter { it.enabled && it.dailyLimitMs != null }
        if (rules.isEmpty()) return BonusClaimResult.NO_DAILY_RULES

        val unlockWall = MorningAlarmPlanner.localWallForMinuteOfDay(nowWall, unlockMinuteOfDay)
        db.metaDao().put(MetaEntity(dayKeyMeta, "1"))
        return if (nowWall >= unlockWall) {
            creditDailyBonus(dayKey, bonusMinutes, "morning_bonus_immediate", nowWall)
            BonusClaimResult.CREDITED_NOW
        } else {
            db.metaDao().put(MetaEntity(KEY_PENDING_AFTERNOON_BONUS, "$dayKey|$unlockWall|$bonusMinutes|0"))
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "morning_bonus_scheduled_delayed", detail = "min=$bonusMinutes unlock=$unlockWall")
            )
            MindstopAlarmScheduler.scheduleAfternoonBonus(context, unlockWall)
            BonusClaimResult.SCHEDULED_AFTERNOON
        }
    }

    /**
     * Called periodically by MindstopEngine.onTick(): credits today's delayed morning bonus quota
     * once local time passes the user's configured morning restriction end time.
     */
    suspend fun restorePendingBonus(nowWall: Long) {
        val bonus = PendingMorningBonus.forToday(db.metaDao().get(KEY_PENDING_AFTERNOON_BONUS), nowWall) ?: return
        MindstopAlarmScheduler.scheduleAfternoonBonus(context, maxOf(nowWall, bonus.unlockWall))
    }

    suspend fun deliverDueAfternoonBonusIfReady(nowWall: Long): Int? = db.withTransaction {
        val bonus = PendingMorningBonus.forToday(db.metaDao().get(KEY_PENDING_AFTERNOON_BONUS), nowWall)
            ?: return@withTransaction null
        if (nowWall < bonus.unlockWall) return@withTransaction null
        if (creditDailyBonus(bonus.dayKey, bonus.minutes, "morning_bonus_afternoon", nowWall) == 0) return@withTransaction null
        db.metaDao().put(MetaEntity(KEY_PENDING_AFTERNOON_BONUS, "${bonus.dayKey}|${bonus.unlockWall}|${bonus.minutes}|1"))
        bonus.minutes
    }

    private suspend fun creditDailyBonus(dayKey: String, minutes: Int, source: String, nowWall: Long): Int {
        val rules = quotaRepo.loadRules().filter { it.enabled && it.dailyLimitMs != null }
        if (rules.isEmpty()) return 0
        val amountMs = minutes.coerceIn(1, 60) * 60_000L
        val grants = rules.map { rule ->
            QuotaGrant(
                ruleId = rule.id,
                dayKey = dayKey,
                kind = LimitKind.DAILY,
                sessionId = null,
                amountMs = amountMs
            )
        }
        quotaRepo.addGrants(grants, source, nowWall)
        db.eventDao().insert(EventEntity(wall = nowWall, type = source, detail = "rules=${grants.size} min=$minutes"))
        return grants.size
    }

    // Local Morning Behavior Statistics (7 / 14 / 30 days)

    suspend fun saveWakefulnessRating(sessionId: Long, rating: Int, nowWall: Long) = db.withTransaction {
        val clamped = rating.coerceIn(1, 5)
        val meta = loadRuntimeMeta(sessionId)
        saveRuntimeMeta(sessionId, meta.copy(wakefulnessRating = clamped))
        val records = loadStatRecordsLocked().toMutableList()
        val idx = records.indexOfFirst { it.sessionId == sessionId }
        if (idx >= 0) {
            records[idx] = records[idx].copy(wakefulnessRating = clamped)
            saveStatRecordsLocked(records)
        }
        db.eventDao().insert(EventEntity(wall = nowWall, type = "morning_wakefulness_rated", detail = "session=$sessionId rating=$clamped"))
    }

    suspend fun loadStatsSummary(daysWindow: Int, nowWall: Long): MorningStatsSummary = db.withTransaction {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowWall
            add(Calendar.DAY_OF_MONTH, -(daysWindow - 1).coerceAtLeast(0))
        }
        val cutoffDayKey = DayKeys.dayKey(cal.timeInMillis)
        val records = loadStatRecordsLocked()
        MorningStatsAggregator.summarize(records, daysWindow, cutoffDayKey)
    }

    private suspend fun recordMorningStatLocked(
        session: MorningSessionRecord,
        meta: MorningRuntimeMeta,
        completed: Boolean,
        nowWall: Long
    ) {
        val wakeConfirmWall = meta.stage2StartWall.takeIf { it > 0L } ?: session.acknowledgedWall ?: nowWall
        val firstRingToWakeSec = ((wakeConfirmWall - session.ringStartedWall).coerceAtLeast(0L) / 1_000L).toInt()
        val prepEndWall = meta.stage3StartWall.takeIf { it > 0L } ?: session.completedWall ?: nowWall
        val prepMin = if (meta.stage2StartWall > 0L && prepEndWall > meta.stage2StartWall) {
            ((prepEndWall - meta.stage2StartWall) / 60_000L).toInt().coerceIn(0, 180)
        } else {
            0
        }
        val supervisedPkgs = RuleEngine.supervisedPackages(quotaRepo.loadRules(), quotaRepo.loadExempt()).toList()
        val postWakeDistractMin = if (supervisedPkgs.isEmpty()) {
            0
        } else {
            val ms = db.usageSegmentDao().entertainmentTotalInWindow(
                fromWall = wakeConfirmWall,
                toWall = wakeConfirmWall + 30 * 60_000L,
                packages = supervisedPkgs
            )
            (ms / 60_000L).toInt().coerceAtLeast(0)
        }
        val record = MorningStatRecord(
            dayKey = session.dayKey,
            sessionId = session.id,
            firstRingToWakeSec = firstRingToWakeSec,
            extendedSleepCount = meta.extendedSleepCount,
            extendedSleepMinutes = meta.extendedSleepTotalMin,
            wakeCheckTimeouts = meta.wakeCheckTimeouts,
            completed = completed,
            prepDurationMin = prepMin,
            postWakeDistractionMin = postWakeDistractMin,
            wakefulnessRating = meta.wakefulnessRating
        )
        val existing = loadStatRecordsLocked().filterNot { it.sessionId == session.id }.toMutableList()
        existing.add(0, record)
        saveStatRecordsLocked(existing.take(MAX_STATS_RECORDS))
    }

    private suspend fun loadStatRecordsLocked(): List<MorningStatRecord> {
        val raw = db.metaDao().get(KEY_MORNING_STATS_LOG) ?: return emptyList()
        return raw.lineSequence().mapNotNull { MorningStatRecord.decode(it.trim()) }.toList()
    }

    private suspend fun saveStatRecordsLocked(records: List<MorningStatRecord>) {
        db.metaDao().put(MetaEntity(KEY_MORNING_STATS_LOG, records.joinToString("\n") { it.encode() }))
    }

    /** Observational Bedtime <-> Morning paired timeline (v0.3 §14, no causal claims). */
    suspend fun loadRecentLinks(limit: Int = 7): List<NightMorningLink> {
        val sessions = db.morningSessionDao().allRecent(limit).map { it.toDomain() }
        val nights = db.bedtimeLogDao().allRecent(limit + 3).associateBy { it.nightKey }
        return sessions.map { s ->
            val nightKey = BedtimeEngine.nightKeyFor(s.ringStartedWall)
            NightMorningLink(s, nights[nightKey])
        }
    }

    private fun MorningAlarmEntity.toDomain() = MorningAlarm(
        id = id,
        label = label,
        hour = hour,
        minute = minute,
        repeatDaysMask = repeatDaysMask,
        enabled = enabled,
        ringtoneUri = ringtoneUri,
        gradualVolumeSeconds = gradualVolumeSeconds,
        vibrate = vibrate,
        snoozeMinutes = snoozeMinutes,
        dismissMethod = WakeupConfirmMethod.entries.firstOrNull { it.code == dismissMethod } ?: WakeupConfirmMethod.MANUAL,
        challengeCount = challengeCount,
        targetTagOrQr = targetTagOrQr,
        backupReminderEnabled = backupReminderEnabled,
        bodyWakeMinutes = bodyWakeMinutes,
        morningJoyMinutes = morningJoyMinutes,
        bodyActivities = BodyWakeActivity.decodeSet(bodyActivities),
        joyActivity = joyActivity,
        joyAllowanceMinutes = joyAllowanceMinutes
    )

    private fun MorningAlarm.toEntity(createdWall: Long, updatedWall: Long) = MorningAlarmEntity(
        id = id,
        label = label,
        hour = hour,
        minute = minute,
        repeatDaysMask = repeatDaysMask,
        enabled = enabled,
        ringtoneUri = ringtoneUri,
        gradualVolumeSeconds = gradualVolumeSeconds,
        vibrate = vibrate,
        snoozeMinutes = snoozeMinutes,
        dismissMethod = dismissMethod.code,
        challengeCount = challengeCount,
        targetTagOrQr = targetTagOrQr,
        backupReminderEnabled = backupReminderEnabled,
        bodyWakeMinutes = bodyWakeMinutes,
        morningJoyMinutes = morningJoyMinutes,
        bodyActivities = BodyWakeActivity.encodeSet(bodyActivities),
        joyActivity = joyActivity,
        joyAllowanceMinutes = joyAllowanceMinutes,
        createdWall = createdWall,
        updatedWall = updatedWall
    )

    private fun MorningSessionEntity.toDomain() = MorningSessionRecord(
        id = id,
        dayKey = dayKey,
        alarmId = alarmId,
        scheduledWall = scheduledWall,
        ringStartedWall = ringStartedWall,
        acknowledgedWall = acknowledgedWall,
        completedWall = completedWall,
        status = MorningState.fromCode(status),
        responseDelaySec = responseDelaySec,
        check5mConfirmed = check5mConfirmed,
        motionHintSeen = motionHintSeen,
        joyActivity = joyActivity,
        rewardId = rewardId,
        updatedWall = updatedWall
    )

    private fun MorningSessionRecord.toEntity() = MorningSessionEntity(
        id = id,
        dayKey = dayKey,
        alarmId = alarmId,
        scheduledWall = scheduledWall,
        ringStartedWall = ringStartedWall,
        acknowledgedWall = acknowledgedWall,
        completedWall = completedWall,
        status = status.code,
        responseDelaySec = responseDelaySec,
        check5mConfirmed = check5mConfirmed,
        motionHintSeen = motionHintSeen,
        joyActivity = joyActivity,
        rewardId = rewardId,
        updatedWall = updatedWall
    )
}
