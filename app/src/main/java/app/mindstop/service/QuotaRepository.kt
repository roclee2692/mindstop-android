package app.mindstop.service

import android.content.Context
import app.mindstop.core.DayKeys
import app.mindstop.core.IntentPurpose
import app.mindstop.core.IntentStatus
import app.mindstop.core.IntentionSettings
import app.mindstop.core.LimitKind
import app.mindstop.core.QuotaCooldown
import app.mindstop.core.QuotaGrant
import app.mindstop.core.QuotaRule
import app.mindstop.core.RuleEngine
import app.mindstop.core.RuleScope
import app.mindstop.core.RuleStatus
import app.mindstop.core.SessionIntent
import app.mindstop.core.Verdict
import app.mindstop.data.CooldownEntity
import app.mindstop.data.EventEntity
import app.mindstop.data.GrantEntity
import app.mindstop.data.MetaEntity
import app.mindstop.data.MindstopDatabase
import app.mindstop.data.RuleEntity
import app.mindstop.data.UsageSegmentEntity
import java.util.UUID

/**
 * Single doorway into mindstop_db for the engine, the block screen, and the home UI.
 * Safe to call from any process: MindstopDatabase uses multi instance invalidation.
 */
class QuotaRepository(context: Context) {

    private val db = MindstopDatabase.getInstance(context)

    suspend fun loadRules(): List<QuotaRule> =
        db.ruleDao().getAll().map { it.toCore() }

    fun observeRules() = db.ruleDao().observeAll()

    suspend fun saveRule(rule: QuotaRule, sortOrder: Int, nowWall: Long) {
        db.ruleDao().upsert(rule.toEntity(sortOrder, nowWall))
        logEvent("rule_changed", "${rule.id} ${rule.name}", nowWall)
    }

    suspend fun deleteRule(id: String, nowWall: Long) {
        db.ruleDao().delete(id)
        logEvent("rule_changed", "deleted $id", nowWall)
    }

    suspend fun loadRuleEntity(id: String): RuleEntity? = db.ruleDao().getById(id)

