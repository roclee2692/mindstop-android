package app.mindstop.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.ChallengeEngine
import app.mindstop.core.ChallengeProgress
import app.mindstop.core.ChoiceQuestion
import app.mindstop.core.DayKeys
import app.mindstop.core.Difficulty
import app.mindstop.core.MathGenerator
import app.mindstop.core.PresentedQuestion
import app.mindstop.core.QuestionType
import app.mindstop.core.RuleEngine
import app.mindstop.core.WrongAnswerPolicy
import app.mindstop.data.ChallengeEntity
import app.mindstop.service.ChallengeRepository
import app.mindstop.service.QuotaRepository
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopChallengeBinding

/**
 * Answer questions to renew a blocked app. Progress is saved before the result is shown, so
 * leaving at any point and coming back continues with the same next question.
 */
class MindstopChallengeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "app.mindstop.extra.CHALLENGE_PACKAGE"
        const val EXTRA_RULE_ID = "app.mindstop.extra.CHALLENGE_RULE_ID"
        private val LETTERS = listOf("A", "B", "C", "D")
    }

    private lateinit var binding: ActivityMindstopChallengeBinding
    private lateinit var repo: QuotaRepository
    private lateinit var challenges: ChallengeRepository
    private lateinit var optionButtons: List<MaterialButton>

    private var targetPackage = ""
    private var ruleId = ""
    private var challenge: ChallengeEntity? = null
    private var bank: Map<Long, ChoiceQuestion> = emptyMap()
    private var progress: ChallengeProgress? = null
    private var current: PresentedQuestion? = null
    private var answered = false
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopChallengeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.challengeRoot)
        repo = QuotaRepository(this)
        challenges = ChallengeRepository(this)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        ruleId = intent.getStringExtra(EXTRA_RULE_ID).orEmpty()

        optionButtons = listOf(binding.challengeOption0, binding.challengeOption1, binding.challengeOption2, binding.challengeOption3)
        optionButtons.forEachIndexed { index, button -> button.setOnClickListener { answerChoice(index) } }
        binding.challengeSubmit.setOnClickListener { answerMath() }
        binding.challengeAnswer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                answerMath()
                true
            } else {
                false
            }
        }
        binding.challengeNext.setOnClickListener { showCurrent() }
        binding.challengeLeave.setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        if (ruleId.isEmpty()) {
            finish()
            return
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) { repo.approxTrustedNow() }
            val entity = withContext(Dispatchers.IO) {
                challenges.startOrResume(DayKeys.dayKey(now), ruleId, targetPackage, now)
            }
            if (entity == null) {
                Toast.makeText(this@MindstopChallengeActivity, R.string.mindstop_block_challenge_used_up, Toast.LENGTH_LONG).show()
                goHome()
                return@launch
            }
            challenge = entity
            bank = withContext(Dispatchers.IO) { challenges.bankFor(entity) }
            val restored = ChallengeProgress(entity.required, entity.correct, entity.wrong, entity.position)
            progress = restored
            binding.challengeTitle.text = getString(R.string.mindstop_challenge_title, entity.renewalIndex + 1)
            if (restored.passed) {
                renderProgress()
                finishPassed()
            } else {
                showCurrent()
            }
        }
    }

    private fun renderProgress() {
        val c = challenge ?: return
        val p = progress ?: return
        binding.challengeProgress.text = getString(R.string.mindstop_challenge_progress, p.correct, p.required, p.wrong) +
            "\n" + getString(R.string.mindstop_challenge_reward, c.grantMinutes)
        binding.challengeProgressBar.max = p.required
        binding.challengeProgressBar.setProgressCompat(p.correct.coerceAtMost(p.required), true)
    }

    private fun showCurrent() {
        val c = challenge ?: return
        val p = progress ?: return
        renderProgress()
        val question = ChallengeEngine.questionAt(
            seed = c.seed,
            position = p.position,
            types = ChallengeEngine.decodeTypes(c.types),
            difficulty = Difficulty.of(c.difficulty),
            snapshot = ChallengeEngine.decodeIds(c.bankIds),
            bank = bank
        )
        current = question
        answered = false
        binding.challengeFeedback.visibility = View.GONE
        binding.challengeNext.visibility = View.GONE
        when (question) {
            is PresentedQuestion.Math -> {
                binding.challengeHint.setText(R.string.mindstop_challenge_hint_math)
                binding.challengePrompt.text = question.question.prompt
                binding.challengeMathGroup.visibility = View.VISIBLE
                binding.challengeChoiceGroup.visibility = View.GONE
                binding.challengeAnswer.setText("")
                binding.challengeAnswer.error = null
                binding.challengeAnswer.isEnabled = true
                binding.challengeSubmit.isEnabled = true
                binding.challengeAnswer.requestFocus()
                showKeyboard()
            }
            is PresentedQuestion.Choice -> {
                binding.challengeHint.setText(
                    when (question.type) {
                        QuestionType.WORD -> R.string.mindstop_challenge_hint_word
                        QuestionType.SYNONYM -> R.string.mindstop_challenge_hint_synonym
                        else -> R.string.mindstop_challenge_hint_custom
                    }
                )
                binding.challengePrompt.text = question.source.question
                binding.challengeMathGroup.visibility = View.GONE
                binding.challengeChoiceGroup.visibility = View.VISIBLE
                optionButtons.forEachIndexed { index, button ->
                    button.text = "${LETTERS[index]}. ${question.options[index]}"
                    button.isEnabled = true
                }
                hideKeyboard()
            }
        }
    }

    private fun answerMath() {
        val question = current as? PresentedQuestion.Math ?: return
        if (answered) return
        val input = binding.challengeAnswer.text?.toString().orEmpty()
        if (MathGenerator.parseAnswer(input) == null) {
            binding.challengeAnswer.error = getString(R.string.mindstop_challenge_not_number)
            return
        }
        submit(ChallengeEngine.isCorrect(question, input, null))
    }

    private fun answerChoice(index: Int) {
        val question = current as? PresentedQuestion.Choice ?: return
        if (answered) return
        submit(ChallengeEngine.isCorrect(question, null, index))
    }

    private fun submit(isCorrect: Boolean) {
        val c = challenge ?: return
        val before = progress ?: return
        val question = current ?: return
        answered = true
        binding.challengeAnswer.isEnabled = false
        binding.challengeSubmit.isEnabled = false
        optionButtons.forEach { it.isEnabled = false }

        val policy = WrongAnswerPolicy.entries.firstOrNull { it.name == c.wrongPolicy } ?: WrongAnswerPolicy.NEXT
        val after = ChallengeEngine.submit(before, isCorrect, policy, c.baseRequired)
        progress = after
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { challenges.saveProgress(c.id, after, System.currentTimeMillis()) }
            binding.challengeFeedback.text = feedbackText(question, isCorrect, before, after)
            binding.challengeFeedback.visibility = View.VISIBLE
            renderProgress()
            if (after.passed) {
                finishPassed()
            } else {
                binding.challengeNext.visibility = View.VISIBLE
                hideKeyboard()
            }
        }
    }

    private fun feedbackText(
        question: PresentedQuestion,
        isCorrect: Boolean,
        before: ChallengeProgress,
        after: ChallengeProgress
    ): String {
        val lines = ArrayList<String>()
        if (isCorrect) {
            lines.add(getString(R.string.mindstop_challenge_right))
        } else {
            val answer = when (question) {
                is PresentedQuestion.Math -> question.question.answer.let { if (it < 0) "${MathGenerator.MINUS}${-it}" else "$it" }
                is PresentedQuestion.Choice -> "${LETTERS[question.correctOption]}. ${question.options[question.correctOption]}"
            }
            lines.add(getString(R.string.mindstop_challenge_wrong, answer))
            if (after.required > before.required) lines.add(getString(R.string.mindstop_challenge_add_one))
            if (before.correct > 0 && after.correct == 0) lines.add(getString(R.string.mindstop_challenge_reset))
        }
        if (question is PresentedQuestion.Choice && question.source.explanation.isNotBlank()) {
            lines.add(question.source.explanation)
        }
        return lines.joinToString("\n")
    }

    private fun finishPassed() {
        val c = challenge ?: return
        if (finishing) return
        finishing = true
        lifecycleScope.launch {
            val granted = withContext(Dispatchers.IO) {
                val now = repo.approxTrustedNow()
                val dayKey = DayKeys.dayKey(now)
                val sessionId = repo.loadMeta(QuotaRepository.KEY_SESSION_ID)?.toLongOrNull()
                val verdict = repo.verdictFor(c.packageName, dayKey, sessionId, now)
                val grants = RuleEngine.grantsToUnlock(verdict, c.grantMinutes * 60_000L, dayKey, sessionId)
                challenges.completeChallenge(c, grants, now)
                grants.isNotEmpty()
            }
            val message = if (granted) {
                getString(R.string.mindstop_challenge_passed, c.grantMinutes)
            } else {
                getString(R.string.mindstop_challenge_not_needed)
            }
            Toast.makeText(this@MindstopChallengeActivity, message, Toast.LENGTH_LONG).show()
            val launch = packageManager.getLaunchIntentForPackage(c.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
            finishAndRemoveTask()
        }
    }

    private fun goHome() {
        hideKeyboard()
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(home)
        finishAndRemoveTask()
    }

    private fun showKeyboard() {
        binding.challengeAnswer.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.challengeAnswer, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
