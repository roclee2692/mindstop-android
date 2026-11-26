package app.mindstop.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** GLOBAL, GROUP or APP, from app.mindstop.core.RuleScope. */
    val scope: String,
    /** Comma separated package names. */
    val packages: String,
    val dailyLimitMs: Long?,
    val sessionLimitMs: Long?,
    val enabled: Boolean,
    val sortOrder: Int,
    val updatedWall: Long
)

@Entity(
    tableName = "usage_segments",
    indices = [Index("dayKey"), Index("packageName", "dayKey")]
)
data class UsageSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val sessionId: Long,
    val dayKey: String,
    val startWall: Long,
    val endWall: Long,
    val durationMs: Long,
    /** STUDY or FUN (null on rows before v6, treated as entertainment). */
    val mode: String? = null
)

@Entity(
    tableName = "grants",
    indices = [Index("ruleId", "dayKey")]
)
data class GrantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: String,
    val dayKey: String,
    /** DAILY or SESSION, from app.mindstop.core.LimitKind. */
    val kind: String,
    val sessionId: Long?,
    val amountMs: Long,
    /** What created this grant, for example emergency_unlock. */
    val source: String,
    val createdWall: Long
)

@Entity(tableName = "intentions")
data class IntentionEntity(
    @PrimaryKey val sessionId: Long,
    val packageName: String,
    val wall: Long,
    /** Planned minutes, null when the user chose to just look around. */
    val minutes: Int?,
    /** study, fun or errand, from app.mindstop.core.IntentPurpose. Null on rows from before v4. */
    val purpose: String? = null,
    /** What the user wants to do. Stays on the device. */
    val goal: String? = null,
    /** What the user plans to search for. Stays on the device. */
    val keywords: String? = null,
    /** Supervised time the session had used when this plan started. */
    val baseUsedMs: Long? = null,
    /** ACTIVE, DONE or ENDED, from app.mindstop.core.IntentStatus. Null means ACTIVE. */
    val status: String? = null,
    val endedWall: Long? = null
)

/** One question of the local bank. [answer] is 0 for A through 3 for D. */
@Entity(
    tableName = "bank_questions",
    indices = [Index(value = ["questionKey"], unique = true)]
)
data class BankQuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** word, synonym or custom, from app.mindstop.core.QuestionType. */
    val type: String,
    val question: String,
    /** Type plus normalized question text, used to refuse duplicates. */
    val questionKey: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val answer: Int,
    val explanation: String,
    val difficulty: Int,
    val enabled: Boolean,
    /** builtin, or csv for imported questions. */
    val source: String,
    val createdWall: Long
)

/**
 * One block screen challenge. The seed, the config values and the bank snapshot are frozen at
 * start so leaving and coming back cannot change the questions.
 */
@Entity(
    tableName = "challenges",
    indices = [Index("dayKey", "ruleId")]
)
data class ChallengeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayKey: String,
    val ruleId: String,
    val packageName: String,
    val renewalIndex: Int,
    val seed: Long,
    val baseRequired: Int,
    val required: Int,
    val correct: Int,
    val wrong: Int,
    val position: Int,
    val grantMinutes: Int,
    val difficulty: Int,
    /** Comma separated QuestionType codes. */
    val types: String,
    val wrongPolicy: String,
    /** Comma separated bank question ids, in the shuffled order. */
    val bankIds: String,
    /** ACTIVE, PASSED or VOID. */
    val status: String,
    val createdWall: Long,
    val updatedWall: Long
)

@Entity(
    tableName = "cooldowns",
    indices = [Index("endWall")]
)
data class CooldownEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: String,
    val sessionId: Long?,
    val startWall: Long,
    val endWall: Long,
    val cancelled: Boolean
)

@Entity(
    tableName = "rewards",
    indices = [Index("dayKey", "groupKey"), Index("status"), Index("sessionId")]
)
data class RewardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayKey: String,
    /** Specific rule id or pkg:<package> used for daily group cap and cross app verification. */
    val groupKey: String,
    val packageName: String,
    val sessionId: Long,
    /** ACTIVE_EXIT, INTENTION_DECLINE or BLOCK_STOP. */
    val trigger: String,
    val energy: Int,
    val isFull: Boolean,
    /** PENDING, CONFIRMED or CANCELLED. */
    val status: String,
    val createdWall: Long,
    val verifyUntilWall: Long,
    val resolvedWall: Long?,
    val cancelReason: String?
)

@Entity(
    tableName = "reflections",
    indices = [Index("dayKey")]
)
data class ReflectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayKey: String,
    val sessionId: Long,
    val packageName: String,
    /** study, fun or errand. */
    val purpose: String,
    val goal: String,
    val plannedMinutes: Int?,
    val actualMs: Long,
    /** COMPLETED, PARTIAL, NOT_COMPLETED or SWITCHED. */
    val outcome: String,
    /** Comma separated ReflectionReason codes. */
    val reasons: String,
    val createdWall: Long
)

@Entity(
    tableName = "focus_sessions",
    indices = [Index("dayKey")]
)
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayKey: String,
    val goal: String,
    val targetFocusMs: Long,
    val interruptBudgetMs: Long,
    val focusedMs: Long,
    val interruptMs: Long,
    val interruptCount: Int,
    /** Comma separated whitelisted learning tool package names. */
    val whitelistPackages: String,
    /** COMPLETED or ABANDONED. */
    val status: String,
    val startedWall: Long,
    val endedWall: Long
)

