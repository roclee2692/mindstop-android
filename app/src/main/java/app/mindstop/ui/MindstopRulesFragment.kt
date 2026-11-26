package app.mindstop.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mindstop.data.RuleEntity
import app.mindstop.service.QuotaRepository
import app.mindstop.service.toCore
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentMindstopRulesBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity

/** Rules, essential apps, intention & reminder toggles, and challenge settings entry points. */
class MindstopRulesFragment : Fragment() {

    private var _binding: FragmentMindstopRulesBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: QuotaRepository

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
        _binding = FragmentMindstopRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repo = QuotaRepository(requireContext())

        binding.rulesPresetGroupsButton.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopPresetGroupsActivity::class.java))
        }
        binding.rulesAddButton.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopRuleEditActivity::class.java))
        }
        binding.rulesEditExempt.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val exempt = withContext(Dispatchers.IO) { repo.loadExempt() }
                val intent = Intent(requireContext(), SelectAppsActivity::class.java).apply {
                    putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(exempt))
                }
                exemptPicker.launch(intent)
            }
        }
        binding.rulesOpenIntentionSettings.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopIntentionSettingsActivity::class.java))
        }
        binding.rulesOpenChallengeSettings.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopChallengeSettingsActivity::class.java))
        }
        binding.rulesOpenQuestionBank.setOnClickListener {
            startActivity(Intent(requireContext(), MindstopQuestionBankActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeRules().collect { renderRules(it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val exempt = withContext(Dispatchers.IO) { repo.loadExempt() }
            val intentionOn = withContext(Dispatchers.IO) { repo.isIntentionPromptEnabled() }
            val remindersOn = withContext(Dispatchers.IO) { repo.isReminderEnabled() }
            if (_binding != null) {
                renderExempt(exempt)
                binding.rulesIntentionSwitch.isChecked = intentionOn
                binding.rulesReminderSwitch.isChecked = remindersOn
                binding.rulesIntentionSwitch.setOnCheckedChangeListener { _, checked ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repo.setIntentionPromptEnabled(checked) }
                    }
                }
                binding.rulesReminderSwitch.setOnCheckedChangeListener { _, checked ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repo.setReminderEnabled(checked) }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun renderRules(rules: List<RuleEntity>) {
        val ctx = requireContext()
        val container = binding.rulesContainer
        container.removeAllViews()
        if (rules.isEmpty()) {
            val emptyCard = buildCard(ctx)
            emptyCard.addView(TextView(ctx).apply {
                text = getString(R.string.mindstop_rules_empty_hint)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            })
            container.addView(emptyCard)
            return
        }

        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)

        for (entity in rules) {
            val rule = entity.toCore()
            val card = buildCard(ctx)
            card.setOnClickListener {
                startActivity(
                    Intent(ctx, MindstopRuleEditActivity::class.java)
                        .putExtra(MindstopRuleEditActivity.EXTRA_RULE_ID, rule.id)
                )
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
            }
            box.addView(TextView(ctx).apply {
                text = MindstopUi.ruleDisplayTitle(ctx, rule)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            })
            box.addView(TextView(ctx).apply {
                text = MindstopUi.ruleDetailLine(ctx, rule)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(box)
            container.addView(card)
        }
    }

    private fun renderExempt(exempt: Set<String>) {
        val b = _binding ?: return
        b.rulesExemptSummary.text = getString(R.string.mindstop_exempt_summary, exempt.size)
    }

    private fun buildCard(ctx: Context): MaterialCardView = MaterialCardView(ctx).apply {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        layoutParams = lp
        radius = dp(18).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLowest))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
