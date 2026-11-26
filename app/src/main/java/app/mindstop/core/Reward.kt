package app.mindstop.core

/** Lifecycle state of a growth reward (SPEC §11). */
enum class RewardStatus(val code: String) {
    PENDING("PENDING"),
    CONFIRMED("CONFIRMED"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromCode(raw: String?): RewardStatus =
            entries.firstOrNull { it.code == raw } ?: PENDING
    }
}

/**
 * What earned a reward. Kept distinct so autonomous exits, pre-launch declines, and post-block
 * stops are never conflated with forced cooldowns (which never earn autonomous rewards).
 */
enum class RewardTrigger(val code: String, val isFull: Boolean, val defaultEnergy: Int) {
    ACTIVE_EXIT("ACTIVE_EXIT", isFull = true, defaultEnergy = RewardEngine.FULL_ENERGY),
    INTENTION_DECLINE("INTENTION_DECLINE", isFull = true, defaultEnergy = RewardEngine.FULL_ENERGY),
    BLOCK_STOP("BLOCK_STOP", isFull = false, defaultEnergy = RewardEngine.PARTIAL_ENERGY),
    FOCUS_COMPLETE("FOCUS_COMPLETE", isFull = true, defaultEnergy = RewardEngine.FOCUS_ENERGY),
    BEDTIME_ON_TIME("BEDTIME_ON_TIME", isFull = true, defaultEnergy = RewardEngine.BEDTIME_FULL_ENERGY),
    BEDTIME_LATE_STOP("BEDTIME_LATE_STOP", isFull = false, defaultEnergy = RewardEngine.BEDTIME_PARTIAL_ENERGY),
    MORNING_WAKE("MORNING_WAKE", isFull = true, defaultEnergy = RewardEngine.MORNING_ENERGY),
    SHOPPING_INTENTIONAL("SHOPPING_INTENTIONAL", isFull = true, defaultEnergy = RewardEngine.SHOPPING_FULL_ENERGY),
    SHOPPING_BACKLOG("SHOPPING_BACKLOG", isFull = false, defaultEnergy = RewardEngine.SHOPPING_BACKLOG_ENERGY);

    companion object {
        fun fromCode(raw: String?): RewardTrigger =
            entries.firstOrNull { it.code == raw } ?: ACTIVE_EXIT
    }
}

