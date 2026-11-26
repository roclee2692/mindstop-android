package app.mindstop.core

/**
 * Decides which progressive reminder is due, if any. Each returned id fires once per rule per
 * day (quota) or once per session (plan); the caller deduplicates.
 */
object ReminderPlanner {

    /**
     * Quota reminder for the binding rule, most severe first: one minute left, five minutes
     * left, then 10, 25, and 50 percent of the effective limit left.
     */
    fun quotaReminder(limitMs: Long, grantedMs: Long, usedMs: Long): String? {
        val total = limitMs + grantedMs
        if (total <= 0L) return null
        val remaining = total - usedMs
        if (remaining <= 0L) return null
        return when {
            remaining <= 60_000L -> "remain_1m"
            remaining <= 5 * 60_000L -> "remain_5m"
            remaining * 10 <= total -> "remain_10p"
            remaining * 4 <= total -> "remain_25p"
            remaining * 2 <= total -> "remain_50p"
            else -> null
        }
    }

    const val PLAN_END = "intent_100"
    const val MERGE_GAP_MS = 60_000L
    const val LAST_STRETCH_MS = 5 * 60_000L

    /** Fires once when the planned time left drops to [remainingMs]. */
    data class PlanNode(val id: String, val remainingMs: Long) {
        /** Within the last five minutes: time to pick a last video or check the goal. */
        val lastStretch: Boolean get() = remainingMs <= LAST_STRETCH_MS
    }

    /**
     * Reminder points for a plan of [totalMs], from minutes left and percent of the plan left.
     * Points that would fire right away (at or above the plan length) are dropped, and points less
     * than a minute apart are merged into the more urgent one. Sorted most urgent first.
     */
    fun planNodes(totalMs: Long, minutesLeft: List<Int>, percentsLeft: List<Int>): List<PlanNode> {
        if (totalMs <= 0L) return emptyList()
        val candidates = (minutesLeft.map { it * 60_000L } + percentsLeft.map { totalMs * it / 100 })
            .filter { it in 1 until totalMs }
            .sorted()
        val kept = ArrayList<Long>()
        for (remaining in candidates) {
            if (kept.none { remaining - it < MERGE_GAP_MS }) kept.add(remaining)
        }
        return kept.map { PlanNode("plan_${it / 1000}s", it) }
    }

    /**
     * The most urgent plan reminder that is due: [PLAN_END] once the plan is used up, otherwise
     * the most urgent point already passed. The caller fires each id once per session, so points
     * skipped while away never fire late.
     */
    fun planReminder(totalMs: Long, elapsedMs: Long, nodes: List<PlanNode>): String? {
        if (totalMs <= 0L) return null
        if (elapsedMs >= totalMs) return PLAN_END
        val remaining = totalMs - elapsedMs
        return nodes.firstOrNull { remaining <= it.remainingMs }?.id
    }
}
