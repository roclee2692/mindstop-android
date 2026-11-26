package app.mindstop.core

/** Why the user opens a supervised app. Study and errand sessions carry a goal and keywords. */
enum class IntentPurpose(val code: String, val defaultMinutes: Int) {
    STUDY("study", 30),
    FUN("fun", 10),
    ERRAND("errand", 5);

    val hasGoal: Boolean get() = this != FUN

    /** Errands are the low friction channel for necessary tasks, so they skip the calm wait. */
    val usesCalmWait: Boolean get() = this != ERRAND

    companion object {
        fun fromCode(code: String?): IntentPurpose? = entries.firstOrNull { it.code == code }
    }
}

object CalmWait {
    val PRESETS = listOf(0, 10, 30, 60)
    const val DEFAULT_SECONDS = 30
    const val MAX_SECONDS = 600

    fun clamp(seconds: Int): Int = seconds.coerceIn(0, MAX_SECONDS)

    /**
     * Time left in a wait that began at [startWall]. Never more than the full wait, so moving the
     * clock back cannot make it longer, and reopening the prompt keeps the original start.
     */
    fun remainingMs(startWall: Long, seconds: Int, nowWall: Long): Long {
        val full = clamp(seconds) * 1000L
        return (startWall + full - nowWall).coerceIn(0L, full)
    }
}

enum class CountdownStage { HIDDEN, GOAL, STEADY, STRONG, FINAL }

/** What the floating target and countdown show while a supervised app is in use. */
object SessionCountdown {
    const val STEADY_MS = 5 * 60_000L
    const val STRONG_MS = 60_000L
    const val FINAL_MS = 10_000L

    /**
     * The countdown follows whichever ends first: the planned time or the quota. A plan that has
     * already run out no longer counts, so the countdown moves on to the quota.
     */
    fun targetMs(planRemainingMs: Long?, quotaRemainingMs: Long?): Long? =
        listOfNotNull(planRemainingMs?.takeIf { it > 0L }, quotaRemainingMs?.takeIf { it > 0L }).minOrNull()

    fun stage(targetMs: Long?, showGoal: Boolean): CountdownStage = when {
        targetMs != null && targetMs <= FINAL_MS -> CountdownStage.FINAL
        targetMs != null && targetMs <= STRONG_MS -> CountdownStage.STRONG
        targetMs != null && targetMs <= STEADY_MS -> CountdownStage.STEADY
        showGoal -> CountdownStage.GOAL
        else -> CountdownStage.HIDDEN
    }
}

enum class OverlayPosition { TOP, MIDDLE, BOTTOM }
enum class OverlaySize { SMALL, MEDIUM, LARGE }
enum class OverlayIntensity { LIGHT, NORMAL, STRONG }

