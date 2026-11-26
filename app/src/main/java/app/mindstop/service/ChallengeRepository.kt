package app.mindstop.service

import android.content.Context
import androidx.room.withTransaction
import app.mindstop.core.ChallengeConfig
import app.mindstop.core.ChallengeEngine
import app.mindstop.core.ChallengeProgress
import app.mindstop.core.ChoiceQuestion
import app.mindstop.core.CsvImportResult
import app.mindstop.core.CsvQuestionParser
import app.mindstop.core.ParsedQuestion
import app.mindstop.core.QuestionType
import app.mindstop.core.QuotaGrant
import app.mindstop.core.RenewalTier
import app.mindstop.data.BankQuestionEntity
import app.mindstop.data.ChallengeEntity
import app.mindstop.data.MetaEntity
import app.mindstop.data.MindstopDatabase
import java.security.SecureRandom

/** Question bank, challenge settings and challenge records in mindstop_db. */
class ChallengeRepository(private val context: Context) {

    private val db = MindstopDatabase.getInstance(context)
    private val quota = QuotaRepository(context)

    data class ImportOutcome(val result: CsvImportResult, val inserted: Int)

    /** What the block screen can offer for one exhausted rule today. */
    data class Offer(val tier: RenewalTier?, val renewalIndex: Int, val active: ChallengeEntity?)

    suspend fun loadConfig(): ChallengeConfig = ChallengeConfig.decode(db.metaDao().get(KEY_CONFIG))

    suspend fun saveConfig(config: ChallengeConfig, nowWall: Long) {
        db.metaDao().put(MetaEntity(KEY_CONFIG, config.encode()))
        quota.logEvent("challenge_config_changed", config.encode(), nowWall)
    }

    // Question bank

    /** Loads the bundled English questions once. Deleted built in questions stay deleted. */
    suspend fun ensureBuiltinBank(nowWall: Long) {
        if (db.metaDao().get(KEY_BUILTIN_VERSION) == BUILTIN_VERSION) return
        val bytes = context.assets.open(BUILTIN_ASSET).use { it.readBytes() }
        db.withTransaction {
            if (db.metaDao().get(KEY_BUILTIN_VERSION) == BUILTIN_VERSION) return@withTransaction
            val existing = db.bankQuestionDao().allKeys().toSet()
            val result = CsvQuestionParser.parseBytes(bytes, existing)
            db.bankQuestionDao().insertAll(result.questions.map { it.toEntity(SOURCE_BUILTIN, nowWall) })
            db.metaDao().put(MetaEntity(KEY_BUILTIN_VERSION, BUILTIN_VERSION))
        }
    }

    suspend fun importCsv(bytes: ByteArray, nowWall: Long): ImportOutcome = db.withTransaction {
        val existing = db.bankQuestionDao().allKeys().toSet()
        val result = CsvQuestionParser.parseBytes(bytes, existing)
        val ids = db.bankQuestionDao().insertAll(result.questions.map { it.toEntity(SOURCE_CSV, nowWall) })
        val inserted = ids.count { it != -1L }
        quota.logEvent("bank_imported", "ok=$inserted errors=${result.errors.size}", nowWall)
        ImportOutcome(result, inserted)
    }

    fun observeBank() = db.bankQuestionDao().observeAll()

    suspend fun setQuestionEnabled(id: Long, enabled: Boolean) = db.bankQuestionDao().setEnabled(id, enabled)

    suspend fun deleteQuestion(id: Long) = db.bankQuestionDao().delete(id)

    // Challenges

    suspend fun offerFor(dayKey: String, ruleId: String): Offer {
        val active = db.challengeDao().active(dayKey, ruleId)
        val passed = db.challengeDao().passedCount(dayKey, ruleId)
        val tier = active?.let { RenewalTier(it.baseRequired, it.grantMinutes) } ?: loadConfig().tierFor(passed)
        return Offer(tier, active?.renewalIndex ?: passed, active)
    }

