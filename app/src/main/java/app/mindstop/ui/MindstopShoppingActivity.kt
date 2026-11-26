package app.mindstop.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.BacklogStatus
import app.mindstop.core.BudgetCalculator
import app.mindstop.core.DayKeys
import app.mindstop.core.DecisionTier
import app.mindstop.core.ExpenseCategory
import app.mindstop.core.ExpenseItemInput
import app.mindstop.core.MoneyFormat
import app.mindstop.core.ShoppingBudgetSettings
import app.mindstop.core.ShoppingTaskStatus
import app.mindstop.data.ExpenseRecordEntity
import app.mindstop.data.ProductCandidateEntity
import app.mindstop.data.PurchaseBacklogEntity
import app.mindstop.data.ShoppingTaskEntity
import app.mindstop.service.ShoppingRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopShoppingBinding

/**
 * Intentional Shopping Hub (v0.3 SPEC §15–§19, §24–§26).
 * Highlights purchase tasks, product candidates, 24h unplanned backlog, and actual confirmed
 * spending (strictly separated from candidate prices).
 */
class MindstopShoppingActivity : AppCompatActivity() {

    private enum class Tab { TASKS, CANDIDATES, BACKLOG, BUDGET }

    private lateinit var binding: ActivityMindstopShoppingBinding
    private lateinit var repo: ShoppingRepository