data class IntentionSettings(
    val waitSeconds: Int = CalmWait.DEFAULT_SECONDS,
    val nodeMinutes: List<Int> = DEFAULT_NODE_MINUTES,
    val nodePercents: List<Int> = DEFAULT_NODE_PERCENTS,
    val overlayEnabled: Boolean = true,
    val position: OverlayPosition = OverlayPosition.TOP,
    val size: OverlaySize = OverlaySize.MEDIUM,
    val intensity: OverlayIntensity = OverlayIntensity.NORMAL
) {
    fun encode(): String = listOf(
        "wait=$waitSeconds",
        "nodes=${formatNodes(nodeMinutes, nodePercents)}",
        "overlay=${if (overlayEnabled) 1 else 0}",
        "position=${position.name}",
        "size=${size.name}",
        "intensity=${intensity.name}"
    ).joinToString(";")

    companion object {
        val DEFAULT_NODE_MINUTES = listOf(15, 10, 5)
        val DEFAULT_NODE_PERCENTS = listOf(50)
        val NODE_MINUTES_RANGE = 1..180
        val NODE_PERCENT_RANGE = 1..99
        val PLAN_MINUTES_RANGE = 1..180

        fun decode(text: String?): IntentionSettings {
            val defaults = IntentionSettings()
            if (text.isNullOrBlank()) return defaults
            val map = text.split(';').mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
            val nodes = map["nodes"]?.let { parseNodes(it) }
            return IntentionSettings(
                waitSeconds = map["wait"]?.toIntOrNull()?.let { CalmWait.clamp(it) } ?: defaults.waitSeconds,
                nodeMinutes = nodes?.first ?: defaults.nodeMinutes,
                nodePercents = nodes?.second ?: defaults.nodePercents,
                overlayEnabled = map["overlay"]?.let { it == "1" } ?: defaults.overlayEnabled,
                position = enumOrNull<OverlayPosition>(map["position"]) ?: defaults.position,
                size = enumOrNull<OverlaySize>(map["size"]) ?: defaults.size,
                intensity = enumOrNull<OverlayIntensity>(map["intensity"]) ?: defaults.intensity
            )
        }

        /**
         * Parses reminder points such as "15, 10, 5, 50%": plain numbers are minutes left, numbers
         * with a percent sign are the share of the plan left. Returns null when any part is invalid.
         * An empty text means no extra points.
         */
        fun parseNodes(text: String): Pair<List<Int>, List<Int>>? {
            val minutes = ArrayList<Int>()
            val percents = ArrayList<Int>()
            for (raw in text.split(',', '，', ' ')) {
                val part = raw.trim()
                if (part.isEmpty()) continue
                if (part.endsWith("%")) {
                    val value = part.dropLast(1).trim().toIntOrNull() ?: return null
                    if (value !in NODE_PERCENT_RANGE) return null
                    percents.add(value)
                } else {
                    val value = part.toIntOrNull() ?: return null
                    if (value !in NODE_MINUTES_RANGE) return null
                    minutes.add(value)
                }
            }
            return minutes.distinct().sortedDescending() to percents.distinct().sortedDescending()
        }

        fun formatNodes(minutes: List<Int>, percents: List<Int>): String =
            (minutes.map { it.toString() } + percents.map { "$it%" }).joinToString(",")

        private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}

enum class IntentStatus { ACTIVE, DONE, ENDED }

/**
 * The intention of one session. [baseUsedMs] is how much supervised time the session had already
 * used when the plan started, so a plan made later in the same session starts from zero.
 */
data class SessionIntent(
    val purpose: IntentPurpose,
    val goal: String,
    val keywords: String,
    val minutes: Int?,
    val baseUsedMs: Long,
    val status: IntentStatus,
    val wall: Long
) {
    val planMs: Long? get() = minutes?.let { it * 60_000L }

    fun planElapsedMs(sessionUsedMs: Long): Long = (sessionUsedMs - baseUsedMs).coerceAtLeast(0L)

    fun planRemainingMs(sessionUsedMs: Long): Long? = planMs?.let { it - planElapsedMs(sessionUsedMs) }

    /** Short text for the floating pill. */
    fun goalSummary(maxChars: Int = 16): String =
        if (goal.length <= maxChars) goal else goal.take(maxChars).trimEnd() + "…"
}

enum class PromptMode { NEW, EXTEND }

object IntentionGate {
    /**
     * Whether the user must answer the intention prompt before using a supervised app: there is
     * no active intention in this session yet ([PromptMode.NEW]), or its planned time is used up
     * ([PromptMode.EXTEND]). Ending a session keeps the session, so the next plan still counts
     * against the same session limit.
     */
    fun promptNeeded(intent: SessionIntent?, sessionUsedMs: Long): PromptMode? {
        if (intent == null || intent.status != IntentStatus.ACTIVE) return PromptMode.NEW
        val remaining = intent.planRemainingMs(sessionUsedMs) ?: return null
        return if (remaining <= 0L) PromptMode.EXTEND else null
    }
}
