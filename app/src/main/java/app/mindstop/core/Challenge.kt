package app.mindstop.core

enum class WrongAnswerPolicy {
    /** A wrong answer simply moves on to the next question. */
    NEXT,
    /** A wrong answer adds one more required question, up to twice the original count. */
    ADD_ONE,
    /** A wrong answer resets the correct count to zero. */
    RESET
}

data class RenewalTier(val questions: Int, val minutes: Int)

/**
 * How the block screen's challenge works. Renewal n of a day uses tiers[n], and the last tier
 * repeats when [maxRenewals] is larger than the tier list.
 */
data class ChallengeConfig(
    val tiers: List<RenewalTier> = DEFAULT_TIERS,
    val maxRenewals: Int = 3,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val types: Set<QuestionType> = setOf(QuestionType.MATH, QuestionType.WORD, QuestionType.SYNONYM),
    val wrongPolicy: WrongAnswerPolicy = WrongAnswerPolicy.NEXT,
    val cooldownMinutes: Int = 60
) {

    fun tierFor(renewalIndex: Int): RenewalTier? {
        if (renewalIndex < 0 || renewalIndex >= maxRenewals || tiers.isEmpty()) return null
        return tiers[minOf(renewalIndex, tiers.lastIndex)]
    }

    fun encode(): String = listOf(
        "tiers=" + tiers.joinToString(",") { "${it.questions}x${it.minutes}" },
        "max=$maxRenewals",
        "difficulty=${difficulty.level}",
        "types=" + types.sortedBy { it.ordinal }.joinToString(",") { it.code },
        "wrong=${wrongPolicy.name}",
        "cooldown=$cooldownMinutes"
    ).joinToString(";")

    companion object {
        val DEFAULT_TIERS = listOf(RenewalTier(5, 10), RenewalTier(10, 10), RenewalTier(20, 5))
        val QUESTIONS_RANGE = 1..100
        val MINUTES_RANGE = 1..120
        val RENEWALS_RANGE = 0..20
        val COOLDOWN_RANGE = 1..720

        /** Tolerant decoding: anything missing or broken falls back to the default. */
        fun decode(text: String?): ChallengeConfig {
            val defaults = ChallengeConfig()
            if (text.isNullOrBlank()) return defaults
            val map = text.split(';').mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
            val tiers = map["tiers"]?.split(',')?.mapNotNull { part ->
                val nums = part.split('x')
                val q = nums.getOrNull(0)?.toIntOrNull()
                val m = nums.getOrNull(1)?.toIntOrNull()
                if (q != null && m != null && q in QUESTIONS_RANGE && m in MINUTES_RANGE) RenewalTier(q, m) else null
            }?.takeIf { it.isNotEmpty() } ?: defaults.tiers
            val types = map["types"]?.split(',')?.mapNotNull { QuestionType.fromCode(it) }?.toSet()
                ?.takeIf { it.isNotEmpty() } ?: defaults.types
            return ChallengeConfig(
                tiers = tiers,
                maxRenewals = map["max"]?.toIntOrNull()?.takeIf { it in RENEWALS_RANGE } ?: defaults.maxRenewals,
                difficulty = map["difficulty"]?.toIntOrNull()?.let { Difficulty.of(it) } ?: defaults.difficulty,
                types = types,
                wrongPolicy = map["wrong"]?.let { name -> WrongAnswerPolicy.entries.firstOrNull { it.name == name } }
                    ?: defaults.wrongPolicy,
                cooldownMinutes = map["cooldown"]?.toIntOrNull()?.takeIf { it in COOLDOWN_RANGE } ?: defaults.cooldownMinutes
            )
        }
    }
}

/** Saved after every answer. [position] is the index of the question now on screen. */
data class ChallengeProgress(
    val required: Int,
    val correct: Int,
    val wrong: Int,
    val position: Int
) {
    val passed: Boolean get() = correct >= required
}

sealed class PresentedQuestion {
    abstract val type: QuestionType

    data class Math(val question: MathQuestion) : PresentedQuestion() {
        override val type get() = QuestionType.MATH
    }

    /** [options] are already shuffled; [correctOption] points into them. */
    data class Choice(
        val source: ChoiceQuestion,
        val options: List<String>,
        val correctOption: Int
    ) : PresentedQuestion() {
        override val type get() = source.type
    }
}

