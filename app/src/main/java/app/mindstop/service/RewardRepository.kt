package app.mindstop.service

import android.content.Context
import app.mindstop.core.DayKeys
import app.mindstop.core.RewardDenyReason
import app.mindstop.core.RewardEngine
import app.mindstop.core.RewardGrantDecision
import app.mindstop.core.RewardRecord
import app.mindstop.core.RewardRequest
import app.mindstop.core.RewardSettings
import app.mindstop.core.RewardStatus
import app.mindstop.core.RewardTrigger
import app.mindstop.data.EventEntity
import app.mindstop.data.MetaEntity
import app.mindstop.data.MindstopDatabase
import app.mindstop.data.RewardEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local persistence and multi-process safe coordinator for Stage 4 rewards:
 * Pending issuance, delayed confirmation after the verification window, and cancellation on
 * re-entry into matching supervised apps.
 */
class RewardRepository(context: Context) {

    private val db = MindstopDatabase.getInstance(context)
    private val quotaRepo = QuotaRepository(context)

    data class IssueOutcome(
        val granted: RewardRecord?,
        val denyReason: RewardDenyReason? = null,
        val settings: RewardSettings
    )

    fun observeAll(): Flow<List<RewardRecord>> =
        db.rewardDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeRecent(limit: Int): Flow<List<RewardRecord>> =
        db.rewardDao().observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun loadSettings(): RewardSettings =
        RewardSettings.decode(db.metaDao().get(KEY_REWARD_SETTINGS))

