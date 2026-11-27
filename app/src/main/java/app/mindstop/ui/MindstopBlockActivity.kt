package app.mindstop.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.BedtimeEngine
import app.mindstop.core.BedtimeSettings
import app.mindstop.core.ChallengeConfig
import app.mindstop.core.DayKeys
import app.mindstop.core.LimitKind
import app.mindstop.core.QuotaRule
import app.mindstop.core.RewardTrigger
import app.mindstop.core.RuleEngine
import app.mindstop.core.RuleScope
import app.mindstop.core.Verdict
import app.mindstop.service.ChallengeRepository
import app.mindstop.service.MindstopEngine
import app.mindstop.service.QuotaRepository
import app.mindstop.service.RewardRepository
import app.mindstop.service.Stage5Repository
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopBlockBinding
import java.util.Date

/**
 * Full screen block shown when a supervised app runs out of quota or is in a cooldown.
 * Launched directly over the blocked app so HyperOS background start protection does not abort
 * against the launcher; ending use or starting a cooldown navigates to the home screen.
 *
 * Options: end use, answer questions for a renewal, or take a cooldown. The emergency unlock
 * stays available in every state.
 */
class MindstopBlockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "app.mindstop.extra.BLOCKED_PACKAGE"
        const val EXTRA_BEDTIME_MODE = "app.mindstop.extra.BEDTIME_MODE"
        const val EXTRA_BEDTIME_END = "app.mindstop.extra.BEDTIME_END"
        const val EXTRA_BEDTIME_IMPACT = "app.mindstop.extra.BEDTIME_IMPACT"
        const val EXTRA_BEDTIME_NIGHT_KEY = "app.mindstop.extra.BEDTIME_NIGHT_KEY"
        const val EXTRA_BEDTIME_ON_TIME = "app.mindstop.extra.BEDTIME_ON_TIME"
        const val EXTRA_BEDTIME_LOW_STIM = "app.mindstop.extra.BEDTIME_LOW_STIM"
    }

    private lateinit var binding: ActivityMindstopBlockBinding
    private lateinit var repo: QuotaRepository
    private lateinit var challenges: ChallengeRepository
    private lateinit var rewards: RewardRepository
    private lateinit var stage5: Stage5Repository
    private var blockedPackage: String = ""
    private var bedtimeMode: Boolean = false
    private var bedtimeEndText: String = "07:00"
    private var bedtimeImpactText: String = ""
    private var bedtimeNightKey: String = ""
    private var bedtimeOnTime: Boolean = false
    private var bedtimeLowStim: Boolean = true
    private var bedtimeSettings: BedtimeSettings = BedtimeSettings()
    private var inBedtimeWindow: Boolean = false
    private var verdict: Verdict? = null
    private var dayKey: String = ""
    private var sessionId: Long? = null
    private var config = ChallengeConfig()
    private var challengeRuleId: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.blockRoot)
        repo = QuotaRepository(this)
        challenges = ChallengeRepository(this)
        rewards = RewardRepository(this)
        stage5 = Stage5Repository(this)

        onBackPressedDispatcher.addCallback(this) { endUse() }
        binding.blockEndUse.setOnClickListener { endUse() }
        binding.blockChallenge.setOnClickListener { openChallenge() }
        binding.blockCooldown.setOnClickListener { startCooldown() }
        binding.blockEmergencyUnlock.setOnClickListener { emergencyUnlock() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        busy = false
    }

    override fun onResume() {
        super.onResume()
        busy = false
        refresh()
    }

    private fun refresh() {
        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE).orEmpty()
        bedtimeMode = intent.getBooleanExtra(EXTRA_BEDTIME_MODE, false)
        bedtimeEndText = intent.getStringExtra(EXTRA_BEDTIME_END).orEmpty().ifBlank { "07:00" }
        bedtimeImpactText = intent.getStringExtra(EXTRA_BEDTIME_IMPACT).orEmpty()
        bedtimeNightKey = intent.getStringExtra(EXTRA_BEDTIME_NIGHT_KEY).orEmpty()
        bedtimeOnTime = intent.getBooleanExtra(EXTRA_BEDTIME_ON_TIME, false)
        bedtimeLowStim = intent.getBooleanExtra(EXTRA_BEDTIME_LOW_STIM, true)
        binding.blockAppName.text = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(blockedPackage, 0)).toString()
        } catch (_: Exception) {
            blockedPackage
        }
        lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) { repo.approxTrustedNow() }
            dayKey = DayKeys.dayKey(now)
            sessionId = withContext(Dispatchers.IO) { repo.loadMeta(QuotaRepository.KEY_SESSION_ID)?.toLongOrNull() }
            val result = withContext(Dispatchers.IO) { repo.verdictFor(blockedPackage, dayKey, sessionId, now) }
            config = withContext(Dispatchers.IO) { challenges.loadConfig() }
            bedtimeSettings = withContext(Dispatchers.IO) { stage5.loadBedtime() }
            inBedtimeWindow = BedtimeEngine.isInBedtimeWindow(bedtimeSettings, BedtimeEngine.minuteOfDay(now))
            verdict = result
            render(result, now)
        }
    }

    private suspend fun render(result: Verdict, now: Long) {
        binding.blockNote.visibility = View.GONE
        if (bedtimeMode && bedtimeLowStim) {
            binding.blockRoot.setBackgroundColor(0xFF101713.toInt())
            binding.blockTitle.setTextColor(0xFFD6E4DB.toInt())
            binding.blockAppName.setTextColor(0xFFA7BCB0.toInt())
            binding.blockRuleDetail.setTextColor(0xFFA7BCB0.toInt())
        } else {
            val surface = MaterialColors.getColor(binding.blockRoot, com.google.android.material.R.attr.colorSurface)
            val onSurface = MaterialColors.getColor(binding.blockRoot, com.google.android.material.R.attr.colorOnSurface)
            val onSurfaceVariant = MaterialColors.getColor(binding.blockRoot, com.google.android.material.R.attr.colorOnSurfaceVariant)
            binding.blockRoot.setBackgroundColor(surface)
            binding.blockTitle.setTextColor(onSurface)
            binding.blockAppName.setTextColor(onSurfaceVariant)
            binding.blockRuleDetail.setTextColor(onSurfaceVariant)
        }
        if (bedtimeMode) {
            binding.blockTitle.setText(R.string.mindstop_block_bedtime_title)
            binding.blockRuleDetail.text = bedtimeImpactText.ifBlank {
                getString(R.string.mindstop_block_bedtime_detail, bedtimeEndText)
            }
            binding.blockChallenge.visibility = View.GONE
            binding.blockCooldown.visibility = View.GONE
            return
        }
        val until = result.cooldownUntilWall
        if (result.inCooldown && until != null) {
            binding.blockTitle.setText(R.string.mindstop_block_cooldown_title)
            val ceilMinutes = ((until - now).coerceAtLeast(0L) + 59_999L) / 60_000L
            binding.blockRuleDetail.text = getString(
                R.string.mindstop_block_cooldown_detail,
                getString(R.string.mindstop_minutes_format, ceilMinutes),
                DateFormat.getTimeFormat(this).format(Date(until))
            )
            binding.blockChallenge.visibility = View.GONE
            binding.blockCooldown.visibility = View.GONE
            return
        }

        binding.blockTitle.setText(R.string.mindstop_block_title)
        val bindingStatus = result.binding
        binding.blockRuleDetail.text = when {
            bindingStatus == null -> ""
            bindingStatus.kind == LimitKind.SESSION -> getString(
                R.string.mindstop_block_session_detail, ruleName(bindingStatus.rule), formatMinutes(bindingStatus.limitMs)
            )
            else -> getString(
                R.string.mindstop_block_rule_detail, ruleName(bindingStatus.rule), formatMinutes(bindingStatus.limitMs)
            )
        }

        val target = result.exhausted.minByOrNull { it.remainingMs } ?: bindingStatus
        challengeRuleId = target?.rule?.id
        if (target == null) {
            binding.blockChallenge.visibility = View.GONE
        } else {
            val offer = withContext(Dispatchers.IO) { challenges.offerFor(dayKey, target.rule.id) }
            val active = offer.active
            val tier = if (inBedtimeWindow && bedtimeSettings.maxBedtimeRenewals <= 0) null else offer.tier
            binding.blockChallenge.visibility = View.VISIBLE
            binding.blockChallenge.isEnabled = tier != null
            binding.blockChallenge.text = when {
                active != null && tier != null -> getString(
                    R.string.mindstop_block_challenge_resume, active.correct, active.required, active.grantMinutes
                )
                tier != null -> getString(R.string.mindstop_block_challenge, tier.questions, tier.minutes)
                else -> getString(R.string.mindstop_block_challenge_used_up)
            }
        }

        val effectiveCooldownMin = if (inBedtimeWindow) bedtimeSettings.bedtimeCooldownMinutes else config.cooldownMinutes
        if (result.cooldownHelps) {
            binding.blockCooldown.visibility = View.VISIBLE
            binding.blockCooldown.text = getString(R.string.mindstop_block_cooldown, effectiveCooldownMin)
        } else {
            binding.blockCooldown.visibility = View.GONE
            if (result.exhausted.any { it.kind == LimitKind.DAILY }) {
                binding.blockNote.visibility = View.VISIBLE
                binding.blockNote.setText(R.string.mindstop_block_daily_note)
            }
        }
    }

    private fun endUse() {
        if (busy) return
        busy = true
        val current = verdict
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val now = repo.approxTrustedNow()
                repo.logEvent("block_end_use", blockedPackage, now)
                // Cooldown is forced restriction and never earns an autonomous control reward.
                if (bedtimeMode && blockedPackage.isNotBlank()) {
                    val eval = BedtimeEngine.evaluateAtWall(bedtimeSettings, now)
                    stage5.recordNightVoluntaryStop(eval, now)
                    rewards.tryIssueBedtimeReward(
                        packageName = blockedPackage,
                        sessionId = sessionId ?: now,
                        nightKey = bedtimeNightKey.ifBlank { eval.nightKey },
                        onTime = bedtimeOnTime,
                        nowWall = now
                    )
                } else if (current?.inCooldown != true && blockedPackage.isNotBlank()) {
                    rewards.tryIssueReward(
                        packageName = blockedPackage,
                        sessionId = sessionId ?: now,
                        trigger = RewardTrigger.BLOCK_STOP,
                        nowWall = now
                    )
                } else {
                    null
                }
            }
            val granted = outcome?.granted
            if (granted != null && outcome.settings.feedbackEnabled) {
                val msgRes = if (bedtimeMode) {
                    if (bedtimeOnTime) R.string.mindstop_bedtime_reward_ontime_title
                    else R.string.mindstop_bedtime_reward_late_title
                } else {
                    R.string.mindstop_reward_feedback_partial_title
                }
                Toast.makeText(
                    this@MindstopBlockActivity,
                    getString(msgRes, granted.energy),
                    Toast.LENGTH_SHORT
                ).show()
            }
            goHome()
        }
    }

    private fun openChallenge() {
        val ruleId = challengeRuleId ?: return
        startActivity(
            Intent(this, MindstopChallengeActivity::class.java)
                .putExtra(MindstopChallengeActivity.EXTRA_PACKAGE, blockedPackage)
                .putExtra(MindstopChallengeActivity.EXTRA_RULE_ID, ruleId)
        )
        finish()
    }

    private fun startCooldown() {
        val current = verdict ?: return
        if (!current.cooldownHelps || busy) return
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = repo.approxTrustedNow()
                val effectiveCooldownMin = if (inBedtimeWindow) bedtimeSettings.bedtimeCooldownMinutes else config.cooldownMinutes
                val cooldowns = RuleEngine.cooldownsFor(current, effectiveCooldownMin * 60_000L, now, sessionId)
                repo.startCooldowns(cooldowns, now)
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
        finishAndRemoveTask()
    }

    private fun emergencyUnlock() {
        val current = verdict ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = repo.approxTrustedNow()
                if (bedtimeMode) {
                    repo.saveMeta(
                        MindstopEngine.KEY_BEDTIME_ALLOW_UNTIL,
                        (now + MindstopEngine.EMERGENCY_UNLOCK_MS).toString()
                    )
                    repo.logEvent("bedtime_unplanned_use", "$blockedPackage emergency_5m", now)
                }
                repo.cancelCooldowns(current.cooldowns.map { it.ruleId }.distinct(), now)
                val grants = RuleEngine.grantsToUnlock(current, MindstopEngine.EMERGENCY_UNLOCK_MS, dayKey, sessionId)
                repo.addGrants(grants, "emergency_unlock", now)
                repo.logEvent("unlock_used", "$blockedPackage +${MindstopEngine.EMERGENCY_UNLOCK_MS / 60_000}min", now)
            }
            val launch = packageManager.getLaunchIntentForPackage(blockedPackage)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
            finishAndRemoveTask()
        }
    }

    private fun ruleName(rule: QuotaRule): String = rule.name.ifBlank {
        when (rule.scope) {
            RuleScope.GLOBAL -> getString(R.string.mindstop_scope_global)
            RuleScope.GROUP -> getString(R.string.mindstop_scope_group, rule.packages.size)
            RuleScope.APP -> getString(R.string.mindstop_scope_app)
        }
    }

    private fun formatMinutes(ms: Long): String {
        val minutes = (ms + 30_000L) / 60_000L
        return getString(R.string.mindstop_minutes_format, minutes)
    }
}
