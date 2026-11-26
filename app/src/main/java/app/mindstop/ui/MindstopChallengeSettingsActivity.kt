package app.mindstop.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.ChallengeConfig
import app.mindstop.core.Difficulty
import app.mindstop.core.QuestionType
import app.mindstop.core.RenewalTier
import app.mindstop.core.WrongAnswerPolicy
import app.mindstop.service.ChallengeRepository
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopChallengeSettingsBinding

/** Edits how block screen challenges and cooldowns work. */
class MindstopChallengeSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopChallengeSettingsBinding
    private lateinit var challenges: ChallengeRepository
    private val tierFields = ArrayList<Pair<TextInputEditText, TextInputEditText>>()
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopChallengeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.csRoot)
        MindstopUi.setupToolbar(this, binding.csToolbar, R.string.mindstop_section_challenge)
        challenges = ChallengeRepository(this)

        binding.csSave.setOnClickListener { save() }
        binding.csOpenBank.setOnClickListener {
            startActivity(Intent(this, MindstopQuestionBankActivity::class.java))
        }
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { challenges.loadConfig() }
            render(config)
            loaded = true
        }
    }

    private fun render(config: ChallengeConfig) {
        binding.csTierContainer.removeAllViews()
        tierFields.clear()
        val rows = maxOf(DEFAULT_ROWS, config.tiers.size)
        for (index in 0 until rows) {
            val tier = config.tiers.getOrNull(index)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = getString(R.string.mindstop_cs_tier_label, index + 1)
                minWidth = (64 * resources.displayMetrics.density).toInt()
            })
            val questions = numberField(R.string.mindstop_cs_questions_hint, tier?.questions)
            val minutes = numberField(R.string.mindstop_cs_minutes_hint, tier?.minutes)
            row.addView(questions.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(minutes.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * resources.displayMetrics.density).toInt()
            })
            binding.csTierContainer.addView(row)
            tierFields.add(questions.second to minutes.second)
        }
        binding.csMaxRenewals.setText(config.maxRenewals.toString())
        binding.csTypeMath.isChecked = QuestionType.MATH in config.types
        binding.csTypeWord.isChecked = QuestionType.WORD in config.types
        binding.csTypeSynonym.isChecked = QuestionType.SYNONYM in config.types
        binding.csTypeCustom.isChecked = QuestionType.CUSTOM in config.types
        binding.csDifficultyGroup.check(
            when (config.difficulty) {
                Difficulty.EASY -> R.id.cs_difficulty_easy
                Difficulty.MEDIUM -> R.id.cs_difficulty_medium
                Difficulty.HARD -> R.id.cs_difficulty_hard
            }
        )
        binding.csWrongGroup.check(
            when (config.wrongPolicy) {
                WrongAnswerPolicy.NEXT -> R.id.cs_wrong_next
                WrongAnswerPolicy.ADD_ONE -> R.id.cs_wrong_add_one
                WrongAnswerPolicy.RESET -> R.id.cs_wrong_reset
            }
        )
        binding.csCooldownMinutes.setText(config.cooldownMinutes.toString())
    }

    private fun numberField(hint: Int, value: Int?): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this)
        layout.hint = getString(hint)
        val edit = TextInputEditText(layout.context)
        edit.inputType = InputType.TYPE_CLASS_NUMBER
        edit.setText(value?.toString().orEmpty())
        layout.addView(edit)
        return layout to edit
    }

    private fun save() {
        if (!loaded) return
        val tiers = ArrayList<RenewalTier>()
        for ((questionsField, minutesField) in tierFields) {
            val q = questionsField.text?.toString()?.trim().orEmpty()
            val m = minutesField.text?.toString()?.trim().orEmpty()
            if (q.isEmpty() && m.isEmpty()) continue
            val questions = q.toIntOrNull()
            val minutes = m.toIntOrNull()
            if (questions == null || minutes == null ||
                questions !in ChallengeConfig.QUESTIONS_RANGE || minutes !in ChallengeConfig.MINUTES_RANGE
            ) {
                invalid()
                return
            }
            tiers.add(RenewalTier(questions, minutes))
        }
        val maxRenewals = binding.csMaxRenewals.text?.toString()?.trim()?.toIntOrNull()
        val cooldown = binding.csCooldownMinutes.text?.toString()?.trim()?.toIntOrNull()
        if (tiers.isEmpty() || maxRenewals == null || maxRenewals !in ChallengeConfig.RENEWALS_RANGE ||
            cooldown == null || cooldown !in ChallengeConfig.COOLDOWN_RANGE
        ) {
            invalid()
            return
        }
        val types = buildSet {
            if (binding.csTypeMath.isChecked) add(QuestionType.MATH)
            if (binding.csTypeWord.isChecked) add(QuestionType.WORD)
            if (binding.csTypeSynonym.isChecked) add(QuestionType.SYNONYM)
            if (binding.csTypeCustom.isChecked) add(QuestionType.CUSTOM)
        }
        if (types.isEmpty()) {
            Toast.makeText(this, R.string.mindstop_cs_need_type, Toast.LENGTH_LONG).show()
            return
        }
        val config = ChallengeConfig(
            tiers = tiers,
            maxRenewals = maxRenewals,
            difficulty = when (binding.csDifficultyGroup.checkedRadioButtonId) {
                R.id.cs_difficulty_easy -> Difficulty.EASY
                R.id.cs_difficulty_hard -> Difficulty.HARD
                else -> Difficulty.MEDIUM
            },
            types = types,
            wrongPolicy = when (binding.csWrongGroup.checkedRadioButtonId) {
                R.id.cs_wrong_add_one -> WrongAnswerPolicy.ADD_ONE
                R.id.cs_wrong_reset -> WrongAnswerPolicy.RESET
                else -> WrongAnswerPolicy.NEXT
            },
            cooldownMinutes = cooldown
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { challenges.saveConfig(config, System.currentTimeMillis()) }
            Toast.makeText(this@MindstopChallengeSettingsActivity, R.string.mindstop_cs_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun invalid() {
        Toast.makeText(this, R.string.mindstop_cs_invalid, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val DEFAULT_ROWS = 3
    }
}