    suspend fun saveSettings(settings: RewardSettings, nowWall: Long) {
        val encoded = RewardSettings.encode(settings)
        db.metaDao().put(MetaEntity(KEY_REWARD_SETTINGS, encoded))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "reward_settings_changed", detail = encoded))
    }

    /**
     * Evaluates anti-arbitrage rules and, when eligible, inserts a new PENDING reward and logs
     * `reward_pending`. Always settles any overdue rewards first.
     */
    suspend fun tryIssueReward(
        packageName: String,
        sessionId: Long,
        trigger: RewardTrigger,
        nowWall: Long,
        hadReminderInSession: Boolean = false
    ): IssueOutcome {
        settleDueRewards(nowWall)
        val settings = loadSettings()
        val rules = quotaRepo.loadRules()
        val exempt = quotaRepo.loadExempt()
        val groupKey = RewardEngine.groupKeyFor(packageName, rules, exempt)
        val dayKey = DayKeys.dayKey(nowWall)

        val existing = db.rewardDao().forSessionOrGroupDay(sessionId, dayKey, groupKey).map { it.toDomain() }
        val decision = RewardEngine.evaluateGrant(
            RewardRequest(
                dayKey = dayKey,
                groupKey = groupKey,
                packageName = packageName,
                sessionId = sessionId,
                trigger = trigger,
                nowWall = nowWall,
                hadReminderInSession = hadReminderInSession
            ),
            existing = existing,
            settings = settings
        )
        return when (decision) {
            is RewardGrantDecision.Deny -> IssueOutcome(null, decision.reason, settings)
            is RewardGrantDecision.Grant -> {
                val id = db.rewardDao().insert(decision.pending.toEntity())
                val inserted = decision.pending.copy(id = id)
                db.eventDao().insert(
                    EventEntity(
                        wall = nowWall,
                        type = "reward_pending",
                        detail = "$packageName trigger=${trigger.code} +${inserted.energy} group=$groupKey session=$sessionId verify=${settings.verifyWindowMinutes}m"
                    )
                )
                IssueOutcome(inserted, null, settings)
            }
        }
    }

    /**
     * Atomically confirms all PENDING rewards whose verification window has elapsed at [nowWall].
     * Safe to call repeatedly from both service ticks and UI resume without duplicate events.
     */
    suspend fun settleDueRewards(nowWall: Long): List<RewardRecord> {
        val pending = db.rewardDao().byStatus(RewardStatus.PENDING.code).map { it.toDomain() }
        if (pending.isEmpty()) return emptyList()
        val due = RewardEngine.dueToConfirm(pending, nowWall)
        val confirmed = ArrayList<RewardRecord>(due.size)
        for (record in due) {
            val changed = db.rewardDao().confirmIfPending(record.id, nowWall)
            if (changed > 0) {
                val settled = record.copy(status = RewardStatus.CONFIRMED, resolvedWall = nowWall)
                confirmed.add(settled)
                db.eventDao().insert(
                    EventEntity(
                        wall = nowWall,
                        type = "reward_confirmed",
                        detail = "${record.packageName} trigger=${record.trigger.code} +${record.energy} id=${record.id}"
                    )
                )
            }
        }
        return confirmed
    }

    /**
     * Cancels any still-open PENDING rewards belonging to [enteredPackage] or its entertainment
     * group(s) when the user re-enters during the verification window. Overdue pending rewards are
     * confirmed first so waiting the full window is always honored.
     */
    suspend fun cancelOnReentry(
        enteredPackage: String,
        nowWall: Long,
        nightRestricted: Boolean = false
    ): List<RewardRecord> {
        settleDueRewards(nowWall)
        val pending = db.rewardDao().byStatus(RewardStatus.PENDING.code).map { it.toDomain() }
        if (pending.isEmpty()) return emptyList()
        val rules = quotaRepo.loadRules()
        val exempt = quotaRepo.loadExempt()
        val matchingGroups = RewardEngine.matchingGroupKeys(enteredPackage, rules, exempt).toMutableSet()
        if (nightRestricted) {
            for (p in pending) {
                if (p.groupKey.startsWith("bedtime:")) matchingGroups.add(p.groupKey)
            }
        }
        val toCancel = RewardEngine.dueToCancelOnReentry(pending, enteredPackage, matchingGroups, nowWall)
        val cancelled = ArrayList<RewardRecord>(toCancel.size)
        val reason = "reentered:$enteredPackage"
        for (record in toCancel) {
            val changed = db.rewardDao().cancelIfPending(record.id, nowWall, reason)
            if (changed > 0) {
                cancelled.add(record.copy(status = RewardStatus.CANCELLED, resolvedWall = nowWall, cancelReason = reason))
                db.eventDao().insert(
                    EventEntity(
                        wall = nowWall,
                        type = "reward_cancelled",
                        detail = "${record.packageName} reason=$reason id=${record.id} session=${record.sessionId}"
                    )
                )
            }
        }
        return cancelled
    }

    /**
     * Issues a quiet PENDING bedtime reward when the user stops night entertainment (v0.3 §7).
     * Verified silently in background over the verification window; never clears confirmed history.
     */
    suspend fun tryIssueBedtimeReward(
        packageName: String,
        sessionId: Long,
        nightKey: String,
        onTime: Boolean,
        nowWall: Long
    ): IssueOutcome {
        settleDueRewards(nowWall)
        val settings = loadSettings()
        val groupKey = "bedtime:$nightKey"
        val trigger = if (onTime) RewardTrigger.BEDTIME_ON_TIME else RewardTrigger.BEDTIME_LATE_STOP
        val existing = db.rewardDao().forSessionOrGroupDay(sessionId, nightKey, groupKey).map { it.toDomain() }
        val decision = RewardEngine.evaluateGrant(
            RewardRequest(
                dayKey = nightKey,
                groupKey = groupKey,
                packageName = packageName,
                sessionId = sessionId,
                trigger = trigger,
                nowWall = nowWall,
                hadReminderInSession = true
            ),
            existing = existing,
            settings = settings.copy(maxFullPerGroupPerDay = 1)
        )
        return when (decision) {
            is RewardGrantDecision.Deny -> IssueOutcome(null, decision.reason, settings)
            is RewardGrantDecision.Grant -> {
                val id = db.rewardDao().insert(decision.pending.toEntity())
                val inserted = decision.pending.copy(id = id)
                db.eventDao().insert(
                    EventEntity(
                        wall = nowWall,
                        type = "reward_pending",
                        detail = "$packageName trigger=${trigger.code} +${inserted.energy} night=$nightKey verify=${settings.verifyWindowMinutes}m"
                    )
                )
                IssueOutcome(inserted, null, settings)
            }
        }
    }

    /**
     * Grants a confirmed Growth World reward when the user genuinely completes an Elastic Focus
     * session (SPEC §15). Guarded by sessionId so a focus session can never be rewarded twice.
     */
    suspend fun grantFocusCompleteReward(sessionId: Long, nowWall: Long): RewardRecord? {
        val dayKey = DayKeys.dayKey(nowWall)
        val existing = db.rewardDao().forSessionOrGroupDay(sessionId, dayKey, "focus")
        if (existing.any { it.sessionId == sessionId }) return null
        val record = RewardRecord(
            dayKey = dayKey,
            groupKey = "focus",
            packageName = "app.mindstop.focus",
            sessionId = sessionId,
            trigger = RewardTrigger.FOCUS_COMPLETE,
            energy = RewardTrigger.FOCUS_COMPLETE.defaultEnergy,
            isFull = true,
            status = RewardStatus.CONFIRMED,
            createdWall = nowWall,
            verifyUntilWall = nowWall,
            resolvedWall = nowWall
        )
        val id = db.rewardDao().insert(record.toEntity())
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "reward_confirmed",
                detail = "focus_complete +${record.energy} id=$id session=$sessionId"
            )
        )
        return record.copy(id = id)
    }

    /**
     * Stage 1 morning reward (v0.3 §13): issued as PENDING when the user dismisses the alarm.
     * Guarded against sleep deprivation (< 5h planned sleep opportunity) and limited to once per day.
     * Uses a far-future verifyUntilWall so only explicit Morning Activation completion confirms it.
     */
    suspend fun issueMorningPendingReward(
        dayKey: String,
        sessionId: Long,
        plannedSleepOpportunityMinutes: Int,
        nowWall: Long
    ): RewardRecord? {
        if (!app.mindstop.core.MorningActivationEngine.eligibleForMorningReward(plannedSleepOpportunityMinutes)) {
            db.eventDao().insert(
                EventEntity(
                    wall = nowWall,
                    type = "morning_reward_skipped_short_sleep",
                    detail = "day=$dayKey opp=${plannedSleepOpportunityMinutes}m"
                )
            )
            return null
        }
        val groupKey = "morning:$dayKey"
        val existing = db.rewardDao().forSessionOrGroupDay(sessionId, dayKey, groupKey)
        if (existing.any { it.status == RewardStatus.PENDING.code || it.status == RewardStatus.CONFIRMED.code }) {
            return null
        }
        val record = RewardRecord(
            dayKey = dayKey,
            groupKey = groupKey,
            packageName = "app.mindstop.morning",
            sessionId = sessionId,
            trigger = RewardTrigger.MORNING_WAKE,
            energy = RewardTrigger.MORNING_WAKE.defaultEnergy,
            isFull = true,
            status = RewardStatus.PENDING,
            createdWall = nowWall,
            verifyUntilWall = Long.MAX_VALUE
        )
        val id = db.rewardDao().insert(record.toEntity())
        db.eventDao().insert(
            EventEntity(
                wall = nowWall,
                type = "reward_pending",
                detail = "morning_wake +${record.energy} id=$id day=$dayKey"
            )
        )
        return record.copy(id = id)
    }

    suspend fun confirmMorningReward(rewardId: Long?, nowWall: Long): Boolean {
        if (rewardId == null || rewardId <= 0L) return false
        val changed = db.rewardDao().confirmIfPending(rewardId, nowWall)
        if (changed > 0) {
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "reward_confirmed", detail = "morning_wake id=$rewardId")
            )
            return true
        }
        return false
    }

    suspend fun cancelMorningReward(rewardId: Long?, reason: String, nowWall: Long): Boolean {
        if (rewardId == null || rewardId <= 0L) return false
        val changed = db.rewardDao().cancelIfPending(rewardId, nowWall, reason)
        if (changed > 0) {
            db.eventDao().insert(
                EventEntity(wall = nowWall, type = "reward_cancelled", detail = "morning_wake id=$rewardId reason=$reason")
            )
            return true
        }
        return false
    }

    /**
     * Grants a confirmed Growth World reward for intentional shopping task completion (buying a
     * genuinely needed item on plan, ending needless comparison, or finishing staged research).
     * Guarded by `shop_task:$taskId` so one task can never be farmed repeatedly (v0.3 §26).
     */
    suspend fun grantShoppingTaskReward(taskId: Long, nowWall: Long): RewardRecord? {
        val dayKey = DayKeys.dayKey(nowWall)
        val groupKey = "shop_task:$taskId"
        val existing = db.rewardDao().forSessionOrGroupDay(taskId, dayKey, groupKey)
        if (existing.any { it.groupKey == groupKey }) return null
        val record = RewardRecord(
            dayKey = dayKey,
            groupKey = groupKey,
            packageName = "app.mindstop.shopping",
            sessionId = taskId,
            trigger = RewardTrigger.SHOPPING_INTENTIONAL,
            energy = RewardTrigger.SHOPPING_INTENTIONAL.defaultEnergy,
            isFull = true,
            status = RewardStatus.CONFIRMED,
            createdWall = nowWall,
            verifyUntilWall = nowWall,
            resolvedWall = nowWall
        )
        val id = db.rewardDao().insert(record.toEntity())
        db.eventDao().insert(
            EventEntity(wall = nowWall, type = "reward_confirmed", detail = "shopping_intentional +${record.energy} task=$taskId")
        )
        return record.copy(id = id)
    }

    /**
     * Grants a partial Growth World reward when saving a non-urgent discovery to the 24h cooling
     * backlog (v0.3 §26). Capped at 2 per day so spam entries cannot farm energy.
     */
    suspend fun grantShoppingBacklogReward(backlogId: Long, nowWall: Long): RewardRecord? {
        val dayKey = DayKeys.dayKey(nowWall)
        val groupKey = "shop_backlog:$dayKey"
        val existing = db.rewardDao().forSessionOrGroupDay(backlogId, dayKey, groupKey)
        if (existing.count { it.groupKey == groupKey } >= 2) return null
        val record = RewardRecord(
            dayKey = dayKey,
            groupKey = groupKey,
            packageName = "app.mindstop.shopping",
            sessionId = backlogId,
            trigger = RewardTrigger.SHOPPING_BACKLOG,
            energy = RewardTrigger.SHOPPING_BACKLOG.defaultEnergy,
            isFull = false,
            status = RewardStatus.CONFIRMED,
            createdWall = nowWall,
            verifyUntilWall = nowWall,
            resolvedWall = nowWall
        )
        val id = db.rewardDao().insert(record.toEntity())
        db.eventDao().insert(
            EventEntity(wall = nowWall, type = "reward_confirmed", detail = "shopping_backlog +${record.energy} item=$backlogId")
        )
        return record.copy(id = id)
    }

    private fun RewardEntity.toDomain() = RewardRecord(
        id = id,
        dayKey = dayKey,
        groupKey = groupKey,
        packageName = packageName,
        sessionId = sessionId,
        trigger = RewardTrigger.fromCode(trigger),
        energy = energy,
        isFull = isFull,
        status = RewardStatus.fromCode(status),
        createdWall = createdWall,
        verifyUntilWall = verifyUntilWall,
        resolvedWall = resolvedWall,
        cancelReason = cancelReason
    )

    private fun RewardRecord.toEntity() = RewardEntity(
        id = id,
        dayKey = dayKey,
        groupKey = groupKey,
        packageName = packageName,
        sessionId = sessionId,
        trigger = trigger.code,
        energy = energy,
        isFull = isFull,
        status = status.code,
        createdWall = createdWall,
        verifyUntilWall = verifyUntilWall,
        resolvedWall = resolvedWall,
        cancelReason = cancelReason
    )

    companion object {
        const val KEY_REWARD_SETTINGS = "reward_settings"
    }
}