    private var currentTab = Tab.TASKS
    private var tasks: List<ShoppingTaskEntity> = emptyList()
    private var candidates: List<ProductCandidateEntity> = emptyList()
    private var backlog: List<PurchaseBacklogEntity> = emptyList()
    private var monthExpenses: List<ExpenseRecordEntity> = emptyList()
    private var budgetSettings = ShoppingBudgetSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopShoppingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.shoppingRoot)
        MindstopUi.setupToolbar(this, binding.shoppingToolbar, R.string.mindstop_shop_title)

        repo = ShoppingRepository(this)
        val monthKey = DayKeys.dayKey(System.currentTimeMillis()).take(7)

        binding.shoppingTabGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentTab = when (checkedIds.firstOrNull()) {
                R.id.shopping_tab_candidates -> Tab.CANDIDATES
                R.id.shopping_tab_backlog -> Tab.BACKLOG
                R.id.shopping_tab_budget -> Tab.BUDGET
                else -> Tab.TASKS
            }
            renderCurrentTab()
        }
        binding.shoppingPrimaryAction.setOnClickListener {
            when (currentTab) {
                Tab.TASKS -> openAddTaskDialog()
                Tab.CANDIDATES -> openAddCandidateDialog()
                Tab.BACKLOG -> openAddBacklogDialog()
                Tab.BUDGET -> openAddExpenseDialog()
            }
        }
        binding.shoppingEditBudgetBtn.setOnClickListener { openEditBudgetDialog() }

        lifecycleScope.launch {
            budgetSettings = withContext(Dispatchers.IO) { repo.loadBudgetSettings() }
            renderCurrentTab()
        }
        lifecycleScope.launch {
            repo.observeTasks().collectLatest {
                tasks = it
                if (currentTab == Tab.TASKS) renderCurrentTab()
            }
        }
        lifecycleScope.launch {
            repo.observeRecentCandidates(50).collectLatest {
                candidates = it
                if (currentTab == Tab.CANDIDATES) renderCurrentTab()
            }
        }
        lifecycleScope.launch {
            repo.observeBacklog().collectLatest {
                backlog = it
                if (currentTab == Tab.BACKLOG) renderCurrentTab()
            }
        }
        lifecycleScope.launch {
            repo.observeMonthExpenses(monthKey).collectLatest {
                monthExpenses = it
                if (currentTab == Tab.BUDGET) renderCurrentTab()
            }
        }
    }

    private fun renderCurrentTab() {
        binding.shoppingBudgetCard.visibility = if (currentTab == Tab.BUDGET) View.VISIBLE else View.GONE
        binding.shoppingPrimaryAction.setText(
            when (currentTab) {
                Tab.TASKS -> R.string.mindstop_shop_add_task
                Tab.CANDIDATES -> R.string.mindstop_shop_add_candidate
                Tab.BACKLOG -> R.string.mindstop_shop_add_backlog
                Tab.BUDGET -> R.string.mindstop_shop_add_expense
            }
        )
        when (currentTab) {
            Tab.TASKS -> renderTasks()
            Tab.CANDIDATES -> renderCandidates()
            Tab.BACKLOG -> renderBacklog()
            Tab.BUDGET -> renderBudget()
        }
    }

    private fun renderTasks() {
        val container = binding.shoppingListContainer
        container.removeAllViews()
        if (tasks.isEmpty()) {
            addEmptyHint(R.string.mindstop_shop_tasks_empty)
            return
        }
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (task in tasks) {
            val card = buildCard()
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(12))
            }
            val tier = DecisionTier.fromCode(task.decisionTier)
            col.addView(TextView(this).apply {
                text = getString(
                    R.string.mindstop_shop_task_header,
                    task.itemName,
                    MoneyFormat.formatYuan(task.budgetCents),
                    taskStatusLabel(ShoppingTaskStatus.fromCode(task.status))
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            })
            val detail = buildString {
                append(getString(R.string.mindstop_shop_task_tier_meta, tier.minMinutes, tier.maxMinutes))
                if (task.mustHaves.isNotBlank()) append("\n").append(getString(R.string.mindstop_shop_must_prefix, task.mustHaves))
                if (task.keywords.isNotBlank()) append("\n").append(getString(R.string.mindstop_overlay_keywords, task.keywords))
            }
            col.addView(TextView(this).apply {
                text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onVariant)
                setPadding(0, dp(4), 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(outlinedBtn(R.string.mindstop_shop_analyze_btn) {
                startActivity(
                    android.content.Intent(this, MindstopTaskDetailActivity::class.java)
                        .putExtra(MindstopTaskDetailActivity.EXTRA_TASK_ID, task.id)
                )
            })
            if (task.keywords.isNotBlank()) {
                row.addView(outlinedBtn(R.string.mindstop_intention_copy, marginStartDp = 8) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Mindstop", task.keywords))
                    Toast.makeText(this, R.string.mindstop_copied, Toast.LENGTH_SHORT).show()
                })
            }
            if (task.status == ShoppingTaskStatus.RESEARCHING.code) {
                row.addView(outlinedBtn(R.string.mindstop_shop_decide_btn, marginStartDp = 8) {
                    openFinishTaskDialog(task)
                })
            }
            col.addView(row)
            card.addView(col)
            container.addView(card)
        }
    }

    private fun renderCandidates() {
        val container = binding.shoppingListContainer
        container.removeAllViews()
        if (candidates.isEmpty()) {
            addEmptyHint(R.string.mindstop_shop_candidates_empty)
            return
        }
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (c in candidates) {
            val card = buildCard()
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(12))
            }
            val landed = BudgetCalculator.landedTotalCents(c.priceCents, c.discountCents, c.shippingCents)
            col.addView(TextView(this).apply {
                text = getString(R.string.mindstop_shop_candidate_header, c.name, MoneyFormat.formatYuan(landed))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            })
            val mustTag = if (c.meetsMustHaves) getString(R.string.mindstop_shop_must_ok) else getString(R.string.mindstop_shop_must_no)
            val meta = getString(
                R.string.mindstop_shop_candidate_meta,
                c.brandModelSpec.ifBlank { getString(R.string.mindstop_stats_na) },
                c.quantityText,
                c.unitCode,
                mustTag
            )
            col.addView(TextView(this).apply {
                text = meta
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onVariant)
                setPadding(0, dp(4), 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(outlinedBtn(R.string.mindstop_rule_delete) {
                lifecycleScope.launch(Dispatchers.IO) { repo.deleteCandidate(c.id) }
            })
            col.addView(row)
            card.addView(col)
            container.addView(card)
        }
    }

    private fun renderBacklog() {
        val container = binding.shoppingListContainer
        container.removeAllViews()
        if (backlog.isEmpty()) {
            addEmptyHint(R.string.mindstop_shop_backlog_empty)
            return
        }
        val now = System.currentTimeMillis()
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (item in backlog) {
            val card = buildCard()
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(12))
            }
            val leftMs = (item.cooldownEndsWall - now).coerceAtLeast(0L)
            val leftHours = ((leftMs + 3_599_999L) / 3_600_000L).toInt()
            val statusStr = if (item.status == BacklogStatus.COOLING.code && leftMs > 0L) {
                getString(R.string.mindstop_shop_backlog_cooling_hours, leftHours)
            } else {
                backlogStatusLabel(BacklogStatus.fromCode(item.status))
            }
            col.addView(TextView(this).apply {
                text = getString(
                    R.string.mindstop_shop_task_header,
                    item.name,
                    MoneyFormat.formatYuan(item.estimatedCents),
                    statusStr
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            })
            if (item.reasonNeeded.isNotBlank()) {
                col.addView(TextView(this).apply {
                    text = item.reasonNeeded
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(onVariant)
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (item.status == BacklogStatus.COOLING.code && leftMs > 0L) {
                row.addView(outlinedBtn(R.string.mindstop_shop_cancel_cool_btn) {
                    lifecycleScope.launch(Dispatchers.IO) { repo.cancelBacklogCooldown(item.id, System.currentTimeMillis()) }
                })
            } else {
                row.addView(outlinedBtn(R.string.mindstop_shop_backlog_need_btn) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        repo.updateBacklogStatus(item.id, BacklogStatus.CONFIRMED_NEED, System.currentTimeMillis())
                    }
                })
            }
            row.addView(outlinedBtn(R.string.mindstop_shop_backlog_drop_btn, marginStartDp = 8) {
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.updateBacklogStatus(item.id, BacklogStatus.DROPPED, System.currentTimeMillis())
                }
            })
            col.addView(row)
            card.addView(col)
            container.addView(card)
        }
    }

    private fun renderBudget() {
        val summary = BudgetCalculator.summarize(
            budgetSettings,
            monthExpenses.map {
                ExpenseItemInput(ExpenseCategory.fromCode(it.category), it.amountCents, it.refundCents)
            }
        )
        binding.shoppingBudgetSummary.text = getString(
            R.string.mindstop_shop_budget_summary_lines,
            MoneyFormat.formatYuan(summary.totalSpentCents),
            MoneyFormat.formatYuan(summary.totalBudgetCents),
            MoneyFormat.formatYuan(summary.remainingTotalCents),
            MoneyFormat.formatYuan(summary.discretionaryBudgetCents)
        )
        val container = binding.shoppingListContainer
        container.removeAllViews()
        if (monthExpenses.isEmpty()) {
            addEmptyHint(R.string.mindstop_shop_expenses_empty)
            return
        }
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (e in monthExpenses) {
            val card = buildCard()
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(12))
            }
            val net = (e.amountCents - e.refundCents).coerceAtLeast(0L)
            col.addView(TextView(this).apply {
                text = getString(
                    R.string.mindstop_shop_expense_header,
                    e.title,
                    MoneyFormat.formatYuan(net),
                    categoryLabel(ExpenseCategory.fromCode(e.category))
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface)
            })
            val sub = if (e.refundCents > 0L) {
                getString(R.string.mindstop_shop_expense_refunded_meta, e.dayKey, MoneyFormat.formatYuan(e.amountCents), MoneyFormat.formatYuan(e.refundCents))
            } else {
                e.dayKey
            }
            col.addView(TextView(this).apply {
                text = sub
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onVariant)
                setPadding(0, dp(4), 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(outlinedBtn(R.string.mindstop_shop_refund_btn) { openRefundDialog(e) })
            row.addView(outlinedBtn(R.string.mindstop_rule_delete, marginStartDp = 8) {
                lifecycleScope.launch(Dispatchers.IO) { repo.deleteExpense(e.id) }
            })
            col.addView(row)
            card.addView(col)
            container.addView(card)
        }
    }

    private fun openAddTaskDialog() {
        val box = formBox()
        val nameInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_item_name_hint) }
        val purposeInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_purpose_hint) }
        val budgetInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_budget_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val mustInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_must_haves_hint) }
        val kwInput = EditText(this).apply { hint = getString(R.string.mindstop_intention_keywords_hint) }
        val durableSwitch = MaterialSwitch(this).apply { setText(R.string.mindstop_shop_durable_switch) }
        listOf(nameInput, purposeInput, budgetInput, mustInput, kwInput, durableSwitch).forEach(box::addView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_shop_add_task)
            .setView(box)
            .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                val budget = MoneyFormat.parseYuanToCents(budgetInput.text?.toString()) ?: 0L
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.createTask(
                        itemName = name,
                        purpose = purposeInput.text?.toString().orEmpty(),
                        budgetCents = budget,
                        mustHaves = mustInput.text?.toString().orEmpty(),
                        keywords = kwInput.text?.toString().orEmpty(),
                        longTermDurable = durableSwitch.isChecked,
                        nowWall = System.currentTimeMillis()
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openFinishTaskDialog(task: ShoppingTaskEntity) {
        val box = formBox()
        val actualInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_actual_spend_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (task.budgetCents > 0L) setText(MoneyFormat.formatYuan(task.budgetCents))
        }
        val cats = ExpenseCategory.entries
        val catSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MindstopShoppingActivity, android.R.layout.simple_spinner_dropdown_item, cats.map { categoryLabel(it) })
        }
        box.addView(actualInput)
        box.addView(catSpinner)

        MaterialAlertDialogBuilder(this)
            .setTitle(task.itemName)
            .setMessage(R.string.mindstop_shop_decide_msg)
            .setView(box)
            .setPositiveButton(R.string.mindstop_shop_confirm_buy_btn) { _, _ ->
                val cents = MoneyFormat.parseYuanToCents(actualInput.text?.toString())
                val cat = cats[catSpinner.selectedItemPosition]
                lifecycleScope.launch {
                    val rewarded = withContext(Dispatchers.IO) {
                        repo.finishTaskWithReward(task.id, ShoppingTaskStatus.PURCHASED, cents, cat, System.currentTimeMillis())
                    }
                    if (rewarded) Toast.makeText(this@MindstopShoppingActivity, R.string.mindstop_shop_task_reward_toast, Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(R.string.mindstop_shop_drop_buy_btn) { _, _ ->
                lifecycleScope.launch {
                    val rewarded = withContext(Dispatchers.IO) {
                        repo.finishTaskWithReward(task.id, ShoppingTaskStatus.DROPPED, null, ExpenseCategory.OTHER, System.currentTimeMillis())
                    }
                    if (rewarded) Toast.makeText(this@MindstopShoppingActivity, R.string.mindstop_shop_task_reward_toast, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAddCandidateDialog() {
        val box = formBox()
        val nameInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_candidate_name_hint) }
        val specInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_spec_hint) }
        val groupInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_group_hint) }
        val qtyInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_qty_hint)
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val unitCodes = listOf("PIECE", "G", "KG", "ML", "L", "CM", "M")
        val unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MindstopShoppingActivity, android.R.layout.simple_spinner_dropdown_item, unitCodes)
        }
        val priceInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_price_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val discountInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_discount_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val shippingInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_shipping_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val mustSwitch = MaterialSwitch(this).apply {
            setText(R.string.mindstop_shop_must_switch)
            isChecked = true
        }
        listOf(nameInput, specInput, groupInput, qtyInput, unitSpinner, priceInput, discountInput, shippingInput, mustSwitch)
            .forEach(box::addView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_shop_add_candidate)
            .setView(box)
            .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val price = MoneyFormat.parseYuanToCents(priceInput.text?.toString()) ?: 0L
                if (name.isBlank()) return@setPositiveButton
                val discount = MoneyFormat.parseYuanToCents(discountInput.text?.toString()) ?: 0L
                val shipping = MoneyFormat.parseYuanToCents(shippingInput.text?.toString()) ?: 0L
                lifecycleScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val task = repo.activeTasks().firstOrNull()
                        ?: repo.createTask(itemName = name, purpose = "", budgetCents = price, nowWall = now)
                    repo.addCandidate(
                        taskId = task.id,
                        name = name,
                        brandModelSpec = specInput.text?.toString().orEmpty(),
                        comparisonGroupKey = groupInput.text?.toString().orEmpty().ifBlank { task.itemName },
                        quantityText = qtyInput.text?.toString().orEmpty().ifBlank { "1" },
                        unitCode = unitCodes[unitSpinner.selectedItemPosition],
                        priceCents = price,
                        discountCents = discount,
                        shippingCents = shipping,
                        meetsMustHaves = mustSwitch.isChecked,
                        source = "manual",
                        nowWall = now
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAddBacklogDialog() {
        val box = formBox()
        val nameInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_item_name_hint) }
        val estInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_price_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val reasonInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_reason_hint) }
        val coolInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_cool_hours_hint)
            setText("24")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val altSwitch = MaterialSwitch(this).apply { setText(R.string.mindstop_shop_has_alt_switch) }
        val urgentSwitch = MaterialSwitch(this).apply { setText(R.string.mindstop_shop_backlog_urgent_switch) }
        listOf(nameInput, estInput, reasonInput, coolInput, altSwitch, urgentSwitch).forEach(box::addView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_shop_add_backlog)
            .setView(box)
            .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                val cents = MoneyFormat.parseYuanToCents(estInput.text?.toString()) ?: 0L
                val hours = coolInput.text?.toString()?.trim()?.toIntOrNull() ?: 24
                lifecycleScope.launch {
                    val (_, rewarded) = withContext(Dispatchers.IO) {
                        repo.addBacklogItem(
                            name = name,
                            estimatedCents = cents,
                            reasonNeeded = reasonInput.text?.toString().orEmpty(),
                            hasAlternative = altSwitch.isChecked,
                            isUrgent = urgentSwitch.isChecked,
                            cooldownHours = hours,
                            nowWall = System.currentTimeMillis()
                        )
                    }
                    val msg = if (rewarded) R.string.mindstop_shop_backlog_saved_reward else R.string.mindstop_shop_backlog_saved
                    Toast.makeText(this@MindstopShoppingActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAddExpenseDialog() {
        val box = formBox()
        val titleInput = EditText(this).apply { hint = getString(R.string.mindstop_shop_expense_title_hint) }
        val amountInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_actual_spend_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val cats = ExpenseCategory.entries
        val catSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MindstopShoppingActivity, android.R.layout.simple_spinner_dropdown_item, cats.map { categoryLabel(it) })
        }
        listOf(titleInput, amountInput, catSpinner).forEach(box::addView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_shop_add_expense)
            .setView(box)
            .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                val title = titleInput.text?.toString()?.trim().orEmpty()
                val cents = MoneyFormat.parseYuanToCents(amountInput.text?.toString())
                if (title.isBlank() || cents == null || cents <= 0L) return@setPositiveButton
                val cat = cats[catSpinner.selectedItemPosition]
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.addExpense(cat, title, cents, nowWall = System.currentTimeMillis())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openRefundDialog(expense: ExpenseRecordEntity) {
        val input = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_refund_hint)
            setText(MoneyFormat.formatYuan(expense.amountCents))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_shop_refund_btn)
            .setView(input)
            .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                val cents = MoneyFormat.parseYuanToCents(input.text?.toString()) ?: return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.recordRefund(expense.id, cents.coerceAtMost(expense.amountCents), System.currentTimeMillis())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openEditBudgetDialog() {
        val box = formBox()
        val totalInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_total_budget_hint)
            setText(MoneyFormat.formatYuan(budgetSettings.monthlyTotalCents))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val freeInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_free_budget_hint)
            setText(MoneyFormat.formatYuan(budgetSettings.monthlyDiscretionaryCents))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val hrInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_hourly_value_hint)
            setText(MoneyFormat.formatYuan(budgetSettings.hourlyTimeValueCents))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        listOf(totalInput, freeInput, hrInput).forEach(box::addView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_shop_edit_budget_btn)
            .setView(box)
            .setPositiveButton(R.string.mindstop_rule_save) { _, _ ->
                val total = MoneyFormat.parseYuanToCents(totalInput.text?.toString()) ?: budgetSettings.monthlyTotalCents
                val free = MoneyFormat.parseYuanToCents(freeInput.text?.toString()) ?: budgetSettings.monthlyDiscretionaryCents
                val hr = MoneyFormat.parseYuanToCents(hrInput.text?.toString()) ?: budgetSettings.hourlyTimeValueCents
                val updated = ShoppingBudgetSettings(total, free, hr)
                budgetSettings = updated
                lifecycleScope.launch(Dispatchers.IO) { repo.saveBudgetSettings(updated, System.currentTimeMillis()) }
                renderBudget()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(4))
    }

    private fun buildCard(): MaterialCardView = MaterialCardView(this).apply {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        layoutParams = lp
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
    }

    private fun outlinedBtn(labelRes: Int, marginStartDp: Int = 0, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(labelRes)
            if (marginStartDp > 0) {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(marginStartDp)
                }
            }
            setOnClickListener { onClick() }
        }

    private fun addEmptyHint(resId: Int) {
        val onVariant = MaterialColors.getColor(binding.shoppingListContainer, com.google.android.material.R.attr.colorOnSurfaceVariant)
        binding.shoppingListContainer.addView(TextView(this).apply {
            setText(resId)
            setTextColor(onVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
    }

    private fun taskStatusLabel(status: ShoppingTaskStatus): String = when (status) {
        ShoppingTaskStatus.RESEARCHING -> getString(R.string.mindstop_shop_status_researching)
        ShoppingTaskStatus.PURCHASED -> getString(R.string.mindstop_shop_status_purchased)
        ShoppingTaskStatus.DROPPED -> getString(R.string.mindstop_shop_status_dropped)
        ShoppingTaskStatus.DEFERRED -> getString(R.string.mindstop_shop_status_deferred)
    }

    private fun backlogStatusLabel(status: BacklogStatus): String = when (status) {
        BacklogStatus.COOLING -> getString(R.string.mindstop_shop_backlog_ready)
        BacklogStatus.CONFIRMED_NEED -> getString(R.string.mindstop_shop_backlog_need)
        BacklogStatus.LATER -> getString(R.string.mindstop_shop_backlog_later)
        BacklogStatus.DROPPED -> getString(R.string.mindstop_shop_backlog_dropped)
    }

    private fun categoryLabel(category: ExpenseCategory): String = when (category) {
        ExpenseCategory.FOOD -> getString(R.string.mindstop_shop_cat_food)
        ExpenseCategory.DAILY -> getString(R.string.mindstop_shop_cat_daily)
        ExpenseCategory.CLOTHING -> getString(R.string.mindstop_shop_cat_clothing)
        ExpenseCategory.WORK_STUDY -> getString(R.string.mindstop_shop_cat_work_study)
        ExpenseCategory.DURABLE -> getString(R.string.mindstop_shop_cat_durable)
        ExpenseCategory.HOBBY -> getString(R.string.mindstop_shop_cat_hobby)
        ExpenseCategory.OTHER -> getString(R.string.mindstop_shop_cat_other)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
