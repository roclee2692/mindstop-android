package app.mindstop.core

/**
 * Pure Kotlin state machine for Elastic Focus Mode (SPEC §15):
 * custom focus duration, learning tool whitelist, and cumulative temporary interrupt budget.
 * Real pauses pause the focus timer; progressive interrupt reminders warn at 50% / 80% / 100%;
 * pausing/resuming repeatedly can never farm rewards; only genuinely completing the focus target
 * earns a growth reward.
 */
enum class FocusStatus(val code: String) {
    IDLE("IDLE"),
    FOCUSING("FOCUSING"),
    PAUSED("PAUSED"),
    COMPLETED("COMPLETED"),
    ABANDONED("ABANDONED");

    companion object {
        fun fromCode(raw: String?): FocusStatus =
            entries.firstOrNull { it.code == raw } ?: IDLE
    }
}

data class FocusConfig(
    val goal: String = "",
    val targetFocusMinutes: Int = DEFAULT_FOCUS_MINUTES,
    val interruptBudgetMinutes: Int = DEFAULT_INTERRUPT_MINUTES,
    val whitelistPackages: Set<String> = emptySet()
) {
    val targetFocusMs: Long get() = targetFocusMinutes.coerceIn(MIN_FOCUS_MINUTES, MAX_FOCUS_MINUTES) * 60_000L
    val interruptBudgetMs: Long get() = interruptBudgetMinutes.coerceIn(0, MAX_INTERRUPT_MINUTES) * 60_000L

    companion object {
        const val DEFAULT_FOCUS_MINUTES = 60
        const val MIN_FOCUS_MINUTES = 5
        const val MAX_FOCUS_MINUTES = 180
        const val DEFAULT_INTERRUPT_MINUTES = 15
        const val MAX_INTERRUPT_MINUTES = 60
    }
}

data class ActiveFocusState(
    val sessionId: Long,
    val dayKey: String,
    val goal: String,
    val targetFocusMs: Long,
    val interruptBudgetMs: Long,
    val whitelistPackages: Set<String>,
    val status: FocusStatus,
    val focusedMs: Long,
    val interruptMs: Long,
    val interruptCount: Int,
    val earlyReturnPraised: Boolean,
    val startedWall: Long,
    val lastTickWall: Long
) {
    val focusRemainingMs: Long get() = (targetFocusMs - focusedMs).coerceAtLeast(0L)
    val interruptRemainingMs: Long get() = (interruptBudgetMs - interruptMs).coerceAtLeast(0L)
    val isActive: Boolean get() = status == FocusStatus.FOCUSING || status == FocusStatus.PAUSED
    val interruptExceeded: Boolean get() = interruptBudgetMs >= 0L && interruptMs >= interruptBudgetMs
}

enum class FocusInterruptNode(val code: String) {
    HALF("interrupt_50"),
    EIGHTY("interrupt_80"),
    EXHAUSTED("interrupt_100")
}

data class FocusTickResult(
    val state: ActiveFocusState,
    val justCompleted: Boolean = false,
    val interruptNode: FocusInterruptNode? = null
)

data class FocusResumeResult(
    val state: ActiveFocusState,
    val showEarlyReturnPraise: Boolean
)

object FocusEngine {

    fun start(config: FocusConfig, nowWall: Long, dayKey: String): ActiveFocusState =
        ActiveFocusState(
            sessionId = nowWall,
            dayKey = dayKey,
            goal = config.goal.trim(),
            targetFocusMs = config.targetFocusMs,
            interruptBudgetMs = config.interruptBudgetMs,
            whitelistPackages = config.whitelistPackages,
            status = FocusStatus.FOCUSING,
            focusedMs = 0L,
            interruptMs = 0L,
            interruptCount = 0,
            earlyReturnPraised = false,
            startedWall = nowWall,
            lastTickWall = nowWall
        )

    /** Pauses the focus clock and begins counting temporary interrupt budget. */
    fun pauseForInterrupt(state: ActiveFocusState, nowWall: Long): ActiveFocusState {
        if (state.status != FocusStatus.FOCUSING) return state
        val delta = (nowWall - state.lastTickWall).coerceAtLeast(0L)
        return state.copy(
            status = FocusStatus.PAUSED,
            focusedMs = (state.focusedMs + delta).coerceAtMost(state.targetFocusMs),
            interruptCount = state.interruptCount + 1,
            lastTickWall = nowWall
        )
    }

    /**
     * Resumes focus from a pause. Grants at most one positive early-return feedback message per
     * focus session (only if returned before interrupt budget ran out), preventing pause/resume farming.
     */
    fun resumeFromInterrupt(state: ActiveFocusState, nowWall: Long): FocusResumeResult {
        if (state.status != FocusStatus.PAUSED) return FocusResumeResult(state, false)
        val delta = (nowWall - state.lastTickWall).coerceAtLeast(0L)
        val newInterruptMs = state.interruptMs + delta
        val returnedEarly = state.interruptBudgetMs > 0L && newInterruptMs < state.interruptBudgetMs
        val praise = returnedEarly && !state.earlyReturnPraised
        val next = state.copy(
            status = FocusStatus.FOCUSING,
            interruptMs = newInterruptMs,
            earlyReturnPraised = state.earlyReturnPraised || praise,
            lastTickWall = nowWall
        )
        return FocusResumeResult(next, praise)
    }