    /**
     * Returns today's open challenge for the rule, or starts the next one. Null when today's
     * renewals are used up.
     */
    suspend fun startOrResume(dayKey: String, ruleId: String, packageName: String, nowWall: Long): ChallengeEntity? {
        ensureBuiltinBank(nowWall)
        return db.withTransaction {
            db.challengeDao().active(dayKey, ruleId)?.let { return@withTransaction it }
            val config = loadConfig()
            val renewalIndex = db.challengeDao().passedCount(dayKey, ruleId)
            val tier = config.tierFor(renewalIndex) ?: return@withTransaction null
            val seed = SecureRandom().nextLong()
            val bank = db.bankQuestionDao().enabled().mapNotNull { it.toChoice() }
            val snapshot = ChallengeEngine.snapshot(seed, bank, config.types)
            val entity = ChallengeEntity(
                dayKey = dayKey,
                ruleId = ruleId,
                packageName = packageName,
                renewalIndex = renewalIndex,
                seed = seed,
                baseRequired = tier.questions,
                required = tier.questions,
                correct = 0,
                wrong = 0,
                position = 0,
                grantMinutes = tier.minutes,
                difficulty = config.difficulty.level,
                types = ChallengeEngine.encodeTypes(config.types),
                wrongPolicy = config.wrongPolicy.name,
                bankIds = ChallengeEngine.encodeIds(snapshot),
                status = STATUS_ACTIVE,
                createdWall = nowWall,
                updatedWall = nowWall
            )
            val id = db.challengeDao().insert(entity)
            quota.logEvent("challenge_started", "$packageName rule=$ruleId n=${tier.questions} +${tier.minutes}min", nowWall)
            entity.copy(id = id)
        }
    }

    suspend fun bankFor(challenge: ChallengeEntity): Map<Long, ChoiceQuestion> =
        ChallengeEngine.decodeIds(challenge.bankIds)
            .chunked(500)
            .flatMap { db.bankQuestionDao().byIds(it) }
            .mapNotNull { it.toChoice() }
            .associateBy { it.id }

    suspend fun saveProgress(id: Long, progress: ChallengeProgress, nowWall: Long) {
        db.challengeDao().saveProgress(id, progress.required, progress.correct, progress.wrong, progress.position, nowWall)
    }

    /**
     * Closes a passed challenge and stores its grants in one transaction, so a crash cannot pay
     * out twice. With no grants (nothing is exhausted any more) the challenge is voided and does
     * not use up a renewal. Returns false when the challenge was already closed.
     */
    suspend fun completeChallenge(challenge: ChallengeEntity, grants: List<QuotaGrant>, nowWall: Long): Boolean =
        db.withTransaction {
            val status = if (grants.isEmpty()) STATUS_VOID else STATUS_PASSED
            if (db.challengeDao().finish(challenge.id, status, nowWall) == 0) return@withTransaction false
            if (grants.isNotEmpty()) quota.addGrants(grants, "challenge", nowWall)
            quota.logEvent(
                if (grants.isEmpty()) "challenge_void" else "challenge_passed",
                "${challenge.packageName} rule=${challenge.ruleId} +${challenge.grantMinutes}min wrong=${challenge.wrong}",
                nowWall
            )
            true
        }

    companion object {
        const val KEY_CONFIG = "challenge_config"
        const val KEY_BUILTIN_VERSION = "builtin_bank_version"
        const val BUILTIN_VERSION = "1"
        const val BUILTIN_ASSET = "mindstop/builtin_english.csv"
        const val SOURCE_BUILTIN = "builtin"
        const val SOURCE_CSV = "csv"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_PASSED = "PASSED"
        const val STATUS_VOID = "VOID"
    }
}

fun ParsedQuestion.toEntity(source: String, nowWall: Long) = BankQuestionEntity(
    type = type.code,
    question = question,
    questionKey = key,
    optionA = options[0],
    optionB = options[1],
    optionC = options[2],
    optionD = options[3],
    answer = answerIndex,
    explanation = explanation,
    difficulty = difficulty,
    enabled = true,
    source = source,
    createdWall = nowWall
)

fun BankQuestionEntity.toChoice(): ChoiceQuestion? {
    val questionType = QuestionType.fromCode(type) ?: return null
    if (answer !in 0..3) return null
    return ChoiceQuestion(
        id = id,
        type = questionType,
        question = question,
        options = listOf(optionA, optionB, optionC, optionD),
        answerIndex = answer,
        explanation = explanation,
        difficulty = difficulty
    )
}
