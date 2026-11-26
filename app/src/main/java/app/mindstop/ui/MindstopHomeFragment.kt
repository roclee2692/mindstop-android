package app.mindstop.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setMargins
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mindstop.core.DayKeys
import app.mindstop.core.LimitKind
import app.mindstop.core.QuotaRule
import app.mindstop.core.RuleStatus
import app.mindstop.data.RuleEntity
import app.mindstop.service.QuotaRepository
import app.mindstop.service.toCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentMindstopHomeBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity

/** Mindstop home tab: today's quota, the rule list, essential apps, and diagnostics. */
class MindstopHomeFragment : Fragment() {

    private var _binding: FragmentMindstopHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: QuotaRepository

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatuses()
            handler.postDelayed(this, 30_000L)
        }
    }

    private val exemptPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.saveExempt(apps.toSet()) }
            renderExempt(apps.toSet())
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMindstopHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repo = QuotaRepository(requireContext())

        binding.mindstopAddRule.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopRuleEditActivity::class.java))
        }
        binding.mindstopEditExempt.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val exempt = withContext(Dispatchers.IO) { repo.loadExempt() }
                val intent = Intent(requireContext(), SelectAppsActivity::class.java).apply {
                    putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(exempt))
                }
                exemptPicker.launch(intent)
            }
        }
        binding.mindstopOpenDiagnostics.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopDiagnosticsActivity::class.java))
        }
        binding.mindstopOpenChallengeSettings.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopChallengeSettingsActivity::class.java))
        }
        binding.mindstopOpenIntentionSettings.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopIntentionSettingsActivity::class.java))
        }
        binding.mindstopOpenQuestionBank.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopQuestionBankActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeRules().collect { renderRules(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repo.ensureDefaultRules(getString(R.string.mindstop_default_rule_name), System.currentTimeMillis())
                app.mindstop.service.ChallengeRepository(requireContext()).ensureBuiltinBank(System.currentTimeMillis())
            }
            renderExempt(withContext(Dispatchers.IO) { repo.loadExempt() })
            val intentionOn = withContext(Dispatchers.IO) { repo.isIntentionPromptEnabled() }
            val remindersOn = withContext(Dispatchers.IO) { repo.isReminderEnabled() }
            if (_binding != null) {
                binding.mindstopIntentionSwitch.isChecked = intentionOn
                binding.mindstopReminderSwitch.isChecked = remindersOn
            }
        }
        binding.mindstopIntentionSwitch.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { repo.setIntentionPromptEnabled(checked) }
            }
        }
        binding.mindstopReminderSwitch.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { repo.setReminderEnabled(checked) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
        handler.postDelayed(refresher, 30_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(refresher)
        _binding = null
        super.onDestroyView()
    }

    private fun refreshStatuses() {
        val b = _binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val dayKey = DayKeys.dayKey(now)
            val sessionId = withContext(Dispatchers.IO) {
                repo.loadMeta(QuotaRepository.KEY_SESSION_ID)?.toLongOrNull()
            }
            val statuses = withContext(Dispatchers.IO) { repo.dailyRuleStatuses(dayKey, sessionId) }
            if (_binding != null) renderStatuses(statuses)
        }
    }

    private fun renderStatuses(statuses: List<RuleStatus>) {
        val container = binding.mindstopStatusContainer
        container.removeAllViews()
        if (statuses.isEmpty()) {
            container.addView(makeText(getString(R.string.mindstop_no_status)))
            return
        }
        for (status in statuses) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 24)
                layoutParams = params
            }
            val kindLabel = if (status.kind == LimitKind.DAILY) {
                getString(R.string.mindstop_kind_daily)
            } else {
                getString(R.string.mindstop_kind_session)
            }
            row.addView(makeText(status.rule.name.ifBlank { kindLabel }, bold = true))
            val progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max = ((status.limitMs + status.grantedMs) / 1000L).toInt().coerceAtLeast(1)
                this.progress = (status.usedMs.coerceAtMost(status.limitMs + status.grantedMs) / 1000L).toInt()
            }
            row.addView(progress)
            row.addView(makeText(getString(
                R.string.mindstop_status_line,
                formatDuration(status.usedMs),
                formatDuration(status.limitMs + status.grantedMs),
                formatDuration(status.remainingMs.coerceAtLeast(0L))
            )))
            container.addView(row)
        }
    }

    private fun renderRules(rules: List<RuleEntity>) {
        val container = binding.mindstopRuleContainer
        container.removeAllViews()
        if (rules.isEmpty()) {
            container.addView(makeText(getString(R.string.mindstop_no_rules)))
            return
        }
        for (entity in rules) {
            val rule = entity.toCore()
            val row = makeText(describeRule(rule), bold = false).apply {
                setOnClickListener {
                    startActivity(
                        Intent(requireContext(), MindstopRuleEditActivity::class.java)
                            .putExtra(MindstopRuleEditActivity.EXTRA_RULE_ID, rule.id)
                    )
                }
            }
            container.addView(row)
        }
    }

    private fun describeRule(rule: QuotaRule): String {
        val parts = ArrayList<String>()
        rule.dailyLimitMs?.let { parts.add(getString(R.string.mindstop_rule_daily_part, formatDuration(it))) }
        rule.sessionLimitMs?.let { parts.add(getString(R.string.mindstop_rule_session_part, formatDuration(it))) }
        val scope = when (rule.scope) {
            app.mindstop.core.RuleScope.GLOBAL -> getString(R.string.mindstop_scope_global)
            app.mindstop.core.RuleScope.GROUP -> getString(R.string.mindstop_scope_group, rule.packages.size)
            app.mindstop.core.RuleScope.APP -> getString(R.string.mindstop_scope_app)
        }
        val state = if (rule.enabled) "" else getString(R.string.mindstop_rule_disabled)
        return "${rule.name.ifBlank { scope }} · ${parts.joinToString(" · ")} · $scope$state"
    }

    private fun renderExempt(exempt: Set<String>) {
        val b = _binding ?: return
        b.mindstopExemptSummary.text = getString(R.string.mindstop_exempt_summary, exempt.size)
    }

    private fun makeText(text: String, bold: Boolean = false): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = if (bold) 16f else 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms + 30_000L) / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            getString(R.string.mindstop_duration_hm, hours, minutes)
        } else {
            getString(R.string.mindstop_minutes_format, minutes)
        }
    }
}
