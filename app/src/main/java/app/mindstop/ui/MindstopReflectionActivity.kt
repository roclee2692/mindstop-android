package app.mindstop.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.IntentPurpose
import app.mindstop.core.ReflectionOutcome
import app.mindstop.core.ReflectionReason
import app.mindstop.service.QuotaRepository
import app.mindstop.service.Stage5Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopReflectionBinding

/**
 * Lightweight, skippable, non-judgmental post-session check-in (SPEC §14).
 * Records goal completion outcome and optional multi-select deviation reasons locally.
 */
class MindstopReflectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "app.mindstop.extra.REFLECT_PACKAGE"
        const val EXTRA_SESSION_ID = "app.mindstop.extra.REFLECT_SESSION_ID"
        const val EXTRA_PURPOSE = "app.mindstop.extra.REFLECT_PURPOSE"
        const val EXTRA_GOAL = "app.mindstop.extra.REFLECT_GOAL"
        const val EXTRA_PLANNED_MINUTES = "app.mindstop.extra.REFLECT_PLANNED_MINUTES"
        const val EXTRA_ACTUAL_MS = "app.mindstop.extra.REFLECT_ACTUAL_MS"
    }

    private lateinit var binding: ActivityMindstopReflectionBinding
    private lateinit var quotaRepo: QuotaRepository
    private lateinit var stage5: Stage5Repository

    private var pkg: String = ""
    private var sessionId: Long = 0L
    private var purpose: IntentPurpose = IntentPurpose.STUDY
    private var goal: String = ""
    private var plannedMinutes: Int? = null
    private var actualMs: Long = 0L
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopReflectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.reflectionRoot)
        MindstopUi.setupToolbar(this, binding.reflectionToolbar, R.string.mindstop_reflection_title)

        quotaRepo = QuotaRepository(this)
        stage5 = Stage5Repository(this)

        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
        purpose = IntentPurpose.fromCode(intent.getStringExtra(EXTRA_PURPOSE)) ?: IntentPurpose.STUDY
        goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
        plannedMinutes = if (intent.hasExtra(EXTRA_PLANNED_MINUTES)) {
            intent.getIntExtra(EXTRA_PLANNED_MINUTES, 0).takeIf { it > 0 }
        } else null
        actualMs = intent.getLongExtra(EXTRA_ACTUAL_MS, 0L)

        val plannedText = plannedMinutes?.let { getString(R.string.mindstop_minutes_format, it) }
            ?: getString(R.string.mindstop_intention_free)
        val actualText = MindstopUi.formatDuration(this, actualMs)
        binding.reflectionSummary.text = if (goal.isNotBlank()) {
            getString(R.string.mindstop_reflection_goal_summary, goal, plannedText, actualText)
        } else {
            getString(R.string.mindstop_reflection_summary_no_goal, plannedText, actualText)
        }

        binding.reflectionToolbar.setNavigationOnClickListener { skip() }
        onBackPressedDispatcher.addCallback(this) { skip() }
        binding.reflectionSave.setOnClickListener { save() }
        binding.reflectionSkip.setOnClickListener { skip() }
    }

    private fun selectedOutcome(): ReflectionOutcome = when (binding.reflectionOutcomeGroup.checkedRadioButtonId) {
        R.id.reflection_outcome_partial -> ReflectionOutcome.PARTIAL
        R.id.reflection_outcome_not_done -> ReflectionOutcome.NOT_COMPLETED
        R.id.reflection_outcome_switched -> ReflectionOutcome.SWITCHED
        else -> ReflectionOutcome.COMPLETED
    }

    private fun selectedReasons(): Set<ReflectionReason> = buildSet {
        if (binding.reflectionReasonRecs.isChecked) add(ReflectionReason.RECOMMENDATIONS)
        if (binding.reflectionReasonComments.isChecked) add(ReflectionReason.COMMENTS)
        if (binding.reflectionReasonForgot.isChecked) add(ReflectionReason.FORGOT_GOAL)
        if (binding.reflectionReasonFatigue.isChecked) add(ReflectionReason.FATIGUE)
        if (binding.reflectionReasonRest.isChecked) add(ReflectionReason.WANT_REST)
        if (binding.reflectionReasonOther.isChecked) add(ReflectionReason.OTHER)
    }

    private fun save() {
        if (busy) return
        busy = true
        val outcome = selectedOutcome()
        val reasons = selectedReasons()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = quotaRepo.approxTrustedNow()
                stage5.saveReflection(
                    sessionId = if (sessionId > 0L) sessionId else now,
                    packageName = pkg,
                    purpose = purpose,
                    goal = goal,
                    plannedMinutes = plannedMinutes,
                    actualMs = actualMs,
                    outcome = outcome,
                    reasons = reasons,
                    nowWall = now
                )
            }
            Toast.makeText(this@MindstopReflectionActivity, R.string.mindstop_reflection_saved, Toast.LENGTH_SHORT).show()
            goHome()
        }
    }

    private fun skip() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = quotaRepo.approxTrustedNow()
                stage5.logReflectionSkipped(pkg, purpose, now)
            }
            goHome()
        }
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(home)
        finish()
    }
}
