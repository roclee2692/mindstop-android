package app.mindstop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY sortOrder, name")
    suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getById(id: String): RuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface UsageSegmentDao {
    @Insert
    suspend fun insertAll(segments: List<UsageSegmentEntity>)

    /** Entertainment daily totals (excludes explicit STUDY mode segments per SPEC §7). */
    @Query("SELECT packageName, SUM(durationMs) AS total FROM usage_segments WHERE dayKey = :dayKey AND (mode IS NULL OR mode != 'STUDY') GROUP BY packageName")
    suspend fun totalsByPackage(dayKey: String): List<PackageTotal>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM usage_segments WHERE dayKey = :dayKey AND mode = 'STUDY'")
    suspend fun studyTotalForDay(dayKey: String): Long

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM usage_segments WHERE dayKey = :dayKey AND (mode IS NULL OR mode != 'STUDY') AND packageName IN (:packages)")
    suspend fun totalForPackages(dayKey: String, packages: List<String>): Long

    @Query("SELECT * FROM usage_segments WHERE sessionId = :sessionId")
    suspend fun segmentsOfSession(sessionId: Long): List<UsageSegmentEntity>

    @Query("SELECT * FROM usage_segments WHERE dayKey = :dayKey ORDER BY startWall DESC LIMIT :limit")
    suspend fun recentOfDay(dayKey: String, limit: Int): List<UsageSegmentEntity>

    @Query("SELECT DISTINCT dayKey FROM usage_segments ORDER BY dayKey DESC LIMIT :limit")
    suspend fun recentDays(limit: Int): List<String>

    @Query("SELECT * FROM usage_segments ORDER BY startWall DESC LIMIT :limit")
    suspend fun allRecent(limit: Int): List<UsageSegmentEntity>

    @Query("DELETE FROM usage_segments WHERE dayKey < :beforeDayKey")
    suspend fun purgeBefore(beforeDayKey: String): Int
}

data class PackageTotal(
    val packageName: String,
    val total: Long
)

@Dao
interface GrantDao {
    @Insert
    suspend fun insert(grant: GrantEntity): Long

    @Query("SELECT * FROM grants WHERE dayKey = :dayKey OR kind = 'SESSION'")
    suspend fun activeFor(dayKey: String): List<GrantEntity>

    @Query("DELETE FROM grants WHERE dayKey < :beforeDayKey AND kind = 'DAILY'")
    suspend fun purgeDailyBefore(beforeDayKey: String): Int
}