    suspend fun nextSortOrder(): Int = (db.ruleDao().getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1

    suspend fun loadExempt(): Set<String> =
        db.metaDao().get(KEY_EXEMPT)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    suspend fun saveExempt(packages: Set<String>) {
        db.metaDao().put(MetaEntity(KEY_EXEMPT, packages.sorted().joinToString(",")))
    }

    suspend fun loadMeta(key: String): String? = db.metaDao().get(key)

    suspend fun saveMeta(key: String, value: String) {
        db.metaDao().put(MetaEntity(key, value))
    }

    suspend fun insertSegments(segments: List<UsageSegmentEntity>) {
        if (segments.isNotEmpty()) db.usageSegmentDao().insertAll(segments)
    }

    suspend fun dailyTotals(dayKey: String): Map<String, Long> =
        db.usageSegmentDao().totalsByPackage(dayKey).associate { it.packageName to it.total }

    suspend fun studyTotalForDay(dayKey: String): Long =
        db.usageSegmentDao().studyTotalForDay(dayKey)

    /** Entertainment session totals (excludes explicit STUDY mode segments per SPEC §7). */
    suspend fun sessionTotals(sessionId: Long): Map<String, Long> =
        db.usageSegmentDao().segmentsOfSession(sessionId)
            .filter { it.mode != MODE_STUDY }
            .groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.durationMs } }

    /** All supervised session totals including STUDY mode (used for Study plan countdowns). */
    suspend fun allSessionTotals(sessionId: Long): Map<String, Long> =
        db.usageSegmentDao().segmentsOfSession(sessionId)
            .groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.durationMs } }

    suspend fun switchIntentionToEntertainment(sessionId: Long, wall: Long) {
        if (db.intentionDao().updatePurpose(sessionId, IntentPurpose.FUN.code) > 0) {
            logEvent("study_switched_to_fun", "session=$sessionId", wall)
        }
    }

    suspend fun loadGrants(dayKey: String): List<QuotaGrant> =
        db.grantDao().activeFor(dayKey).map {
            QuotaGrant(it.ruleId, it.dayKey, LimitKind.valueOf(it.kind), it.sessionId, it.amountMs)
        }

    suspend fun addGrants(grants: List<QuotaGrant>, source: String, nowWall: Long) {
        grants.forEach {
            db.grantDao().insert(
                GrantEntity(
                    ruleId = it.ruleId,
                    dayKey = it.dayKey,
                    kind = it.kind.name,
                    sessionId = it.sessionId,
                    amountMs = it.amountMs,
                    source = source,
                    createdWall = nowWall
                )
            )
        }
    }

    suspend fun saveIntention(
        sessionId: Long,
        packageName: String,
        purpose: IntentPurpose,
        goal: String,
        keywords: String,
        minutes: Int?,
        baseUsedMs: Long,
        wall: Long
    ) {
        db.intentionDao().upsert(
            app.mindstop.data.IntentionEntity(
                sessionId = sessionId,
                packageName = packageName,
                wall = wall,
                minutes = minutes,
                purpose = purpose.code,
                goal = goal,
                keywords = keywords,
                baseUsedMs = baseUsedMs,
                status = IntentStatus.ACTIVE.name
            )
        )
        // Goal and keywords stay out of the event log, which the diagnostics screen shows.
        logEvent("intention_set", "$packageName ${purpose.code} ${minutes?.let { "${it}min" } ?: "free"}", wall)
    }

    suspend fun extendIntention(sessionId: Long, extraMinutes: Int, wall: Long) {
        if (db.intentionDao().extend(sessionId, extraMinutes) > 0) {
            logEvent("intention_extended", "session=$sessionId +${extraMinutes}min", wall)
        }
    }

    suspend fun finishIntention(sessionId: Long, status: IntentStatus, wall: Long) {
        if (db.intentionDao().finish(sessionId, status.name, wall) > 0) {
            logEvent("intention_finished", "session=$sessionId ${status.name}", wall)
        }
    }

    suspend fun intentionForSession(sessionId: Long): app.mindstop.data.IntentionEntity? =
        db.intentionDao().forSession(sessionId)

    suspend fun sessionIntent(sessionId: Long): SessionIntent? =
        intentionForSession(sessionId)?.toSessionIntent()

    /** Supervised time used so far in [sessionId], from stored segments only. */
    suspend fun supervisedSessionUsage(sessionId: Long): Long {
        val supervised = RuleEngine.supervisedPackages(loadRules(), loadExempt())
        return sessionTotals(sessionId).filterKeys { it in supervised }.values.sum()
    }

    suspend fun loadIntentionSettings(): IntentionSettings =
        IntentionSettings.decode(db.metaDao().get(KEY_INTENTION_SETTINGS))

    suspend fun saveIntentionSettings(settings: IntentionSettings, wall: Long) {
        db.metaDao().put(MetaEntity(KEY_INTENTION_SETTINGS, settings.encode()))
        logEvent("intention_settings_changed", settings.encode(), wall)
    }

    /**
     * When the calm wait for [token] began. Reopening the prompt for the same token keeps the
     * first start, so leaving and coming back does not restart or skip the wait.
     */
    suspend fun calmWaitStart(token: String, nowWall: Long): Long {
        val saved = db.metaDao().get(KEY_CALM_WAIT)?.split('#', limit = 2)
        if (saved != null && saved.size == 2 && saved[1] == token) {
            saved[0].toLongOrNull()?.let { if (it <= nowWall) return it }
        }
        db.metaDao().put(MetaEntity(KEY_CALM_WAIT, "$nowWall#$token"))
        return nowWall
    }

    suspend fun clearCalmWait() {
        db.metaDao().put(MetaEntity(KEY_CALM_WAIT, ""))
    }
    suspend fun isIntentionPromptEnabled(): Boolean =
        db.metaDao().get(KEY_INTENTION_ENABLED)?.let { it == "1" } ?: true

    suspend fun setIntentionPromptEnabled(enabled: Boolean) {
        db.metaDao().put(MetaEntity(KEY_INTENTION_ENABLED, if (enabled) "1" else "0"))
    }

    suspend fun isReminderEnabled(): Boolean =
        db.metaDao().get(KEY_REMINDERS_ENABLED)?.let { it == "1" } ?: true

    suspend fun setReminderEnabled(enabled: Boolean) {
        db.metaDao().put(MetaEntity(KEY_REMINDERS_ENABLED, if (enabled) "1" else "0"))
    }

    suspend fun loadFiredReminders(): MutableSet<String> =
        (db.metaDao().get(KEY_FIRED_REMINDERS)?.split(';')?.filter { it.isNotBlank() } ?: emptyList())
            .toMutableSet()

    suspend fun saveFiredReminders(fired: Set<String>) {
        db.metaDao().put(MetaEntity(KEY_FIRED_REMINDERS, fired.joinToString(";")))
    }

    suspend fun logEvent(type: String, detail: String, wall: Long) {
        db.eventDao().insert(EventEntity(wall = wall, type = type, detail = detail.take(500)))
    }

    fun observeRecentEvents(limit: Int) = db.eventDao().observeRecent(limit)

    suspend fun recentDays(limit: Int): List<String> = db.usageSegmentDao().recentDays(limit)

    suspend fun recentSegmentsOfDay(dayKey: String, limit: Int) =
        db.usageSegmentDao().recentOfDay(dayKey, limit)

    /**
     * What the database alone says about [packageName] right now. Used by the block screen and
     * the home UI, which cannot see the service's still open in memory segment.
     */
    suspend fun verdictFor(
        packageName: String,
        dayKey: String,
        sessionId: Long?,
        nowWall: Long
    ): Verdict {
        val rules = loadRules()
        val exempt = loadExempt()
        val daily = dailyTotals(dayKey)
        val session = sessionId?.let { sessionTotals(it) } ?: emptyMap()
        val grants = loadGrants(dayKey)
        return RuleEngine.evaluate(
            packageName, rules, daily, session, grants, dayKey, sessionId, exempt, loadCooldowns(nowWall), nowWall
        )
    }

    // Cooldowns

    suspend fun loadCooldowns(nowWall: Long): List<QuotaCooldown> =
        db.cooldownDao().active(nowWall).map { QuotaCooldown(it.ruleId, it.endWall, it.sessionId) }

    suspend fun startCooldowns(cooldowns: List<QuotaCooldown>, startWall: Long) {
        if (cooldowns.isEmpty()) return
        db.cooldownDao().insertAll(cooldowns.map {
            CooldownEntity(ruleId = it.ruleId, sessionId = it.sessionId, startWall = startWall, endWall = it.endWall, cancelled = false)
        })
        logEvent("cooldown_started", cooldowns.joinToString(" ") { "rule=${it.ruleId}" } + " until=${cooldowns.maxOf { it.endWall }}", startWall)
    }

    suspend fun cancelCooldowns(ruleIds: List<String>, nowWall: Long) {
        if (ruleIds.isEmpty()) return
        val count = db.cooldownDao().cancel(ruleIds, nowWall)
        if (count > 0) logEvent("cooldown_cancelled", ruleIds.joinToString(" ") { "rule=$it" }, nowWall)
    }

    /** True when a cooldown taken in [sessionId] is over, so the next use must be a new session. */
    suspend fun cooldownEndedForSession(sessionId: Long, nowWall: Long): Boolean =
        db.cooldownDao().endedForSession(sessionId, nowWall) > 0

    /**
     * Best guess of trusted time outside the service: the system clock, but never earlier than
     * the last time the service trusted, so turning the clock back cannot shorten a wait.
     */
    suspend fun approxTrustedNow(): Long =
        maxOf(System.currentTimeMillis(), loadMeta(KEY_LAST_TRUSTED_WALL)?.toLongOrNull() ?: Long.MIN_VALUE)

    /** Today's daily status of every enabled rule that has a daily limit, for the home screen. */
    suspend fun dailyRuleStatuses(dayKey: String, sessionId: Long?): List<RuleStatus> {
        val rules = loadRules()
        val exempt = loadExempt()
        val daily = dailyTotals(dayKey)
        val session = sessionId?.let { sessionTotals(it) } ?: emptyMap()
        val grants = loadGrants(dayKey)
        val statuses = ArrayList<RuleStatus>()
        for (rule in rules) {
            if (!rule.enabled) continue
            val packages = RuleEngine.effectivePackages(rule, rules, exempt)
            rule.dailyLimitMs?.let { limit ->
                val used = packages.sumOf { daily[it] ?: 0L }
                val granted = grants.filter {
                    it.ruleId == rule.id && it.kind == LimitKind.DAILY && it.dayKey == dayKey
                }.sumOf { it.amountMs }
                statuses.add(RuleStatus(rule, LimitKind.DAILY, limit, used, granted))
            }
            rule.sessionLimitMs?.let { limit ->
                val used = packages.sumOf { session[it] ?: 0L }
                val granted = if (sessionId == null) 0L else grants.filter {
                    it.ruleId == rule.id && it.kind == LimitKind.SESSION && it.sessionId == sessionId
                }.sumOf { it.amountMs }
                statuses.add(RuleStatus(rule, LimitKind.SESSION, limit, used, granted))
            }
        }
        return statuses
    }

    suspend fun ensureDefaultRules(defaultName: String, nowWall: Long) {
        if (db.metaDao().get(KEY_DEFAULT_RULES_SEEDED) != null) return
        db.metaDao().put(MetaEntity(KEY_DEFAULT_RULES_SEEDED, "1"))
        if (db.ruleDao().getAll().isNotEmpty()) return
        val global = QuotaRule(
            id = UUID.randomUUID().toString(),
            name = defaultName,
            scope = RuleScope.GLOBAL,
            packages = emptySet(),
            dailyLimitMs = DEFAULT_GLOBAL_DAILY_MS,
            sessionLimitMs = null
        )
        db.ruleDao().upsert(global.toEntity(0, nowWall))
    }

    companion object {
        const val KEY_EXEMPT = "exempt_packages"
        const val KEY_CLOCK_ANCHOR = "clock_anchor"
        const val KEY_LAST_TRUSTED_WALL = "last_trusted_wall"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_LAST_SUPERVISED_WALL = "last_supervised_wall"
        const val KEY_DEFAULT_RULES_SEEDED = "default_rules_seeded"
        const val KEY_INTENTION_ENABLED = "intention_enabled"
        const val KEY_REMINDERS_ENABLED = "reminders_enabled"
        const val KEY_FIRED_REMINDERS = "fired_reminders"
        const val KEY_INTENTION_SETTINGS = "intention_settings"
        const val KEY_CALM_WAIT = "calm_wait"
        const val MODE_STUDY = "STUDY"
        const val MODE_FUN = "FUN"
        const val DEFAULT_GLOBAL_DAILY_MS = 60 * 60_000L
    }
}