/** User-configurable reward & verification parameters, stored in meta as key=value pairs. */
data class RewardSettings(
    val verifyWindowMinutes: Int = DEFAULT_VERIFY_MINUTES,
    val maxFullPerGroupPerDay: Int = DEFAULT_MAX_FULL_PER_GROUP_PER_DAY,
    val requireReminderFirst: Boolean = false,
    val feedbackEnabled: Boolean = true
) {
    val verifyWindowMs: Long get() = verifyWindowMinutes * 60_000L

    companion object {
        const val DEFAULT_VERIFY_MINUTES = 15
        const val MIN_VERIFY_MINUTES = 1
        const val MAX_VERIFY_MINUTES = 60

        const val DEFAULT_MAX_FULL_PER_GROUP_PER_DAY = 1
        const val MIN_MAX_FULL_PER_GROUP_PER_DAY = 1
        const val MAX_MAX_FULL_PER_GROUP_PER_DAY = 10

        fun encode(s: RewardSettings): String = listOf(
            "verify=${s.verifyWindowMinutes.coerceIn(MIN_VERIFY_MINUTES, MAX_VERIFY_MINUTES)}",
            "cap=${s.maxFullPerGroupPerDay.coerceIn(MIN_MAX_FULL_PER_GROUP_PER_DAY, MAX_MAX_FULL_PER_GROUP_PER_DAY)}",
            "reminderFirst=${if (s.requireReminderFirst) 1 else 0}",
            "feedback=${if (s.feedbackEnabled) 1 else 0}"
        ).joinToString(";")

        fun decode(raw: String?): RewardSettings {
            if (raw.isNullOrBlank()) return RewardSettings()
            val map = HashMap<String, String>()
            for (part in raw.split(';')) {
                val eq = part.indexOf('=')
                if (eq > 0) map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            val verify = map["verify"]?.toIntOrNull()
                ?.coerceIn(MIN_VERIFY_MINUTES, MAX_VERIFY_MINUTES) ?: DEFAULT_VERIFY_MINUTES
            val cap = map["cap"]?.toIntOrNull()
                ?.coerceIn(MIN_MAX_FULL_PER_GROUP_PER_DAY, MAX_MAX_FULL_PER_GROUP_PER_DAY)
                ?: DEFAULT_MAX_FULL_PER_GROUP_PER_DAY
            val reminderFirst = map["reminderFirst"] == "1"
            val feedback = map["feedback"]?.let { it != "0" } ?: true
            return RewardSettings(
                verifyWindowMinutes = verify,
                maxFullPerGroupPerDay = cap,
                requireReminderFirst = reminderFirst,
                feedbackEnabled = feedback
            )
        }
    }
}

/** One persisted reward record (domain view, independent of Room and UI). */
data class RewardRecord(
    val id: Long = 0L,
    val dayKey: String,
    val groupKey: String,
    val packageName: String,
    val sessionId: Long,
    val trigger: RewardTrigger,
    val energy: Int,
    val isFull: Boolean,
    val status: RewardStatus,
    val createdWall: Long,
    val verifyUntilWall: Long,
    val resolvedWall: Long? = null,
    val cancelReason: String? = null
)

data class RewardRequest(
    val dayKey: String,
    val groupKey: String,
    val packageName: String,
    val sessionId: Long,
    val trigger: RewardTrigger,
    val nowWall: Long,
    val hadReminderInSession: Boolean = false
)

enum class RewardDenyReason {
    ALREADY_REWARDED_IN_SESSION,
    GROUP_DAILY_CAP_REACHED,
    CANCEL_COOLDOWN_IN_SESSION,
    REMINDER_REQUIRED_FIRST
}

sealed class RewardGrantDecision {
    data class Grant(val pending: RewardRecord) : RewardGrantDecision()
    data class Deny(val reason: RewardDenyReason) : RewardGrantDecision()
}

/**
 * Pure state machine for Mindstop Stage 4 rewards:
 * - Immediate Pending grant on autonomous exit, pre-launch decline, or post-block stop.
 * - Delayed confirmation after the verification window (default 15 minutes).
 * - Cancellation when re-entering a matching supervised app during the verification window.
 * - Anti-arbitrage caps: at most 1 active/confirmed reward per session, configurable daily full
 *   reward cap per entertainment group, fixed energy independent of watch duration, and no
 *   revocation of already confirmed rewards.
 */
object RewardEngine {
    const val FULL_ENERGY = 10
    const val PARTIAL_ENERGY = 4
    const val FOCUS_ENERGY = 15
    const val BEDTIME_FULL_ENERGY = 12
    const val BEDTIME_PARTIAL_ENERGY = 5
    const val MORNING_ENERGY = 12
    const val SHOPPING_FULL_ENERGY = 10
    const val SHOPPING_BACKLOG_ENERGY = 6
    const val MAX_CANCELLED_RETRIES_PER_SESSION = 1

    /**
     * Most specific enabled rule id covering [packageName] (APP > GROUP > GLOBAL), used to bucket
     * daily group caps and cross-app verification cancellation.
     */
    fun groupKeyFor(packageName: String, rules: List<QuotaRule>, exempt: Set<String>): String {
        val matching = matchingRules(packageName, rules, exempt)
        val picked = matching.minWithOrNull(
            compareBy<QuotaRule> { scopeRank(it.scope) }.thenBy { it.id }
        )
        return picked?.id ?: "pkg:$packageName"
    }

    /** All group keys (enabled rule ids + package fallback) associated with [packageName]. */
    fun matchingGroupKeys(packageName: String, rules: List<QuotaRule>, exempt: Set<String>): Set<String> {
        val keys = HashSet<String>()
        keys.add("pkg:$packageName")
        for (rule in matchingRules(packageName, rules, exempt)) {
            keys.add(rule.id)
        }
        return keys
    }

    private fun matchingRules(packageName: String, rules: List<QuotaRule>, exempt: Set<String>): List<QuotaRule> {
        if (packageName in exempt) return emptyList()
        return rules.filter { rule ->
            rule.enabled && packageName in RuleEngine.effectivePackages(rule, rules, exempt)
        }
    }

    private fun scopeRank(scope: RuleScope): Int = when (scope) {
        RuleScope.APP -> 0
        RuleScope.GROUP -> 1
        RuleScope.GLOBAL -> 2
    }

    /**
     * Evaluates whether [request] earns a new [RewardStatus.PENDING] reward given [existing]
     * records (at least covering the same session and the same dayKey+groupKey) and [settings].
     */
    fun evaluateGrant(
        request: RewardRequest,
        existing: List<RewardRecord>,
        settings: RewardSettings = RewardSettings()
    ): RewardGrantDecision {
        // Optional experimental gate: require at least one reminder in session before granting full reward.
        if (settings.requireReminderFirst && request.trigger.isFull && !request.hadReminderInSession) {
            return RewardGrantDecision.Deny(RewardDenyReason.REMINDER_REQUIRED_FIRST)
        }

        // Anti-arbitrage 1: at most one Pending or Confirmed reward per session.
        val sessionRewards = existing.filter { it.sessionId == request.sessionId }
        if (sessionRewards.any { it.status == RewardStatus.PENDING || it.status == RewardStatus.CONFIRMED }) {
            return RewardGrantDecision.Deny(RewardDenyReason.ALREADY_REWARDED_IN_SESSION)
        }

        // Prevent rapid open/exit/reopen farming inside one session after repeated cancellations.
        val cancelledInSession = sessionRewards.count { it.status == RewardStatus.CANCELLED }
        if (cancelledInSession > MAX_CANCELLED_RETRIES_PER_SESSION) {
            return RewardGrantDecision.Deny(RewardDenyReason.CANCEL_COOLDOWN_IN_SESSION)
        }

        // Anti-arbitrage 2: per entertainment group daily cap (Pending + Confirmed both count).
        val groupActiveToday = existing.filter {
            it.dayKey == request.dayKey &&
                it.groupKey == request.groupKey &&
                (it.status == RewardStatus.PENDING || it.status == RewardStatus.CONFIRMED)
        }
        if (request.trigger.isFull) {
            val fullCount = groupActiveToday.count { it.isFull }
            if (fullCount >= settings.maxFullPerGroupPerDay) {
                return RewardGrantDecision.Deny(RewardDenyReason.GROUP_DAILY_CAP_REACHED)
            }
        } else {
            // Partial post-block stop reward: at most 1 per group per day to avoid block-spam farming.
            val partialCount = groupActiveToday.count { !it.isFull }
            if (partialCount >= 1) {
                return RewardGrantDecision.Deny(RewardDenyReason.GROUP_DAILY_CAP_REACHED)
            }
        }

        val pending = RewardRecord(
            dayKey = request.dayKey,
            groupKey = request.groupKey,
            packageName = request.packageName,
            sessionId = request.sessionId,
            trigger = request.trigger,
            energy = request.trigger.defaultEnergy,
            isFull = request.trigger.isFull,
            status = RewardStatus.PENDING,
            createdWall = request.nowWall,
            verifyUntilWall = request.nowWall + settings.verifyWindowMs
        )
        return RewardGrantDecision.Grant(pending)
    }

    /**
     * Returns every PENDING reward whose verification window has elapsed at [nowWall].
     * Always run this BEFORE [dueToCancelOnReentry] so an elapsed pending reward is confirmed
     * instead of cancelled when the user returns after waiting out the full window.
     */
    fun dueToConfirm(pending: List<RewardRecord>, nowWall: Long): List<RewardRecord> =
        pending.filter { it.status == RewardStatus.PENDING && nowWall >= it.verifyUntilWall }

    /**
     * Returns every still-active PENDING reward that must be cancelled because the user re-entered
     * [enteredPackage] (covering [matchingGroupKeys]) before `verifyUntilWall`.
     * Already CONFIRMED rewards are never included.
     */
    fun dueToCancelOnReentry(
        pending: List<RewardRecord>,
        enteredPackage: String,
        matchingGroupKeys: Set<String>,
        nowWall: Long
    ): List<RewardRecord> = pending.filter { record ->
        record.status == RewardStatus.PENDING &&
            nowWall < record.verifyUntilWall &&
            (record.packageName == enteredPackage || record.groupKey in matchingGroupKeys)
    }
}

/**
 * Pure domain projection for the Growth World visual theme (SPEC §12).
 * Completely separated from Android Views/Canvas so future themes (forest, island, companion)
 * consume the exact same state.
 */
enum class GardenStage(val level: Int, val minEnergy: Int) {
    CLEARING(0, 0),
    SPROUTS(1, 1),
    GROVE(2, 30),
    FOREST_PATH(3, 80),
    SANCTUARY(4, 150);

    companion object {
        fun forEnergy(energy: Int): GardenStage =
            entries.lastOrNull { energy >= it.minEnergy } ?: CLEARING
    }
}

data class GardenPlant(
    val index: Int,
    /** Normalized horizontal position in [0.08, 0.92]. */
    val xRatio: Float,
    /** Normalized ground depth in [0.0, 1.0] (0 = further hill, 1 = foreground meadow). */
    val depthRatio: Float,
    /** 0 = broad leaf tree, 1 = pine/cypress, 2 = flowering shrub tree. */
    val variant: Int,
    /** Relative size multiplier in [0.82, 1.18]. */
    val scale: Float,
    val isPending: Boolean
)

data class GrowthWorldState(
    val stage: GardenStage,
    val confirmedEnergy: Int,
    val pendingEnergy: Int,
    val confirmedTrees: Int,
    val pendingSaplings: Int,
    val nextTreeProgress: Int,
    val energyPerTree: Int,
    val plants: List<GardenPlant>,
    val confirmedTodayEnergy: Int,
    val pendingTodayCount: Int,
    val activeExitsConfirmed: Int,
    val blockStopsConfirmed: Int
)

object GrowthWorldPlanner {
    const val ENERGY_PER_TREE = 10
    const val MAX_RENDERED_TREES = 28
    const val MAX_RENDERED_PENDING = 4

    fun compute(rewards: List<RewardRecord>, todayKey: String): GrowthWorldState {
        val confirmed = rewards.filter { it.status == RewardStatus.CONFIRMED }
        val pending = rewards.filter { it.status == RewardStatus.PENDING }

        val confirmedEnergy = confirmed.sumOf { it.energy }
        val pendingEnergy = pending.sumOf { it.energy }
        val confirmedTrees = confirmedEnergy / ENERGY_PER_TREE
        val nextTreeProgress = confirmedEnergy % ENERGY_PER_TREE
        val pendingSaplings = pending.size

        val confirmedTodayEnergy = confirmed.filter { it.dayKey == todayKey }.sumOf { it.energy }
        val pendingTodayCount = pending.count { it.dayKey == todayKey }
        val activeExitsConfirmed = confirmed.count {
            it.trigger == RewardTrigger.ACTIVE_EXIT || it.trigger == RewardTrigger.INTENTION_DECLINE
        }
        val blockStopsConfirmed = confirmed.count { it.trigger == RewardTrigger.BLOCK_STOP }

        val plants = buildPlants(confirmedTrees.coerceAtMost(MAX_RENDERED_TREES), pendingSaplings.coerceAtMost(MAX_RENDERED_PENDING))

        return GrowthWorldState(
            stage = GardenStage.forEnergy(confirmedEnergy),
            confirmedEnergy = confirmedEnergy,
            pendingEnergy = pendingEnergy,
            confirmedTrees = confirmedTrees,
            pendingSaplings = pendingSaplings,
            nextTreeProgress = nextTreeProgress,
            energyPerTree = ENERGY_PER_TREE,
            plants = plants,
            confirmedTodayEnergy = confirmedTodayEnergy,
            pendingTodayCount = pendingTodayCount,
            activeExitsConfirmed = activeExitsConfirmed,
            blockStopsConfirmed = blockStopsConfirmed
        )
    }

    private fun buildPlants(confirmedCount: Int, pendingCount: Int): List<GardenPlant> {
        val list = ArrayList<GardenPlant>(confirmedCount + pendingCount)
        val rng = SeededRandom(0x4D494E4453544F50L) // "MINDSTOP"
        // Place confirmed trees along gently staggered slots so they never clump awkwardly.
        for (i in 0 until confirmedCount) {
            val band = i % 7
            val baseX = 0.11f + band * 0.13f
            val jitterX = (rng.nextInt(0, 99) / 100f - 0.5f) * 0.07f
            val x = (baseX + jitterX).coerceIn(0.08f, 0.92f)
            val depth = (0.12f + (i / 7) * 0.22f + (rng.nextInt(0, 99) / 100f) * 0.14f).coerceIn(0.08f, 0.84f)
            val variant = rng.nextInt(0, 2)
            val scale = 0.84f + (rng.nextInt(0, 33) / 100f)
            list.add(GardenPlant(i, x, depth, variant, scale, isPending = false))
        }
        // Pending saplings sit cleanly in the foreground meadow so they stand out.
        for (p in 0 until pendingCount) {
            val step = 1f / (pendingCount + 1)
            val x = (step * (p + 1)).coerceIn(0.18f, 0.82f)
            list.add(
                GardenPlant(
                    index = confirmedCount + p,
                    xRatio = x,
                    depthRatio = 0.92f,
                    variant = 0,
                    scale = 0.90f,
                    isPending = true
                )
            )
        }
        return list.sortedBy { it.depthRatio }
    }
}
