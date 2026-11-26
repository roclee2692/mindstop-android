package app.mindstop.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.PresetCategoryId
import app.mindstop.core.PresetGroupScanner
import app.mindstop.core.QuotaRule
import app.mindstop.core.RuleScope
import app.mindstop.core.ScannedPresetGroup
import app.mindstop.service.InstalledAppScanner
import app.mindstop.service.QuotaRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopPresetGroupsBinding

/**
 * Automatically scans domestic and international apps on this device into 6 behavioral-addiction
 * preset groups so users can enable shared-quota groups in one tap instead of picking apps one by one.
 */
class MindstopPresetGroupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopPresetGroupsBinding
    private lateinit var repo: QuotaRepository

    private var scannedGroups: List<ScannedPresetGroup> = emptyList()
    private var existingRulesById: Map<String, QuotaRule> = emptyMap()
    private val selectedPresets = LinkedHashSet<PresetCategoryId>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopPresetGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.presetRoot)
        MindstopUi.setupToolbar(this, binding.presetToolbar, R.string.mindstop_preset_title)
        repo = QuotaRepository(this)

        binding.presetApplyButton.setOnClickListener { applySelected() }
        loadAndScan()
    }

    private fun loadAndScan() {
        lifecycleScope.launch {
            val (groups, existingById, hasAnyCustomGroup) = withContext(Dispatchers.IO) {
                val rules = repo.loadRules()
                val exempt = repo.loadExempt()
                val installed = InstalledAppScanner.scanInstalled(this@MindstopPresetGroupsActivity)
                val scanned = PresetGroupScanner.scan(installed, exempt, packageName)
                val byId = rules.associateBy { it.id }
                val hasGroup = rules.any { it.scope != RuleScope.GLOBAL }
                Triple(scanned, byId, hasGroup)
            }
            scannedGroups = groups
            existingRulesById = existingById
            selectedPresets.clear()
            for (g in groups) {
                if (g.matchedApps.isEmpty()) continue
                val alreadyActive = existingById[g.preset.ruleId]?.enabled == true
                if (alreadyActive || (!hasAnyCustomGroup && g.defaultSelected)) {
                    selectedPresets.add(g.preset)
                }
            }
            binding.presetScanningHint.visibility = View.GONE
            renderGroups()
            updateApplyButton()
        }
    }

    private fun renderGroups() {
        val container = binding.presetGroupsContainer
        container.removeAllViews()

        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = MaterialColors.getColor(container, androidx.appcompat.R.attr.colorPrimary)
        val surfaceCard = MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurfaceContainerLowest)
        val outline = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOutlineVariant)

        for (group in scannedGroups) {
            val hasApps = group.matchedApps.isNotEmpty()
            val alreadyActive = existingRulesById[group.preset.ruleId]?.enabled == true
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setCardBackgroundColor(surfaceCard)
                strokeColor = outline
                alpha = if (hasApps) 1f else 0.62f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(14), dp(14), dp(16), dp(14))
            }

            val checkBox = CheckBox(this).apply {
                isEnabled = hasApps
                isChecked = group.preset in selectedPresets
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPresets.add(group.preset) else selectedPresets.remove(group.preset)
                    updateApplyButton()
                }
            }
            if (hasApps) {
                card.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(6), dp(2), 0, 0)
            }

            textCol.addView(TextView(this).apply {
                setText(titleRes(group.preset))
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
            })

            textCol.addView(TextView(this).apply {
                setText(descRes(group.preset))
                setTextColor(onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            })

            textCol.addView(TextView(this).apply {
                text = getString(
                    R.string.mindstop_preset_quota_hint,
                    group.preset.defaultSessionMinutes,
                    group.preset.defaultDailyMinutes
                )
                setTextColor(primary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, 0)
            })

            val foundText = when {
                !hasApps -> getString(R.string.mindstop_preset_none_found)
                group.matchedApps.size <= 6 -> getString(
                    R.string.mindstop_preset_found_apps,
                    group.matchedApps.size,
                    group.matchedApps.joinToString("、") { it.label }
                )
                else -> getString(
                    R.string.mindstop_preset_found_apps_more,
                    group.matchedApps.size,
                    group.matchedApps.take(6).joinToString("、") { it.label },
                    group.matchedApps.size - 6
                )
            }
            textCol.addView(TextView(this).apply {
                text = foundText
                setTextColor(if (hasApps) onSurface else onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(6), 0, 0)
            })

            if (alreadyActive) {
                textCol.addView(TextView(this).apply {
                    setText(R.string.mindstop_preset_already_active)
                    setTextColor(primary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(4), 0, 0)
                })
            }

            root.addView(checkBox)
            root.addView(textCol)
            card.addView(root)
            container.addView(card)
        }
    }

    private fun updateApplyButton() {
        val count = selectedPresets.size
        binding.presetApplyButton.isEnabled = count > 0
        binding.presetApplyButton.text = getString(R.string.mindstop_preset_apply_btn, count)
    }

    private fun applySelected() {
        val toApply = scannedGroups.filter { it.preset in selectedPresets && it.matchedApps.isNotEmpty() }
        if (toApply.isEmpty()) {
            Toast.makeText(this, R.string.mindstop_preset_apply_empty, Toast.LENGTH_SHORT).show()
            return
        }
        binding.presetApplyButton.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                for (group in toApply) {
                    val existing = repo.loadRuleEntity(group.preset.ruleId)
                    val existingCore = existing?.let {
                        QuotaRule(
                            id = it.id,
                            name = it.name,
                            scope = app.mindstop.core.RuleScope.valueOf(it.scope),
                            packages = it.packages.split(',').filter { p -> p.isNotBlank() }.toSet(),
                            dailyLimitMs = it.dailyLimitMs,
                            sessionLimitMs = it.sessionLimitMs,
                            enabled = it.enabled
                        )
                    }
                    val packages = group.matchedApps.map { it.packageName }.toSet()
                    val rule = PresetGroupScanner.buildRule(
                        preset = group.preset,
                        packages = packages,
                        localizedName = getString(titleRes(group.preset)),
                        existing = existingCore
                    )
                    val order = existing?.sortOrder ?: repo.nextSortOrder()
                    repo.saveRule(rule, order, now)
                }
            }
            Toast.makeText(
                this@MindstopPresetGroupsActivity,
                getString(R.string.mindstop_preset_applied_toast, toApply.size),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    private fun titleRes(preset: PresetCategoryId): Int = when (preset) {
        PresetCategoryId.SHORT_VIDEO -> R.string.mindstop_preset_video_title
        PresetCategoryId.SOCIAL_FEED -> R.string.mindstop_preset_social_title
        PresetCategoryId.GAMES -> R.string.mindstop_preset_games_title
        PresetCategoryId.READING_NEWS -> R.string.mindstop_preset_reading_title
        PresetCategoryId.SHOPPING -> R.string.mindstop_preset_shopping_title
        PresetCategoryId.MESSAGING_OPT_IN -> R.string.mindstop_preset_messaging_title
    }

    private fun descRes(preset: PresetCategoryId): Int = when (preset) {
        PresetCategoryId.SHORT_VIDEO -> R.string.mindstop_preset_video_desc
        PresetCategoryId.SOCIAL_FEED -> R.string.mindstop_preset_social_desc
        PresetCategoryId.GAMES -> R.string.mindstop_preset_games_desc
        PresetCategoryId.READING_NEWS -> R.string.mindstop_preset_reading_desc
        PresetCategoryId.SHOPPING -> R.string.mindstop_preset_shopping_desc
        PresetCategoryId.MESSAGING_OPT_IN -> R.string.mindstop_preset_messaging_desc
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