fun RuleEntity.toCore(): QuotaRule = QuotaRule(
    id = id,
    name = name,
    scope = RuleScope.valueOf(scope),
    packages = packages.split(',').filter { it.isNotBlank() }.toSet(),
    dailyLimitMs = dailyLimitMs,
    sessionLimitMs = sessionLimitMs,
    enabled = enabled
)

fun QuotaRule.toEntity(sortOrder: Int, nowWall: Long): RuleEntity = RuleEntity(
    id = id,
    name = name,
    scope = scope.name,
    packages = packages.sorted().joinToString(","),
    dailyLimitMs = dailyLimitMs,
    sessionLimitMs = sessionLimitMs,
    enabled = enabled,
    sortOrder = sortOrder,
    updatedWall = nowWall
)

fun app.mindstop.data.IntentionEntity.toSessionIntent(): SessionIntent = SessionIntent(
    purpose = IntentPurpose.fromCode(purpose) ?: IntentPurpose.FUN,
    goal = goal.orEmpty(),
    keywords = keywords.orEmpty(),
    minutes = minutes,
    baseUsedMs = baseUsedMs ?: 0L,
    status = status?.let { s -> IntentStatus.entries.firstOrNull { it.name == s } } ?: IntentStatus.ACTIVE,
    wall = wall
)

/** Splits a usage segment at local midnights so every row belongs to one day. */
fun segmentRows(
    packageName: String,
    sessionId: Long,
    startWall: Long,
    endWall: Long,
    mode: String? = null
): List<UsageSegmentEntity> =
    DayKeys.splitByDay(startWall, endWall).map { slice ->
        UsageSegmentEntity(
            packageName = packageName,
            sessionId = sessionId,
            dayKey = slice.dayKey,
            startWall = slice.startWall,
            endWall = slice.endWall,
            durationMs = slice.durationMs,
            mode = mode
        )
    }
