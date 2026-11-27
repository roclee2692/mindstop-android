package app.mindstop.service

import android.content.Context
import app.mindstop.core.BedtimeEngine
import app.mindstop.core.BodyWakeActivity
import app.mindstop.core.DayKeys
import app.mindstop.core.LimitKind
import app.mindstop.core.MorningActivationEngine
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.MorningState
import app.mindstop.core.QuotaGrant
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
 * Local persistence & Bedtime <-> Morning coordination for Module 2 (v0.3 SPEC §10–§14).
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

    data class StageProgressMeta(
        val stage1StartWall: Long = 0L,
        val stage2StartWall: Long = 0L,
        val stage3StartWall: Long = 0L,
        val bonusClaimed: Boolean = false,
        val bonusAfterNoon: Boolean = true,
        val selectedJoy: String = ""
    )

    enum class BonusClaimResult {
        CREDITED_NOW,
        SCHEDULED_AFTERNOON,
        ALREADY_CLAIMED,
        NO_DAILY_RULES
    }

    companion object {
        private const val KEY_STAGE_META_PREFIX = "morning_stage_meta_"
        private const val KEY_PENDING_AFTERNOON_BONUS = "morning_pending_afternoon_bonus"
        private const val STALE_RING_AGE_MS = 12 * 60_000L
    }

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

    /**
     * Syncs workday (and optionally weekend) alarms from the unified Sleep Plan (BedtimeSettings)
     * so bedtime and morning activation share one personal schedule (v0.3 §14).
     */
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

    // Morning Activation Sessions

    fun observeRecentSessions(limit: Int = 10): Flow<List<MorningSessionRecord>> =
        db.morningSessionDao().observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun activeSession(nowWall: Long): MorningSessionRecord? {
        db.morningSessionDao().settleStaleRinging(
            excludeId = if (MindstopAlarmService.isRinging()) MindstopAlarmService.activeSessionId else -1L,
            staleBeforeWall = nowWall - STALE_RING_AGE_MS,
            nowWall = nowWall
        )
        val raw = db.morningSessionDao().activeSession()?.toDomain() ?: return null
        val settled = MorningActivationEngine.settleUnverifiedIfExpired(raw, nowWall)
        if (settled.status != raw.status) {
            db.morningSessionDao().upsert(settled.toEntity())
            rewards.cancelMorningReward(settled.rewardId, "unverified_timeout", nowWall)
            return null
        }
        return settled
    }

    suspend fun getSession(id: Long): MorningSessionRecord? =
        db.morningSessionDao().byId(id)?.toDomain()

    /**
     * Returns true when a subsequent scheduled alarm should auto-skip because the user already
     * completed Morning Activation within the same morning window (3h) or is already in Stage 2/3.
     */
    suspend fun shouldSkipAlarmBecauseAlreadyAwake(nowWall: Long): Boolean {
        val dayKey = DayKeys.dayKey(nowWall)
        val latestCompleted = db.morningSessionDao().latestCompletedForDay(dayKey)?.completedWall
        val active = activeSession(nowWall)
        return MorningActivationEngine.shouldSkipSubsequentAlarm(
            latestCompletedWall = latestCompleted,
            activeState = active?.status,
            nowWall = nowWall
        )
    }

    suspend fun logSkippedAlarm(alarmId: Long, nowWall: Long) {
        db.eventDao().insert(EventEntity(wall = nowWall, type = "alarm_skipped_already_awake", detail = "alarm=$alarmId"))
    }

    suspend fun startRinging(alarm: MorningAlarm, scheduledWall: Long, nowWall: Long): MorningSessionRecord {
        db.morningSessionDao().settleStaleRinging(excludeId = -1L, staleBeforeWall = nowWall, nowWall = nowWall)
        val session = MorningActivationEngine.onRingStarted(alarm, scheduledWall, nowWall)
        val id = db.morningSessionDao().upsert(session.toEntity())
        db.eventDao().insert(EventEntity(wall = nowWall, type = "alarm_ringing", detail = "alarm=${alarm.id} session=$id"))
        return session.copy(id = id)
    }

    suspend fun acknowledgeAlarm(sessionId: Long, nowWall: Long): MorningSessionRecord? {
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return null
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
        val existingMeta = loadStageMeta(sessionId)
        saveStageMeta(
            sessionId,
            existingMeta.copy(
                stage1StartWall = nowWall,
                selectedJoy = existingMeta.selectedJoy.ifBlank { current.joyActivity }
            )
        )
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "alarm_acknowledged",
                detail = "session=$sessionId stage=STAGE1_WAKE delay=${updated.responseDelaySec}s reward=${pendingRewardId ?: 0}"
            )
        )
        return updated
    }

    suspend fun enterStage2(sessionId: Long, nowWall: Long): MorningSessionRecord? {
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return null
        val updated = MorningActivationEngine.enterStage2(current, nowWall)
        if (updated.status != MorningState.STAGE2_PREP) return current
        db.morningSessionDao().upsert(updated.toEntity())
        val meta = loadStageMeta(sessionId)
        saveStageMeta(sessionId, meta.copy(stage2StartWall = nowWall))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "morning_stage2_started", detail = "session=$sessionId"))
        return updated
    }

    suspend fun enterStage3(sessionId: Long, nowWall: Long): MorningSessionRecord? {
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return null
        val updated = MorningActivationEngine.enterStage3(current, nowWall)
        if (updated.status != MorningState.STAGE3_REWARD) return current
        db.morningSessionDao().upsert(updated.toEntity())
        val meta = loadStageMeta(sessionId)
        saveStageMeta(sessionId, meta.copy(stage3StartWall = nowWall))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "morning_stage3_started", detail = "session=$sessionId"))
        return updated
    }

    suspend fun updateSelectedJoy(sessionId: Long, joyCode: String) {
        val meta = loadStageMeta(sessionId)
        saveStageMeta(sessionId, meta.copy(selectedJoy = joyCode))
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return
        if (current.joyActivity != joyCode) {
            db.morningSessionDao().upsert(current.copy(joyActivity = joyCode).toEntity())
        }
    }

    suspend fun claimMorningBonusQuota(
        sessionId: Long,
        afternoonOnly: Boolean,
        bonusMinutes: Int = MorningActivationEngine.BONUS_QUOTA_MINUTES,
        nowWall: Long
    ): BonusClaimResult {
        val meta = loadStageMeta(sessionId)
        if (meta.bonusClaimed) return BonusClaimResult.ALREADY_CLAIMED
        val rules = quotaRepo.loadRules().filter { it.enabled && it.dailyLimitMs != null }
        if (rules.isEmpty()) return BonusClaimResult.NO_DAILY_RULES

        val dayKey = DayKeys.dayKey(nowWall)
        val noonWall = localNoonWall(nowWall)
        return if (!afternoonOnly || nowWall >= noonWall) {
            creditDailyBonus(dayKey, bonusMinutes, "morning_bonus_immediate", nowWall)
            saveStageMeta(sessionId, meta.copy(bonusClaimed = true, bonusAfterNoon = false))
            BonusClaimResult.CREDITED_NOW
        } else {
            db.metaDao().put(MetaEntity(KEY_PENDING_AFTERNOON_BONUS, "$dayKey|$noonWall|$bonusMinutes|0"))
            saveStageMeta(sessionId, meta.copy(bonusClaimed = true, bonusAfterNoon = true))
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "morning_bonus_scheduled_afternoon", detail = "session=$sessionId min=$bonusMinutes")
            )
            BonusClaimResult.SCHEDULED_AFTERNOON
        }
    }

    /**
     * Called periodically by MindstopEngine.onTick(): credits today's afternoon-locked morning
     * bonus quota (+15 min) once local time passes 12:00, and returns the credited minutes if any.
     */
    suspend fun deliverDueAfternoonBonusIfReady(nowWall: Long): Int? {
        val raw = db.metaDao().get(KEY_PENDING_AFTERNOON_BONUS)?.takeIf { it.isNotBlank() } ?: return null
        val parts = raw.split('|')
        if (parts.size < 4) return null
        val dayKey = parts[0]
        val unlockWall = parts[1].toLongOrNull() ?: return null
        val minutes = parts[2].toIntOrNull() ?: return null
        val delivered = parts[3] == "1"
        val today = DayKeys.dayKey(nowWall)
        if (delivered || dayKey != today) return null
        if (nowWall < unlockWall) return null
        db.metaDao().put(MetaEntity(KEY_PENDING_AFTERNOON_BONUS, "$dayKey|$unlockWall|$minutes|1"))
        val count = creditDailyBonus(dayKey, minutes, "morning_bonus_afternoon", nowWall)
        return if (count > 0) minutes else null
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

    private fun localNoonWall(nowWall: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowWall
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    suspend fun loadStageMeta(sessionId: Long): StageProgressMeta {
        val raw = db.metaDao().get("$KEY_STAGE_META_PREFIX$sessionId") ?: return StageProgressMeta()
        val p = raw.split('|')
        return StageProgressMeta(
            stage1StartWall = p.getOrNull(0)?.toLongOrNull() ?: 0L,
            stage2StartWall = p.getOrNull(1)?.toLongOrNull() ?: 0L,
            stage3StartWall = p.getOrNull(2)?.toLongOrNull() ?: 0L,
            bonusClaimed = p.getOrNull(3) == "1",
            bonusAfterNoon = p.getOrNull(4)?.let { it == "1" } ?: true,
            selectedJoy = p.getOrNull(5).orEmpty()
        )
    }

    private suspend fun saveStageMeta(sessionId: Long, meta: StageProgressMeta) {
        val safeJoy = meta.selectedJoy.replace("|", "")
        val encoded = listOf(
            meta.stage1StartWall.toString(),
            meta.stage2StartWall.toString(),
            meta.stage3StartWall.toString(),
            if (meta.bonusClaimed) "1" else "0",
            if (meta.bonusAfterNoon) "1" else "0",
            safeJoy
        ).joinToString("|")
        db.metaDao().put(MetaEntity("$KEY_STAGE_META_PREFIX$sessionId", encoded))
    }

    suspend fun markCheck5m(sessionId: Long, nowWall: Long): MorningSessionRecord? {
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return null
        val updated = MorningActivationEngine.onCheck5mConfirmed(current, nowWall)
        db.morningSessionDao().upsert(updated.toEntity())
        db.eventDao().insert(EventEntity(wall = nowWall, type = "morning_check_5m", detail = "session=$sessionId"))
        return updated
    }

    suspend fun markMotionHint(sessionId: Long, nowWall: Long) {
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return
        if (current.motionHintSeen) return
        val updated = MorningActivationEngine.onMotionHint(current, nowWall)
        db.morningSessionDao().upsert(updated.toEntity())
    }

    suspend fun completeActivation(sessionId: Long, nowWall: Long): Pair<MorningSessionRecord?, Boolean> {
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return null to false
        val updated = MorningActivationEngine.onActivationCompleted(current, nowWall)
        if (updated.status != MorningState.COMPLETED) return current to false
        db.morningSessionDao().upsert(updated.toEntity())
        db.morningSessionDao().settleStaleRinging(excludeId = sessionId, staleBeforeWall = nowWall, nowWall = nowWall)
        val confirmed = rewards.confirmMorningReward(updated.rewardId, nowWall)
        db.eventDao().insert(
            EventEntity(wall = nowWall, type = "morning_activation_completed", detail = "session=$sessionId confirmed=$confirmed")
        )
        return updated to confirmed
    }

    suspend fun reportReturnedToBed(sessionId: Long, nowWall: Long): MorningSessionRecord? {
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return null
        val updated = MorningActivationEngine.onReturnedToBed(current, nowWall)
        db.morningSessionDao().upsert(updated.toEntity())
        rewards.cancelMorningReward(updated.rewardId, "returned_to_bed", nowWall)
        db.eventDao().insert(EventEntity(wall = nowWall, type = "morning_returned_to_bed", detail = "session=$sessionId"))
        return updated
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