@Dao
interface IntentionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(intention: IntentionEntity)

    @Query("SELECT * FROM intentions WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: Long): IntentionEntity?

    @Query("SELECT * FROM intentions ORDER BY wall DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<IntentionEntity>

    @Query("UPDATE intentions SET minutes = minutes + :extraMinutes WHERE sessionId = :sessionId AND minutes IS NOT NULL")
    suspend fun extend(sessionId: Long, extraMinutes: Int): Int

    @Query("UPDATE intentions SET status = :status, endedWall = :wall WHERE sessionId = :sessionId")
    suspend fun finish(sessionId: Long, status: String, wall: Long): Int

    @Query("UPDATE intentions SET purpose = :purpose WHERE sessionId = :sessionId")
    suspend fun updatePurpose(sessionId: Long, purpose: String): Int
}

@Dao
interface BankQuestionDao {
    @Query("SELECT * FROM bank_questions ORDER BY type, id")
    fun observeAll(): Flow<List<BankQuestionEntity>>

    @Query("SELECT * FROM bank_questions WHERE enabled = 1")
    suspend fun enabled(): List<BankQuestionEntity>

    @Query("SELECT * FROM bank_questions WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<BankQuestionEntity>

    @Query("SELECT questionKey FROM bank_questions")
    suspend fun allKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(questions: List<BankQuestionEntity>): List<Long>

    @Query("UPDATE bank_questions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM bank_questions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChallengeDao {
    @Insert
    suspend fun insert(challenge: ChallengeEntity): Long

    @Query("SELECT * FROM challenges WHERE id = :id")
    suspend fun byId(id: Long): ChallengeEntity?

    @Query("SELECT * FROM challenges WHERE dayKey = :dayKey AND ruleId = :ruleId AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    suspend fun active(dayKey: String, ruleId: String): ChallengeEntity?

    @Query("SELECT COUNT(*) FROM challenges WHERE dayKey = :dayKey AND ruleId = :ruleId AND status = 'PASSED'")
    suspend fun passedCount(dayKey: String, ruleId: String): Int

    @Query("UPDATE challenges SET required = :required, correct = :correct, wrong = :wrong, position = :position, updatedWall = :wall WHERE id = :id AND status = 'ACTIVE'")
    suspend fun saveProgress(id: Long, required: Int, correct: Int, wrong: Int, position: Int, wall: Long): Int

    @Query("UPDATE challenges SET status = :status, updatedWall = :wall WHERE id = :id AND status = 'ACTIVE'")
    suspend fun finish(id: Long, status: String, wall: Long): Int
}

@Dao
interface CooldownDao {
    @Insert
    suspend fun insertAll(cooldowns: List<CooldownEntity>)

    @Query("SELECT * FROM cooldowns WHERE cancelled = 0 AND endWall > :nowWall")
    suspend fun active(nowWall: Long): List<CooldownEntity>

    @Query("SELECT COUNT(*) FROM cooldowns WHERE cancelled = 0 AND sessionId = :sessionId AND endWall <= :nowWall")
    suspend fun endedForSession(sessionId: Long, nowWall: Long): Int

    @Query("UPDATE cooldowns SET cancelled = 1 WHERE cancelled = 0 AND endWall > :nowWall AND ruleId IN (:ruleIds)")
    suspend fun cancel(ruleIds: List<String>, nowWall: Long): Int
}

@Dao
interface RewardDao {
    @Query("SELECT * FROM rewards ORDER BY createdWall DESC")
    fun observeAll(): Flow<List<RewardEntity>>

    @Query("SELECT * FROM rewards ORDER BY createdWall DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RewardEntity>>

    @Query("SELECT * FROM rewards WHERE status = :status ORDER BY createdWall ASC")
    suspend fun byStatus(status: String): List<RewardEntity>

    @Query("SELECT * FROM rewards ORDER BY createdWall DESC LIMIT :limit")
    suspend fun allRecent(limit: Int): List<RewardEntity>

    @Query("SELECT * FROM rewards WHERE sessionId = :sessionId OR (dayKey = :dayKey AND groupKey = :groupKey)")
    suspend fun forSessionOrGroupDay(sessionId: Long, dayKey: String, groupKey: String): List<RewardEntity>

    @Insert
    suspend fun insert(reward: RewardEntity): Long

    @Query("UPDATE rewards SET status = 'CONFIRMED', resolvedWall = :resolvedWall WHERE id = :id AND status = 'PENDING'")
    suspend fun confirmIfPending(id: Long, resolvedWall: Long): Int

    @Query("UPDATE rewards SET status = 'CANCELLED', resolvedWall = :resolvedWall, cancelReason = :reason WHERE id = :id AND status = 'PENDING'")
    suspend fun cancelIfPending(id: Long, resolvedWall: Long, reason: String): Int
}

@Dao
interface ReflectionDao {
    @Insert
    suspend fun insert(reflection: ReflectionEntity): Long

    @Query("SELECT * FROM reflections ORDER BY createdWall DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReflectionEntity>>

    @Query("SELECT * FROM reflections WHERE dayKey = :dayKey ORDER BY createdWall DESC")
    suspend fun forDay(dayKey: String): List<ReflectionEntity>

    @Query("SELECT * FROM reflections ORDER BY createdWall DESC LIMIT :limit")
    suspend fun allRecent(limit: Int): List<ReflectionEntity>
}

@Dao
interface FocusSessionDao {
    @Insert
    suspend fun insert(session: FocusSessionEntity): Long

    @Query("SELECT * FROM focus_sessions ORDER BY endedWall DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions ORDER BY endedWall DESC LIMIT :limit")
    suspend fun allRecent(limit: Int): List<FocusSessionEntity>

    @Query("SELECT COALESCE(SUM(focusedMs), 0) FROM focus_sessions WHERE dayKey = :dayKey AND status = 'COMPLETED'")
    suspend fun completedTotalMsForDay(dayKey: String): Long
}

@Dao
interface BedtimeLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: BedtimeLogEntity)

    @Query("SELECT * FROM bedtime_logs WHERE nightKey = :nightKey")
    suspend fun forNight(nightKey: String): BedtimeLogEntity?

    @Query("SELECT * FROM bedtime_logs ORDER BY nightKey DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BedtimeLogEntity>>

    @Query("SELECT * FROM bedtime_logs ORDER BY nightKey DESC LIMIT :limit")
    suspend fun allRecent(limit: Int): List<BedtimeLogEntity>
}

@Dao
interface MorningAlarmDao {
    @Query("SELECT * FROM morning_alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<MorningAlarmEntity>>

    @Query("SELECT * FROM morning_alarms ORDER BY hour, minute, id")
    suspend fun getAll(): List<MorningAlarmEntity>

    @Query("SELECT * FROM morning_alarms WHERE id = :id")
    suspend fun byId(id: Long): MorningAlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: MorningAlarmEntity): Long

    @Query("DELETE FROM morning_alarms WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MorningSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: MorningSessionEntity): Long

    @Query("SELECT * FROM morning_sessions WHERE id = :id")
    suspend fun byId(id: Long): MorningSessionEntity?

    @Query("SELECT * FROM morning_sessions WHERE status IN ('RINGING', 'ACKNOWLEDGED', 'ACTIVATION') ORDER BY id DESC LIMIT 1")
    suspend fun activeSession(): MorningSessionEntity?

    @Query("SELECT * FROM morning_sessions ORDER BY ringStartedWall DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MorningSessionEntity>>

    @Query("SELECT * FROM morning_sessions ORDER BY ringStartedWall DESC LIMIT :limit")
    suspend fun allRecent(limit: Int): List<MorningSessionEntity>
}

@Dao
interface ShoppingTaskDao {
    @Query("SELECT * FROM shopping_tasks ORDER BY updatedWall DESC")
    fun observeAll(): Flow<List<ShoppingTaskEntity>>

    @Query("SELECT * FROM shopping_tasks WHERE status = 'RESEARCHING' ORDER BY updatedWall DESC")
    suspend fun activeTasks(): List<ShoppingTaskEntity>

    @Query("SELECT * FROM shopping_tasks WHERE id = :id")
    suspend fun byId(id: Long): ShoppingTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ShoppingTaskEntity): Long

    @Query("DELETE FROM shopping_tasks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ProductCandidateDao {
    @Query("SELECT * FROM product_candidates WHERE taskId = :taskId ORDER BY (priceCents - discountCents + shippingCents) ASC")
    fun observeForTask(taskId: Long): Flow<List<ProductCandidateEntity>>

    @Query("SELECT * FROM product_candidates WHERE taskId = :taskId ORDER BY collectedWall DESC")
    suspend fun forTask(taskId: Long): List<ProductCandidateEntity>

    @Query("SELECT * FROM product_candidates ORDER BY collectedWall DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProductCandidateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(candidate: ProductCandidateEntity): Long

    @Query("DELETE FROM product_candidates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PurchaseBacklogDao {
    @Query("SELECT * FROM purchase_backlog ORDER BY createdWall DESC")
    fun observeAll(): Flow<List<PurchaseBacklogEntity>>

    @Query("SELECT * FROM purchase_backlog WHERE id = :id")
    suspend fun byId(id: Long): PurchaseBacklogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PurchaseBacklogEntity): Long

    @Query("DELETE FROM purchase_backlog WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ExpenseRecordDao {
    @Query("SELECT * FROM expense_records WHERE monthKey = :monthKey ORDER BY createdWall DESC")
    fun observeForMonth(monthKey: String): Flow<List<ExpenseRecordEntity>>

    @Query("SELECT * FROM expense_records ORDER BY createdWall DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ExpenseRecordEntity): Long

    @Query("UPDATE expense_records SET refundCents = :refundCents WHERE id = :id")
    suspend fun updateRefund(id: Long, refundCents: Long): Int

    @Query("DELETE FROM expense_records WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: MetaEntity)
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events ORDER BY wall DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY wall DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventEntity>

    @Query("DELETE FROM events WHERE wall < :beforeWall")
    suspend fun purgeBefore(beforeWall: Long): Int
}