    /** Advances either focus time or interrupt time by the trusted wall delta since last tick. */
    fun tick(state: ActiveFocusState, nowWall: Long): FocusTickResult {
        if (!state.isActive) return FocusTickResult(state)
        val delta = (nowWall - state.lastTickWall).coerceAtLeast(0L)
        return when (state.status) {
            FocusStatus.FOCUSING -> {
                val newFocused = state.focusedMs + delta
                if (newFocused >= state.targetFocusMs) {
                    FocusTickResult(
                        state = state.copy(
                            status = FocusStatus.COMPLETED,
                            focusedMs = state.targetFocusMs,
                            lastTickWall = nowWall
                        ),
                        justCompleted = true
                    )
                } else {
                    FocusTickResult(
                        state = state.copy(focusedMs = newFocused, lastTickWall = nowWall)
                    )
                }
            }
            FocusStatus.PAUSED -> {
                val prevInterrupt = state.interruptMs
                val newInterrupt = prevInterrupt + delta
                val node = interruptNodeCrossed(state.interruptBudgetMs, prevInterrupt, newInterrupt)
                FocusTickResult(
                    state = state.copy(interruptMs = newInterrupt, lastTickWall = nowWall),
                    interruptNode = node
                )
            }
            else -> FocusTickResult(state)
        }
    }

    fun abandon(state: ActiveFocusState, nowWall: Long): ActiveFocusState {
        if (!state.isActive) return state
        val ticked = tick(state, nowWall).state
        if (ticked.status == FocusStatus.COMPLETED) return ticked
        return ticked.copy(status = FocusStatus.ABANDONED, lastTickWall = nowWall)
    }

    /** Determines whether entering [packageName] while focusing counts as an allowed whitelisted tool. */
    fun isAllowedDuringFocus(
        state: ActiveFocusState,
        packageName: String,
        exemptPackages: Set<String>,
        ownPackage: String
    ): Boolean = packageName == ownPackage || packageName in exemptPackages || packageName in state.whitelistPackages

    private fun interruptNodeCrossed(budgetMs: Long, prevMs: Long, nowMs: Long): FocusInterruptNode? {
        if (budgetMs <= 0L) return if (nowMs > 0L && prevMs == 0L) FocusInterruptNode.EXHAUSTED else null
        return when {
            nowMs >= budgetMs && prevMs < budgetMs -> FocusInterruptNode.EXHAUSTED
            nowMs * 100 >= budgetMs * 80 && prevMs * 100 < budgetMs * 80 -> FocusInterruptNode.EIGHTY
            nowMs * 100 >= budgetMs * 50 && prevMs * 100 < budgetMs * 50 -> FocusInterruptNode.HALF
            else -> null
        }
    }

    /** Lightweight R8-safe persistence for crash/reboot recovery in meta table. */
    fun encode(state: ActiveFocusState): String = listOf(
        "sid=${state.sessionId}",
        "day=${state.dayKey}",
        "goal=${state.goal.replace(';', ' ').replace('=', ' ')}",
        "target=${state.targetFocusMs}",
        "budget=${state.interruptBudgetMs}",
        "wl=${state.whitelistPackages.joinToString(",")}",
        "st=${state.status.code}",
        "fms=${state.focusedMs}",
        "ims=${state.interruptMs}",
        "icnt=${state.interruptCount}",
        "praised=${if (state.earlyReturnPraised) 1 else 0}",
        "start=${state.startedWall}",
        "last=${state.lastTickWall}"
    ).joinToString(";")

    fun decode(raw: String?): ActiveFocusState? {
        if (raw.isNullOrBlank()) return null
        val map = HashMap<String, String>()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) map[part.substring(0, eq)] = part.substring(eq + 1)
        }
        val sid = map["sid"]?.toLongOrNull() ?: return null
        val day = map["day"]?.takeIf { it.isNotBlank() } ?: return null
        val status = FocusStatus.fromCode(map["st"])
        if (status != FocusStatus.FOCUSING && status != FocusStatus.PAUSED) return null
        return ActiveFocusState(
            sessionId = sid,
            dayKey = day,
            goal = map["goal"].orEmpty(),
            targetFocusMs = map["target"]?.toLongOrNull() ?: (FocusConfig.DEFAULT_FOCUS_MINUTES * 60_000L),
            interruptBudgetMs = map["budget"]?.toLongOrNull() ?: (FocusConfig.DEFAULT_INTERRUPT_MINUTES * 60_000L),
            whitelistPackages = map["wl"].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
            status = status,
            focusedMs = map["fms"]?.toLongOrNull() ?: 0L,
            interruptMs = map["ims"]?.toLongOrNull() ?: 0L,
            interruptCount = map["icnt"]?.toIntOrNull() ?: 0,
            earlyReturnPraised = map["praised"] == "1",
            startedWall = map["start"]?.toLongOrNull() ?: sid,
            lastTickWall = map["last"]?.toLongOrNull() ?: sid
        )
    }
}