/**
 * Pure challenge logic. A challenge is its seed, its config snapshot, its bank snapshot and its
 * progress. Everything on screen is derived from those, so leaving and coming back shows the very
 * same question instead of a fresh, easier one.
 */
object ChallengeEngine {

    private const val SLOT_STREAM = 0x534C4F54L
    private const val OPTION_STREAM = 0x4F505453L
    private const val SNAPSHOT_STREAM = 0x534E4150L
    const val SNAPSHOT_CAP_PER_TYPE = 300

    fun start(required: Int): ChallengeProgress = ChallengeProgress(required, 0, 0, 0)

    fun submit(
        progress: ChallengeProgress,
        isCorrect: Boolean,
        policy: WrongAnswerPolicy,
        baseRequired: Int
    ): ChallengeProgress {
        if (progress.passed) return progress
        val next = progress.position + 1
        if (isCorrect) return progress.copy(correct = progress.correct + 1, position = next)
        return when (policy) {
            WrongAnswerPolicy.NEXT -> progress.copy(wrong = progress.wrong + 1, position = next)
            WrongAnswerPolicy.ADD_ONE -> progress.copy(
                required = minOf(progress.required + 1, baseRequired * 2),
                wrong = progress.wrong + 1,
                position = next
            )
            WrongAnswerPolicy.RESET -> progress.copy(correct = 0, wrong = progress.wrong + 1, position = next)
        }
    }

    /** Bank question ids the challenge may use, shuffled by the seed and fixed at start. */
    fun snapshot(seed: Long, bank: List<ChoiceQuestion>, types: Set<QuestionType>): List<Long> =
        bank.filter { it.type in types }
            .sortedBy { it.id }
            .groupBy { it.type }
            .toSortedMap(compareBy { it.ordinal })
            .flatMap { (type, questions) ->
                SeededRandom.derive(seed, SNAPSHOT_STREAM, type.ordinal.toLong())
                    .shuffled(questions.map { it.id })
                    .take(SNAPSHOT_CAP_PER_TYPE)
            }

    fun questionAt(
        seed: Long,
        position: Int,
        types: Set<QuestionType>,
        difficulty: Difficulty,
        snapshot: List<Long>,
        bank: Map<Long, ChoiceQuestion>
    ): PresentedQuestion {
        val byType = snapshot.mapNotNull { bank[it] }.groupBy { it.type }
        val available = types
            .filter { it == QuestionType.MATH || byType[it].orEmpty().isNotEmpty() }
            .sortedBy { it.ordinal }
            .ifEmpty { listOf(QuestionType.MATH) }
        val type = slotType(seed, position, available)
        if (type == QuestionType.MATH) {
            return PresentedQuestion.Math(MathGenerator.generate(seed, position, difficulty))
        }
        val pool = byType.getValue(type)
        val occurrence = (0 until position).count { slotType(seed, it, available) == type }
        val source = pool[occurrence % pool.size]
        val order = SeededRandom.derive(seed, OPTION_STREAM, position.toLong()).shuffled(source.options.indices.toList())
        return PresentedQuestion.Choice(
            source = source,
            options = order.map { source.options[it] },
            correctOption = order.indexOf(source.answerIndex)
        )
    }

    fun isCorrect(question: PresentedQuestion, mathInput: String?, chosenOption: Int?): Boolean = when (question) {
        is PresentedQuestion.Math -> mathInput?.let { MathGenerator.parseAnswer(it) } == question.question.answer
        is PresentedQuestion.Choice -> chosenOption == question.correctOption
    }

    private fun slotType(seed: Long, position: Int, available: List<QuestionType>): QuestionType =
        if (available.size == 1) available[0]
        else SeededRandom.derive(seed, SLOT_STREAM, position.toLong()).pick(available)

    fun encodeIds(ids: List<Long>): String = ids.joinToString(",")

    fun decodeIds(text: String): List<Long> = text.split(',').mapNotNull { it.trim().toLongOrNull() }

    fun encodeTypes(types: Set<QuestionType>): String = types.sortedBy { it.ordinal }.joinToString(",") { it.code }

    fun decodeTypes(text: String): Set<QuestionType> =
        text.split(',').mapNotNull { QuestionType.fromCode(it) }.toSet().ifEmpty { setOf(QuestionType.MATH) }
}