/**
 * Nightly Bedtime Wind-down behavioral record (v0.3 SPEC §8).
 * Tracks planned vs actual entertainment stop, overtime, night reopens, and optional self-reported
 * sleep time. Explicitly behavioral only, never medical-grade sleep tracking.
 */
@Entity(
    tableName = "bedtime_logs",
    indices = [Index("updatedWall")]
)
data class BedtimeLogEntity(
    /** Local evening date key (YYYY-MM-DD of the night start). */
    @PrimaryKey val nightKey: String,
    val isWeekend: Boolean,
    val plannedFunEndMin: Int,
    val plannedSleepMin: Int,
    val plannedWakeMin: Int,
    val lastSupervisedWall: Long?,
    val overtimeMinutes: Int,
    val reopenCount: Int,
    val voluntaryStopCount: Int,
    val forcedBlockCount: Int,
    /** Optional user-entered self-reported sleep time (epoch ms). */
    val selfReportedSleepWall: Long?,
    val updatedWall: Long
)

/** Persistent alarm definition for Module 2: Morning Activation (v0.3 SPEC §10–§11). */
@Entity(tableName = "morning_alarms")
data class MorningAlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val hour: Int,
    val minute: Int,
    val repeatDaysMask: Int,
    val enabled: Boolean,
    val ringtoneUri: String,
    val gradualVolumeSeconds: Int,
    val vibrate: Boolean,
    val snoozeMinutes: Int,
    val dismissMethod: String,
    val challengeCount: Int,
    val targetTagOrQr: String,
    val backupReminderEnabled: Boolean,
    val bodyWakeMinutes: Int,
    val morningJoyMinutes: Int,
    val bodyActivities: String,
    val joyActivity: String,
    val joyAllowanceMinutes: Int,
    val createdWall: Long,
    val updatedWall: Long
)

/** One morning wake & 15-minute activation session record (v0.3 SPEC §12–§14). */
@Entity(
    tableName = "morning_sessions",
    indices = [Index("dayKey")]
)
data class MorningSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayKey: String,
    val alarmId: Long,
    val scheduledWall: Long,
    val ringStartedWall: Long,
    val acknowledgedWall: Long?,
    val completedWall: Long?,
    val status: String,
    val responseDelaySec: Int,
    val check5mConfirmed: Boolean,
    val motionHintSeen: Boolean,
    val joyActivity: String,
    val rewardId: Long?,
    val updatedWall: Long
)

/** Planned shopping task / durable research project (v0.3 SPEC §17, §18, §23). */
@Entity(
    tableName = "shopping_tasks",
    indices = [Index("status")]
)
data class ShoppingTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemName: String,
    val purpose: String,
    val budgetCents: Long,
    /** 1 = Low, 2 = Medium, 3 = High */
    val importance: Int,
    /** 1 = Low, 2 = Medium, 3 = High */
    val urgency: Int,
    val mustHaves: String,
    val niceToHaves: String,
    val keywords: String,
    val decisionTier: String,
    val recommendedMinutes: Int,
    val researchMsTotal: Long,
    val status: String,
    val unresolvedQuestions: String,
    val decisionDateKey: String,
    val cooldownHours: Int,
    val createdWall: Long,
    val updatedWall: Long
)

/** One candidate product inside a shopping task (v0.3 SPEC §19–§21). */
@Entity(
    tableName = "product_candidates",
    indices = [Index("taskId")]
)
data class ProductCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val name: String,
    val url: String,
    val brandModelSpec: String,
    /** Only candidates sharing the same non-blank comparisonGroupKey are compared together. */
    val comparisonGroupKey: String,
    /** Decimal string for exact BigDecimal unit price math (e.g. "3", "500"). */
    val quantityText: String,
    /** PIECE, G, KG, ML, L, CM, M */
    val unitCode: String,
    val priceCents: Long,
    val discountCents: Long,
    val shippingCents: Long,
    val meetsMustHaves: Boolean,
    val evidenceNote: String,
    val warrantyNote: String,
    val notes: String,
    val source: String,
    val collectedWall: Long
)

/** Unplanned shopping discovery cooling pool (v0.3 SPEC §24). */
@Entity(
    tableName = "purchase_backlog",
    indices = [Index("status")]
)
data class PurchaseBacklogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val estimatedCents: Long,
    val reasonNeeded: String,
    val hasAlternative: Boolean,
    val isUrgent: Boolean,
    val url: String,
    val cooldownHours: Int,
    val cooldownEndsWall: Long,
    val status: String,
    val createdWall: Long,
    val updatedWall: Long
)

/** Manually confirmed actual expense record (strictly separate from candidate prices, v0.3 SPEC §25). */
@Entity(
    tableName = "expense_records",
    indices = [Index("monthKey")]
)
data class ExpenseRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** YYYY-MM local month key. */
    val monthKey: String,
    val dayKey: String,
    val category: String,
    val title: String,
    val amountCents: Long,
    val refundCents: Long,
    val taskId: Long?,
    val notes: String,
    val createdWall: Long
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "events", indices = [Index("wall")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wall: Long,
    /** For example block_shown, unlock_used, rule_changed, clock_ignored. */
    val type: String,
    val detail: String
)
