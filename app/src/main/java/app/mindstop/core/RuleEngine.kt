package app.mindstop.core

enum class RuleScope { GLOBAL, GROUP, APP }

enum class LimitKind { DAILY, SESSION }

data class QuotaRule(
    val id: String,
    val name: String,
    val scope: RuleScope,
    val packages: Set<String>,
    val dailyLimitMs: Long?,
    val sessionLimitMs: Long?,
    val enabled: Boolean = true
)

/**
 * Extra time the user explicitly unlocked for one rule. A daily grant has no session id. A session
 * grant only counts inside the session it was given in.
 */
data class QuotaGrant(
    val ruleId: String,
    val dayKey: String,
    val kind: LimitKind,
    val sessionId: Long?,
    val amountMs: Long
)

/**
 * A wait the user chose on the block screen. While it runs, every app the rule counts stays
 * blocked, whatever quota is left. [sessionId] is the session that ran out, so the engine can start
 * a fresh one once the wait is over.
 */
data class QuotaCooldown(
    val ruleId: String,
    val endWall: Long,
    val sessionId: Long?
)

data class RuleStatus(
    val rule: QuotaRule,
    val kind: LimitKind,
    val limitMs: Long,
    val usedMs: Long,
    val grantedMs: Long
) {
    val remainingMs: Long get() = limitMs + grantedMs - usedMs
}

data class Verdict(
    val packageName: String,
    val supervised: Boolean,
    val statuses: List<RuleStatus>,
    val cooldowns: List<QuotaCooldown> = emptyList()
) {
    /** The status with the least time left. This is the rule that decides. */
    val binding: RuleStatus? get() = statuses.minByOrNull { it.remainingMs }
    val remainingMs: Long? get() = binding?.remainingMs
    val inCooldown: Boolean get() = supervised && cooldowns.isNotEmpty()
    val cooldownUntilWall: Long? get() = cooldowns.maxOfOrNull { it.endWall }
    val blocked: Boolean get() = supervised && ((remainingMs ?: Long.MAX_VALUE) <= 0L || cooldowns.isNotEmpty())
    val exhausted: List<RuleStatus> get() = statuses.filter { it.remainingMs <= 0L }

    /**
     * A cooldown only helps when every exhausted limit is a per session one: the wait ends the
     * session, while a used up daily quota stays used up after any wait.
     */
    val cooldownHelps: Boolean
        get() = exhausted.isNotEmpty() && exhausted.all { it.kind == LimitKind.SESSION }
}

object RuleEngine {

    /**
     * Packages a rule counts. The global entertainment rule covers every app that any enabled rule
     * supervises, plus the apps added to it directly.
     */
    fun effectivePackages(rule: QuotaRule, rules: List<QuotaRule>, exempt: Set<String>): Set<String> {
        val base = if (rule.scope == RuleScope.GLOBAL) {
            rules.filter { it.enabled }.flatMapTo(HashSet()) { it.packages } + rule.packages
        } else {
            rule.packages
        }
        return base - exempt
    }

    fun supervisedPackages(rules: List<QuotaRule>, exempt: Set<String>): Set<String> =
        rules.filter { it.enabled && it.scope != RuleScope.GLOBAL }.flatMapTo(HashSet()) { it.packages }
            .plus(rules.filter { it.enabled && it.scope == RuleScope.GLOBAL }.flatMap { it.packages })
            .minus(exempt)

    fun evaluate(
        packageName: String,
        rules: List<QuotaRule>,
        dailyUsageByPackage: Map<String, Long>,
        sessionUsageByPackage: Map<String, Long>,
        grants: List<QuotaGrant>,
        dayKey: String,
        sessionId: Long?,
        exempt: Set<String>,
        cooldowns: List<QuotaCooldown> = emptyList(),
        nowWall: Long = 0L
    ): Verdict {
        if (packageName in exempt || packageName !in supervisedPackages(rules, exempt)) {
            return Verdict(packageName, supervised = false, statuses = emptyList())
        }
        val statuses = ArrayList<RuleStatus>()
        val activeCooldowns = ArrayList<QuotaCooldown>()
        for (rule in rules) {
            if (!rule.enabled) continue
            val packages = effectivePackages(rule, rules, exempt)
            if (packageName !in packages) continue
            cooldowns.filterTo(activeCooldowns) { it.ruleId == rule.id && it.endWall > nowWall }

            rule.dailyLimitMs?.let { limit ->
                val used = packages.sumOf { dailyUsageByPackage[it] ?: 0L }
                val granted = grants.filter {
                    it.ruleId == rule.id && it.kind == LimitKind.DAILY && it.dayKey == dayKey
                }.sumOf { it.amountMs }
                statuses.add(RuleStatus(rule, LimitKind.DAILY, limit, used, granted))
            }
            rule.sessionLimitMs?.let { limit ->
                val used = packages.sumOf { sessionUsageByPackage[it] ?: 0L }
                val granted = if (sessionId == null) 0L else grants.filter {
                    it.ruleId == rule.id && it.kind == LimitKind.SESSION && it.sessionId == sessionId
                }.sumOf { it.amountMs }
                statuses.add(RuleStatus(rule, LimitKind.SESSION, limit, used, granted))
            }
        }
        return Verdict(packageName, supervised = true, statuses = statuses, cooldowns = activeCooldowns)
    }

    /**
     * Grants that give exactly [extraMs] of use to every rule that is currently exhausted, so the
     * unlock covers the rules the user saw and nothing else.
     */
    fun grantsToUnlock(verdict: Verdict, extraMs: Long, dayKey: String, sessionId: Long?): List<QuotaGrant> =
        verdict.exhausted.map { status ->
            QuotaGrant(
                ruleId = status.rule.id,
                dayKey = dayKey,
                kind = status.kind,
                sessionId = if (status.kind == LimitKind.SESSION) sessionId else null,
                amountMs = extraMs - status.remainingMs
            )
        }

    /** Cooldowns for every exhausted per session limit, each lasting [durationMs] from [nowWall]. */
    fun cooldownsFor(verdict: Verdict, durationMs: Long, nowWall: Long, sessionId: Long?): List<QuotaCooldown> =
        verdict.exhausted.filter { it.kind == LimitKind.SESSION }
            .map { it.rule.id }
            .distinct()
            .map { QuotaCooldown(it, nowWall + durationMs, sessionId) }
}
