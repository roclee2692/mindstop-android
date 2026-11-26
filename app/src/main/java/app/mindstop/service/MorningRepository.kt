package app.mindstop.service

import android.content.Context
import app.mindstop.core.BedtimeEngine
import app.mindstop.core.BodyWakeActivity
import app.mindstop.core.MorningActivationEngine
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.MorningState
import app.mindstop.core.WakeupConfirmMethod
import app.mindstop.data.BedtimeLogEntity
import app.mindstop.data.EventEntity
import app.mindstop.data.MindstopDatabase
import app.mindstop.data.MorningAlarmEntity
import app.mindstop.data.MorningSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local persistence & Bedtime <-> Morning coordination for Module 2 (v0.3 SPEC §10–§14).
 */
class MorningRepository(private val context: Context) {

    private val db = MindstopDatabase.getInstance(context)
    private val stage5 = Stage5Repository(context)
    private val rewards = RewardRepository(context)

    data class NightMorningLink(
        val session: MorningSessionRecord,
        val previousNight: BedtimeLogEntity?
    )

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

    suspend fun startRinging(alarm: MorningAlarm, scheduledWall: Long, nowWall: Long): MorningSessionRecord {
        val session = MorningActivationEngine.onRingStarted(alarm, scheduledWall, nowWall)
        val id = db.morningSessionDao().upsert(session.toEntity())
        db.eventDao().insert(EventEntity(wall = nowWall, type = "alarm_ringing", detail = "alarm=${alarm.id} session=$id"))
        return session.copy(id = id)
    }

    suspend fun acknowledgeAlarm(sessionId: Long, nowWall: Long): MorningSessionRecord? {
        val current = db.morningSessionDao().byId(sessionId)?.toDomain() ?: return null
        val bedtime = stage5.loadBedtime()
        val eval = BedtimeEngine.evaluateAtWall(bedtime, nowWall)
        val pendingReward = rewards.issueMorningPendingReward(
            dayKey = current.dayKey,
            sessionId = current.id,
            plannedSleepOpportunityMinutes = eval.plannedSleepOpportunityMinutes,
            nowWall = nowWall
        )
        val updated = MorningActivationEngine.onAlarmAcknowledged(current, nowWall, rewardId = pendingReward?.id)
        db.morningSessionDao().upsert(updated.toEntity())
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "alarm_acknowledged",
                detail = "session=$sessionId delay=${updated.responseDelaySec}s reward=${pendingReward?.id ?: 0}"
            )
        )
        return updated
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
        db.morningSessionDao().upsert(updated.toEntity())
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
