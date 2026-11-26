package app.mindstop.core

/**
 * Lightweight Reflection domain model (SPEC §14).
 * Non-judgmental self-report after study sessions or noticeable entertainment overtime.
 * Always skippable; never runs automated psychological diagnosis.
 */
enum class ReflectionOutcome(val code: String) {
    COMPLETED("COMPLETED"),
    PARTIAL("PARTIAL"),
    NOT_COMPLETED("NOT_COMPLETED"),
    SWITCHED("SWITCHED");

    companion object {
        fun fromCode(raw: String?): ReflectionOutcome =
            entries.firstOrNull { it.code == raw } ?: COMPLETED
    }
}

enum class ReflectionReason(val code: String) {
    RECOMMENDATIONS("RECOMMENDATIONS"),
    COMMENTS("COMMENTS"),
    FORGOT_GOAL("FORGOT_GOAL"),
    FATIGUE("FATIGUE"),
    WANT_REST("WANT_REST"),
    OTHER("OTHER");

    companion object {
        fun decodeList(raw: String?): Set<ReflectionReason> =
            raw.orEmpty().split(',').mapNotNull { code ->
                entries.firstOrNull { it.code == code.trim() }
            }.toSet()

        fun encodeList(reasons: Set<ReflectionReason>): String =
            reasons.joinToString(",") { it.code }
    }
}

data class ReflectionRecord(
    val id: Long = 0L,
    val dayKey: String,
    val sessionId: Long,
    val packageName: String,
    val purpose: IntentPurpose,
    val goal: String,
    val plannedMinutes: Int?,
    val actualMs: Long,
    val outcome: ReflectionOutcome,
    val reasons: Set<ReflectionReason>,
    val createdWall: Long
)
