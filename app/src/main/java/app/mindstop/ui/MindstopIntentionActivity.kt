package app.mindstop.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.CalmWait
import app.mindstop.core.DecisionTierAdvisor
import app.mindstop.core.IntentPurpose
import app.mindstop.core.IntentStatus
import app.mindstop.core.IntentionSettings
import app.mindstop.core.MoneyFormat
import app.mindstop.core.PromptMode
import app.mindstop.core.RewardTrigger
import app.mindstop.core.SessionIntent
import app.mindstop.core.ShoppingIntentType
import app.mindstop.service.QuotaRepository
import app.mindstop.service.RewardRepository
import app.mindstop.service.ShoppingRepository
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopIntentionBinding

/**
 * Asks what the user wants to do before a supervised app can be used: a purpose, a goal and search
 * words for study or errands, and a planned time. A calm wait runs while the user fills it in;
 * starting is only possible once the wait is over. Errands skip the wait so necessary tasks stay
 * quick. In [PromptMode.EXTEND] the planned time is up and the user can add time or leave.
 */
class MindstopIntentionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "app.mindstop.extra.INTENTION_PACKAGE"
        const val EXTRA_SESSION_ID = "app.mindstop.extra.INTENTION_SESSION_ID"
        const val EXTRA_MODE = "app.mindstop.extra.INTENTION_MODE"

        private val NEW_CHOICES = listOf(5, 10, 20, 30, 45)
        private val EXTEND_CHOICES = listOf(5, 10, 20, 30)
        private const val CHOICE_CUSTOM = -1
        private const val CHOICE_FREE = 0
    }

    private data class Choice(val valid: Boolean, val minutes: Int?)

    private lateinit var binding: ActivityMindstopIntentionBinding
    private lateinit var repo: QuotaRepository
    private lateinit var rewards: RewardRepository
    private lateinit var shoppingRepo: ShoppingRepository
    private val handler = Handler(Looper.getMainLooper())

    private var targetPackage = ""
    private var sessionId = 0L
    private var mode = PromptMode.NEW
    private var appLabel = ""
    private var isShoppingApp = false
    private var shoppingIntentType: ShoppingIntentType? = null

    private var settings = IntentionSettings()
    private var existing: SessionIntent? = null
    private var purpose: IntentPurpose? = null
    private var waitEndElapsed = 0L
    private var loaded = false
    private var busy = false

    private val ticker = object : Runnable {
        override fun run() {
            updateState()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopIntentionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        repo = QuotaRepository(this)
        rewards = RewardRepository(this)
        shoppingRepo = ShoppingRepository(this)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
        mode = PromptMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: PromptMode.NEW
        if (sessionId == 0L) {
            finish()
            return
        }
        appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0)).toString()
        } catch (_: Exception) {
            targetPackage
        }

        onBackPressedDispatcher.addCallback(this) { leave() }
        binding.intentionLeave.setOnClickListener { leave() }
        binding.intentionCopy.setOnClickListener { copyKeywords() }
        binding.intentionStart.setOnClickListener { start() }
        binding.intentionDone.setOnClickListener { goalDone() }
        binding.intentionPurposeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.intention_purpose_study -> selectPurpose(IntentPurpose.STUDY)
                R.id.intention_purpose_fun -> selectPurpose(IntentPurpose.FUN)
                R.id.intention_purpose_errand -> selectPurpose(IntentPurpose.ERRAND)
            }
        }
        binding.intentionMinutesGroup.setOnCheckedStateChangeListener { _, _ ->
            binding.intentionCustomLayout.visibility =
                if (selectedChoiceValue() == CHOICE_CUSTOM) View.VISIBLE else View.GONE
            updateState()
        }
        binding.intentionGoal.doAfterTextChanged { updateState() }
        binding.intentionKeywords.doAfterTextChanged { updateState() }
        binding.intentionCustomMinutes.doAfterTextChanged { updateState() }
        binding.intentionShopBudget.doAfterTextChanged { updateShoppingTierHint() }
        binding.intentionShopOpenHub.setOnClickListener {
            startActivity(Intent(this, MindstopShoppingActivity::class.java))
        }
        binding.intentionShoppingGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val picked = when (checkedIds.firstOrNull()) {
                R.id.intention_shop_buy -> ShoppingIntentType.PLANNED_BUY
                R.id.intention_shop_logistics -> ShoppingIntentType.LOGISTICS
                R.id.intention_shop_service -> ShoppingIntentType.CUSTOMER_SERVICE
                R.id.intention_shop_after_sales -> ShoppingIntentType.AFTER_SALES
                R.id.intention_shop_review -> ShoppingIntentType.REVIEW
                R.id.intention_shop_browse -> ShoppingIntentType.BROWSE
                else -> null
            }
            if (picked != null) selectShoppingIntent(picked)
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                settings = repo.loadIntentionSettings()
                existing = repo.sessionIntent(sessionId)
                isShoppingApp = shoppingRepo.isShoppingPackage(targetPackage)
            }
            val current = existing
            if (mode == PromptMode.EXTEND && (current == null || current.minutes == null)) mode = PromptMode.NEW
            val token = "$sessionId|$mode|${current?.wall}|${current?.minutes}"
            val now = withContext(Dispatchers.IO) { repo.approxTrustedNow() }
            val waitStart = withContext(Dispatchers.IO) { repo.calmWaitStart(token, now) }
            waitEndElapsed = SystemClock.elapsedRealtime() + CalmWait.remainingMs(waitStart, settings.waitSeconds, now)
            if (mode == PromptMode.EXTEND && current != null) renderExtend(current) else renderNew()
            loaded = true
            updateState()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sameSession = intent.getLongExtra(EXTRA_SESSION_ID, 0L) == sessionId
        val sameMode = intent.getStringExtra(EXTRA_MODE) == mode.name
        if (!sameSession || !sameMode) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun renderNew() {
        binding.intentionTitle.text = getString(R.string.mindstop_intention_question, appLabel)
        binding.intentionSubtitle.setText(R.string.mindstop_intention_subtitle)
        binding.intentionMinutesLabel.setText(R.string.mindstop_intention_minutes_label)
        if (isShoppingApp) {
            binding.intentionPurposeGroup.visibility = View.GONE
            binding.intentionShoppingGroup.visibility = View.VISIBLE
            binding.intentionShopOpenHub.visibility = View.VISIBLE
        } else {
            binding.intentionPurposeGroup.visibility = View.VISIBLE
            binding.intentionShoppingGroup.visibility = View.GONE
            binding.intentionShopOpenHub.visibility = View.GONE
        }
    }

    private fun selectShoppingIntent(type: ShoppingIntentType) {
        shoppingIntentType = type
        when (type) {
            ShoppingIntentType.PLANNED_BUY -> {
                purpose = IntentPurpose.ERRAND
                binding.intentionPurposeHint.setText(R.string.mindstop_shop_intent_buy_hint)
                binding.intentionGoalSection.visibility = View.VISIBLE
                binding.intentionGoalLayout.hint = getString(R.string.mindstop_shop_item_name_hint)
                binding.intentionShopBuyExtra.visibility = View.VISIBLE
                updateShoppingTierHint()
                buildChoices(NEW_CHOICES, allowFree = false, preselect = type.defaultMinutes)
            }
            ShoppingIntentType.BROWSE -> {
                purpose = IntentPurpose.FUN
                binding.intentionPurposeHint.setText(R.string.mindstop_shop_intent_browse_hint)
                binding.intentionGoalSection.visibility = View.GONE
                binding.intentionShopBuyExtra.visibility = View.GONE
                buildChoices(NEW_CHOICES, allowFree = false, preselect = type.defaultMinutes)
            }
            else -> {
                purpose = IntentPurpose.ERRAND
                binding.intentionPurposeHint.setText(R.string.mindstop_shop_intent_errand_hint)
                binding.intentionGoalSection.visibility = View.GONE
                binding.intentionShopBuyExtra.visibility = View.GONE
                buildChoices(NEW_CHOICES, allowFree = false, preselect = type.defaultMinutes)
            }
        }
        updateState()
    }

    private fun updateShoppingTierHint() {
        val cents = MoneyFormat.parseYuanToCents(binding.intentionShopBudget.text?.toString()) ?: 0L
        if (cents <= 0L) {
            binding.intentionShopTierHint.text = ""
            return
        }
        val tier = DecisionTierAdvisor.recommend(cents)
        binding.intentionShopTierHint.text = getString(
            R.string.mindstop_shop_tier_suggestion,
            tier.minMinutes,
            tier.maxMinutes
        )
    }

    private fun renderExtend(current: SessionIntent) {
        purpose = current.purpose
        binding.intentionTitle.text = getString(R.string.mindstop_extend_title, current.minutes ?: 0)
        binding.intentionSubtitle.setText(R.string.mindstop_extend_subtitle)
        binding.intentionPurposeGroup.visibility = View.GONE
        binding.intentionPurposeHint.visibility = View.GONE
        binding.intentionGoalSection.visibility = View.GONE
        if (current.goal.isNotBlank()) {
            binding.intentionGoalReadonly.visibility = View.VISIBLE
            binding.intentionGoalReadonly.text = getString(R.string.mindstop_overlay_goal, current.goal)
            binding.intentionDone.visibility = View.VISIBLE
        }
        binding.intentionMinutesLabel.setText(R.string.mindstop_extend_minutes_label)
        buildChoices(EXTEND_CHOICES, allowFree = false, preselect = EXTEND_CHOICES.first())
    }

    private fun selectPurpose(selected: IntentPurpose) {
        purpose = selected
        binding.intentionPurposeHint.setText(
            when (selected) {
                IntentPurpose.STUDY -> R.string.mindstop_purpose_study_hint
                IntentPurpose.FUN -> R.string.mindstop_purpose_fun_hint
                IntentPurpose.ERRAND -> R.string.mindstop_purpose_errand_hint
            }
        )
        binding.intentionGoalSection.visibility = if (selected.hasGoal) View.VISIBLE else View.GONE
        buildChoices(NEW_CHOICES, allowFree = selected == IntentPurpose.FUN, preselect = selected.defaultMinutes)
        updateState()
    }

    private fun buildChoices(values: List<Int>, allowFree: Boolean, preselect: Int) {
        val group = binding.intentionMinutesGroup
        group.removeAllViews()
        val all = values + CHOICE_CUSTOM + if (allowFree) listOf(CHOICE_FREE) else emptyList()
        for (value in all) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                tag = value
                isCheckable = true
                text = when (value) {
                    CHOICE_CUSTOM -> getString(R.string.mindstop_intention_custom)
                    CHOICE_FREE -> getString(R.string.mindstop_intention_free)
                    else -> getString(R.string.mindstop_minutes_format, value)
                }
            }
            group.addView(chip)
            if (value == preselect) group.check(chip.id)
        }
    }

    private fun selectedChoiceValue(): Int? {
        val id = binding.intentionMinutesGroup.checkedChipId
        if (id == View.NO_ID) return null
        return binding.intentionMinutesGroup.findViewById<Chip>(id)?.tag as? Int
    }

    private fun chosenMinutes(): Choice = when (val value = selectedChoiceValue()) {
        null -> Choice(false, null)
        CHOICE_FREE -> Choice(true, null)
        CHOICE_CUSTOM -> {
            val typed = binding.intentionCustomMinutes.text?.toString()?.trim()?.toIntOrNull()
            if (typed != null && typed in IntentionSettings.PLAN_MINUTES_RANGE) Choice(true, typed) else Choice(false, null)
        }
        else -> Choice(true, value)
    }

    private fun goalText(): String = binding.intentionGoal.text?.toString()?.trim().orEmpty()

    private fun keywordsText(): String =
        if (mode == PromptMode.EXTEND) existing?.keywords.orEmpty()
        else binding.intentionKeywords.text?.toString()?.trim().orEmpty()

    private fun waitLeftMs(): Long =
        if (purpose?.usesCalmWait == false) 0L
        else (waitEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun updateState() {
        if (!loaded) return
        val selected = purpose
        val choice = chosenMinutes()
        val waitLeft = waitLeftMs()
        val waitSeconds = ((waitLeft + 999L) / 1000L).toInt()
        val fullWaitMs = CalmWait.clamp(settings.waitSeconds) * 1000L

        when {
            selected == IntentPurpose.ERRAND -> {
                binding.intentionWaitText.setText(R.string.mindstop_wait_skipped)
                binding.intentionWaitProgress.visibility = View.GONE
            }
            fullWaitMs == 0L -> {
                binding.intentionWaitText.text = ""
                binding.intentionWaitProgress.visibility = View.GONE
            }
            else -> {
                binding.intentionWaitText.text = if (waitLeft > 0L) {
                    getString(R.string.mindstop_wait_running, waitSeconds)
                } else {
                    getString(R.string.mindstop_wait_done)
                }
                binding.intentionWaitProgress.visibility = View.VISIBLE
                binding.intentionWaitProgress.progress = (((fullWaitMs - waitLeft) * 1000L) / fullWaitMs).toInt()
            }
        }

        binding.intentionCopy.visibility = if (keywordsText().isNotBlank()) View.VISIBLE else View.GONE

        val needGoal = mode == PromptMode.NEW && (
            selected == IntentPurpose.STUDY ||
                (isShoppingApp && shoppingIntentType == ShoppingIntentType.PLANNED_BUY)
            ) && goalText().isBlank()
        val (label, ready) = when {
            selected == null -> getString(R.string.mindstop_start_pick_purpose) to false
            !choice.valid -> getString(R.string.mindstop_start_pick_time) to false
            needGoal -> getString(R.string.mindstop_start_need_goal) to false
            waitLeft > 0L -> getString(R.string.mindstop_start_wait, waitSeconds) to false
            mode == PromptMode.EXTEND -> getString(R.string.mindstop_extend_start, choice.minutes ?: 0) to true
            choice.minutes == null -> getString(R.string.mindstop_start_free) to true
            else -> getString(R.string.mindstop_start_minutes, choice.minutes) to true
        }
        binding.intentionStart.text = label
        binding.intentionStart.isEnabled = ready && !busy
    }

    private fun start() {
        val selected = purpose ?: return
        val choice = chosenMinutes()
        if (!choice.valid || waitLeftMs() > 0L || busy) return
        busy = true
        val shopType = shoppingIntentType
        val goal = when {
            isShoppingApp && shopType == ShoppingIntentType.PLANNED_BUY -> {
                val budgetRaw = binding.intentionShopBudget.text?.toString()?.trim().orEmpty()
                val must = binding.intentionShopMustHaves.text?.toString()?.trim().orEmpty()
                listOfNotNull(
                    goalText().takeIf { it.isNotBlank() },
                    budgetRaw.takeIf { it.isNotBlank() }?.let { "¥$it" },
                    must.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
            }
            isShoppingApp && shopType != null && shopType.isFastErrand -> when (shopType) {
                ShoppingIntentType.LOGISTICS -> getString(R.string.mindstop_shop_intent_logistics)
                ShoppingIntentType.CUSTOMER_SERVICE -> getString(R.string.mindstop_shop_intent_service)
                ShoppingIntentType.AFTER_SALES -> getString(R.string.mindstop_shop_intent_after_sales)
                ShoppingIntentType.REVIEW -> getString(R.string.mindstop_shop_intent_review)
                else -> ""
            }
            selected.hasGoal -> goalText()
            else -> ""
        }
        val keywords = if (selected.hasGoal) keywordsText() else ""
        val budgetCents = MoneyFormat.parseYuanToCents(binding.intentionShopBudget.text?.toString()) ?: 0L
        val mustHaves = binding.intentionShopMustHaves.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = repo.approxTrustedNow()
                if (mode == PromptMode.EXTEND) {
                    repo.extendIntention(sessionId, choice.minutes ?: 0, now)
                } else {
                    if (isShoppingApp && shopType == ShoppingIntentType.PLANNED_BUY && goalText().isNotBlank()) {
                        shoppingRepo.createTask(
                            itemName = goalText(),
                            purpose = appLabel,
                            budgetCents = budgetCents,
                            mustHaves = mustHaves,
                            keywords = keywords,
                            nowWall = now
                        )
                    }
                    val base = repo.supervisedSessionUsage(sessionId)
                    repo.saveIntention(sessionId, targetPackage, selected, goal, keywords, choice.minutes, base, now)
                }
                repo.clearCalmWait()
            }
            moveTaskToBack(true)
            finish()
        }
    }

    private fun goalDone() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val (outcome, actualMs) = withContext(Dispatchers.IO) {
                val now = repo.approxTrustedNow()
                repo.finishIntention(sessionId, IntentStatus.DONE, now)
                repo.clearCalmWait()
                val issued = if (targetPackage.isNotBlank()) {
                    rewards.tryIssueReward(targetPackage, sessionId, RewardTrigger.ACTIVE_EXIT, now)
                } else null
                issued to repo.allSessionTotals(sessionId).values.sum()
            }
            showRewardToastIfGranted(outcome)
            if (shouldOfferStudyReflection()) openReflection(actualMs) else goHome()
        }
    }

    private fun leave() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val (outcome, actualMs) = withContext(Dispatchers.IO) {
                val now = repo.approxTrustedNow()
                if (mode == PromptMode.EXTEND) repo.finishIntention(sessionId, IntentStatus.ENDED, now)
                repo.logEvent("intention_left", "$targetPackage ${mode.name}", now)
                val issued = if (targetPackage.isNotBlank()) {
                    val trigger = if (mode == PromptMode.EXTEND) RewardTrigger.ACTIVE_EXIT else RewardTrigger.INTENTION_DECLINE
                    rewards.tryIssueReward(targetPackage, sessionId, trigger, now)
                } else null
                val total = if (mode == PromptMode.EXTEND) repo.allSessionTotals(sessionId).values.sum() else 0L
                issued to total
            }
            showRewardToastIfGranted(outcome)
            if (shouldOfferStudyReflection()) openReflection(actualMs) else goHome()
        }
    }

    private fun shouldOfferStudyReflection(): Boolean =
        mode == PromptMode.EXTEND && existing?.purpose == IntentPurpose.STUDY && targetPackage.isNotBlank()

    private fun openReflection(actualMs: Long) {
        val current = existing
        val reflect = Intent(this, MindstopReflectionActivity::class.java).apply {
            putExtra(MindstopReflectionActivity.EXTRA_PACKAGE, targetPackage)
            putExtra(MindstopReflectionActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(MindstopReflectionActivity.EXTRA_PURPOSE, (current?.purpose ?: IntentPurpose.STUDY).code)
            putExtra(MindstopReflectionActivity.EXTRA_GOAL, current?.goal.orEmpty())
            current?.minutes?.let { putExtra(MindstopReflectionActivity.EXTRA_PLANNED_MINUTES, it) }
            putExtra(MindstopReflectionActivity.EXTRA_ACTUAL_MS, actualMs)
        }
        startActivity(reflect)
        finish()
    }

    private fun showRewardToastIfGranted(outcome: RewardRepository.IssueOutcome?) {
        val granted = outcome?.granted ?: return
        if (!outcome.settings.feedbackEnabled) return
        Toast.makeText(
            this,
            getString(R.string.mindstop_reward_feedback_full_title, granted.energy),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(home)
        finish()
    }

    private fun copyKeywords() {
        val keywords = keywordsText()
        if (keywords.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Mindstop", keywords))
        Toast.makeText(this, R.string.mindstop_copied, Toast.LENGTH_SHORT).show()
    }
}
