package app.mindstop.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.QuotaRule
import app.mindstop.core.RuleScope
import app.mindstop.service.QuotaRepository
import app.mindstop.service.toCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopRuleEditBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import java.util.UUID

/** Creates or edits one quota rule. */
class MindstopRuleEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RULE_ID = "app.mindstop.extra.RULE_ID"
    }

    private lateinit var binding: ActivityMindstopRuleEditBinding
    private lateinit var repo: QuotaRepository

    private var ruleId: String? = null
    private var sortOrder = 0
    private var packages: Set<String> = emptySet()
    private var loaded = false

    private val appsPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
        packages = apps.toSet()
        renderPackageCount()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopRuleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.ruleEditRoot)
        repo = QuotaRepository(this)
        ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        MindstopUi.setupToolbar(
            this,
            binding.ruleEditToolbar,
            if (ruleId == null) R.string.mindstop_title_rule_new else R.string.mindstop_title_rule_edit
        )

        binding.ruleScopeGroup.setOnCheckedChangeListener { _, _ -> updateScopeVisibility() }
        binding.rulePickApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(packages))
            }
            appsPicker.launch(intent)
        }
        binding.ruleSave.setOnClickListener { save() }
        binding.ruleDelete.setOnClickListener { delete() }

        if (ruleId == null) {
            binding.ruleDelete.visibility = View.GONE
            binding.ruleScopeApps.isChecked = true
            binding.ruleEnabled.isChecked = true
            updateScopeVisibility()
            renderPackageCount()
            loaded = true
        } else {
            lifecycleScope.launch {
                val entity = withContext(Dispatchers.IO) { repo.loadRuleEntity(ruleId!!) }
                if (entity == null) {
                    finish()
                    return@launch
                }
                val rule = entity.toCore()
                sortOrder = entity.sortOrder
                packages = rule.packages
                binding.ruleName.setText(rule.name)
                when (rule.scope) {
                    RuleScope.GLOBAL -> binding.ruleScopeGlobal.isChecked = true
                    else -> binding.ruleScopeApps.isChecked = true
                }
                binding.ruleDailyMinutes.setText(rule.dailyLimitMs?.let { (it / 60_000L).toString() } ?: "")
                binding.ruleSessionMinutes.setText(rule.sessionLimitMs?.let { (it / 60_000L).toString() } ?: "")
                binding.ruleEnabled.isChecked = rule.enabled
                updateScopeVisibility()
                renderPackageCount()
                loaded = true
            }
        }
    }

    private fun updateScopeVisibility() {
        val group = binding.ruleScopeApps.isChecked
        binding.rulePickApps.visibility = if (group) View.VISIBLE else View.GONE
        binding.ruleAppsCount.visibility = if (group) View.VISIBLE else View.GONE
    }

    private fun renderPackageCount() {
        if (packages.isEmpty()) {
            binding.ruleAppsCount.setText(R.string.mindstop_rule_apps_none)
            return
        }
        val labels = packages.map { pkg ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }.sorted()
        binding.ruleAppsCount.text = if (labels.size <= 5) {
            getString(R.string.mindstop_rule_apps_selected, labels.size, labels.joinToString("、"))
        } else {
            getString(
                R.string.mindstop_rule_apps_selected_more,
                labels.size,
                labels.take(5).joinToString("、"),
                labels.size - 5
            )
        }
    }

    private fun parseMinutes(text: String): Long? {
        val value = text.trim().toLongOrNull() ?: return null
        return if (value <= 0L) null else value * 60_000L
    }

    private fun save() {
        if (!loaded) return
        val isGroup = binding.ruleScopeApps.isChecked
        val daily = parseMinutes(binding.ruleDailyMinutes.text?.toString().orEmpty())
        val session = parseMinutes(binding.ruleSessionMinutes.text?.toString().orEmpty())
        if (daily == null && session == null) {
            Toast.makeText(this, R.string.mindstop_rule_need_limit, Toast.LENGTH_LONG).show()
            return
        }
        if (isGroup && packages.isEmpty()) {
            Toast.makeText(this, R.string.mindstop_rule_need_apps, Toast.LENGTH_LONG).show()
            return
        }
        val name = binding.ruleName.text?.toString()?.trim().orEmpty()
        val scope = if (isGroup) {
            if (packages.size == 1) RuleScope.APP else RuleScope.GROUP
        } else {
            RuleScope.GLOBAL
        }
        val id = ruleId ?: UUID.randomUUID().toString()
        val rule = QuotaRule(
            id = id,
            name = name,
            scope = scope,
            packages = if (isGroup) packages else emptySet(),
            dailyLimitMs = daily,
            sessionLimitMs = session,
            enabled = binding.ruleEnabled.isChecked
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val order = if (ruleId == null) repo.nextSortOrder() else sortOrder
                repo.saveRule(rule, order, System.currentTimeMillis())
            }
            finish()
        }
    }

    private fun delete() {
        val id = ruleId ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.deleteRule(id, System.currentTimeMillis()) }
            finish()
        }
    }
}
