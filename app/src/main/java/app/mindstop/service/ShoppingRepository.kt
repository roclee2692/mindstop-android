package app.mindstop.service

import android.content.Context
import app.mindstop.core.BacklogStatus
import app.mindstop.core.DayKeys
import app.mindstop.core.DecisionTier
import app.mindstop.core.DecisionTierAdvisor
import app.mindstop.core.ExpenseCategory
import app.mindstop.core.PresetCategoryId
import app.mindstop.core.PresetGroupScanner
import app.mindstop.core.ShoppingBudgetSettings
import app.mindstop.core.ShoppingTaskStatus
import app.mindstop.data.EventEntity
import app.mindstop.data.ExpenseRecordEntity
import app.mindstop.data.MetaEntity
import app.mindstop.data.MindstopDatabase
import app.mindstop.data.ProductCandidateEntity
import app.mindstop.data.PurchaseBacklogEntity
import app.mindstop.data.ShoppingTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * Local persistence for Module 3: Intentional Shopping (v0.3 SPEC §15–§26).
 * Manages shopping tasks, product candidate pool, 24h unplanned backlog, actual confirmed expenses
 * (strictly separate from candidate prices), and monthly budget settings.
 */
class ShoppingRepository(context: Context) {

    private val db = MindstopDatabase.getInstance(context)
    private val rewards = RewardRepository(context)

    // Shopping app recognition (§16)

    suspend fun isShoppingPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName in PresetGroupScanner.SHOPPING_PACKAGES) return true
        val shoppingRule = db.ruleDao().getById(PresetCategoryId.SHOPPING.ruleId)
        if (shoppingRule != null && packageName in shoppingRule.packages.split(',')) return true
        val custom = db.metaDao().get(KEY_SHOPPING_PACKAGES).orEmpty().split(',').filter { it.isNotBlank() }
        return packageName in custom
    }

    // Monthly budget settings (§25)

    suspend fun loadBudgetSettings(): ShoppingBudgetSettings =
        ShoppingBudgetSettings.decode(db.metaDao().get(KEY_SHOPPING_BUDGET))

    suspend fun saveBudgetSettings(settings: ShoppingBudgetSettings, nowWall: Long) {
        val encoded = ShoppingBudgetSettings.encode(settings)
        db.metaDao().put(MetaEntity(KEY_SHOPPING_BUDGET, encoded))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "shopping_budget_changed", detail = encoded))
    }

    // Shopping tasks (§17–§18, §23)

    fun observeTasks(): Flow<List<ShoppingTaskEntity>> = db.shoppingTaskDao().observeAll()

    suspend fun activeTasks(): List<ShoppingTaskEntity> = db.shoppingTaskDao().activeTasks()

    suspend fun getTask(id: Long): ShoppingTaskEntity? = db.shoppingTaskDao().byId(id)

    suspend fun createTask(
        itemName: String,
        purpose: String,
        budgetCents: Long,
        importance: Int = 2,
        urgency: Int = 2,
        mustHaves: String = "",
        niceToHaves: String = "",
        keywords: String = "",
        safetyOrCompatCritical: Boolean = false,
        longTermDurable: Boolean = false,
        nowWall: Long
    ): ShoppingTaskEntity {
        val budget = loadBudgetSettings()
        val tier = DecisionTierAdvisor.recommend(
            budgetCents = budgetCents,
            monthlyDiscretionaryCents = budget.monthlyDiscretionaryCents,
            safetyOrCompatCritical = safetyOrCompatCritical,
            longTermDurable = longTermDurable
        )
        val entity = ShoppingTaskEntity(
            itemName = itemName.trim(),
            purpose = purpose.trim(),
            budgetCents = budgetCents.coerceAtLeast(0L),
            importance = importance.coerceIn(1, 3),
            urgency = urgency.coerceIn(1, 3),
            mustHaves = mustHaves.trim(),
            niceToHaves = niceToHaves.trim(),
            keywords = keywords.trim().ifBlank { itemName.trim() },
            decisionTier = tier.code,
            recommendedMinutes = tier.defaultMinutes,
            researchMsTotal = 0L,
            status = ShoppingTaskStatus.RESEARCHING.code,
            unresolvedQuestions = "",
            decisionDateKey = "",
            cooldownHours = if (tier == DecisionTier.DURABLE_HIGH) 72 else 0,
            createdWall = nowWall,
            updatedWall = nowWall
        )
        val id = db.shoppingTaskDao().upsert(entity)
        db.eventDao().insert(
            EventEntity(wall = nowWall, type = "shopping_task_created", detail = "id=$id tier=${tier.code} budget=$budgetCents")
        )
        return entity.copy(id = id)
    }

    suspend fun saveTask(task: ShoppingTaskEntity) {
        db.shoppingTaskDao().upsert(task)
    }

    suspend fun finishTaskWithReward(
        taskId: Long,
        status: ShoppingTaskStatus,
        recordActualExpenseCents: Long? = null,
        expenseCategory: ExpenseCategory = ExpenseCategory.DAILY,
        nowWall: Long
    ): Boolean {
        val task = db.shoppingTaskDao().byId(taskId) ?: return false
        db.shoppingTaskDao().upsert(task.copy(status = status.code, updatedWall = nowWall))
        if (status == ShoppingTaskStatus.PURCHASED && recordActualExpenseCents != null && recordActualExpenseCents > 0L) {
            addExpense(
                category = expenseCategory,
                title = task.itemName,
                amountCents = recordActualExpenseCents,
                taskId = task.id,
                notes = task.purpose,
                nowWall = nowWall
            )
        }
        val granted = rewards.grantShoppingTaskReward(task.id, nowWall)
        db.eventDao().insert(
            EventEntity(wall = nowWall, type = "shopping_task_finished", detail = "id=$taskId status=${status.code} reward=${granted != null}")
        )
        return granted != null
    }

    suspend fun deleteTask(id: Long) = db.shoppingTaskDao().delete(id)

    // Product candidates (§19)

    fun observeCandidates(taskId: Long): Flow<List<ProductCandidateEntity>> =
        db.productCandidateDao().observeForTask(taskId)

    fun observeRecentCandidates(limit: Int = 40): Flow<List<ProductCandidateEntity>> =
        db.productCandidateDao().observeRecent(limit)

    suspend fun candidatesForTask(taskId: Long): List<ProductCandidateEntity> =
        db.productCandidateDao().forTask(taskId)

    suspend fun addCandidate(
        taskId: Long,
        name: String,
        url: String = "",
        brandModelSpec: String = "",
        comparisonGroupKey: String = "",
        quantityText: String = "1",
        unitCode: String = "PIECE",
        priceCents: Long,
        discountCents: Long = 0L,
        shippingCents: Long = 0L,
        meetsMustHaves: Boolean = true,
        evidenceNote: String = "",
        warrantyNote: String = "",
        notes: String = "",
        source: String = "manual",
        nowWall: Long
    ): ProductCandidateEntity {
        val entity = ProductCandidateEntity(
            taskId = taskId,
            name = name.trim(),
            url = url.trim(),
            brandModelSpec = brandModelSpec.trim(),
            comparisonGroupKey = comparisonGroupKey.trim(),
            quantityText = quantityText.trim().ifBlank { "1" },
            unitCode = unitCode.trim().ifBlank { "PIECE" },
            priceCents = priceCents.coerceAtLeast(0L),
            discountCents = discountCents.coerceAtLeast(0L),
            shippingCents = shippingCents.coerceAtLeast(0L),
            meetsMustHaves = meetsMustHaves,
            evidenceNote = evidenceNote.trim(),
            warrantyNote = warrantyNote.trim(),
            notes = notes.trim(),
            source = source,
            collectedWall = nowWall
        )
        val id = db.productCandidateDao().upsert(entity)
        db.eventDao().insert(EventEntity(wall = nowWall, type = "candidate_added", detail = "task=$taskId id=$id source=$source"))
        return entity.copy(id = id)
    }

    suspend fun deleteCandidate(id: Long) = db.productCandidateDao().delete(id)

    // Unplanned backlog / 24h cooling pool (§24)

    fun observeBacklog(): Flow<List<PurchaseBacklogEntity>> = db.purchaseBacklogDao().observeAll()

    suspend fun addBacklogItem(
        name: String,
        estimatedCents: Long,
        reasonNeeded: String,
        hasAlternative: Boolean,
        isUrgent: Boolean,
        url: String = "",
        cooldownHours: Int = 24,
        nowWall: Long
    ): Pair<PurchaseBacklogEntity, Boolean> {
        val effectiveHours = if (isUrgent) 0 else cooldownHours.coerceIn(0, 720)
        val entity = PurchaseBacklogEntity(
            name = name.trim(),
            estimatedCents = estimatedCents.coerceAtLeast(0L),
            reasonNeeded = reasonNeeded.trim(),
            hasAlternative = hasAlternative,
            isUrgent = isUrgent,
            url = url.trim(),
            cooldownHours = effectiveHours,
            cooldownEndsWall = nowWall + effectiveHours * 3_600_000L,
            status = if (isUrgent) BacklogStatus.CONFIRMED_NEED.code else BacklogStatus.COOLING.code,
            createdWall = nowWall,
            updatedWall = nowWall
        )
        val id = db.purchaseBacklogDao().upsert(entity)
        val rewarded = if (!isUrgent) rewards.grantShoppingBacklogReward(id, nowWall) != null else false
        db.eventDao().insert(
            EventEntity(wall = nowWall, type = "backlog_added", detail = "id=$id urgent=$isUrgent cool=${effectiveHours}h")
        )
        return entity.copy(id = id) to rewarded
    }

    suspend fun updateBacklogStatus(id: Long, status: BacklogStatus, nowWall: Long) {
        val cur = db.purchaseBacklogDao().byId(id) ?: return
        db.purchaseBacklogDao().upsert(cur.copy(status = status.code, updatedWall = nowWall))
    }

    suspend fun cancelBacklogCooldown(id: Long, nowWall: Long) {
        val cur = db.purchaseBacklogDao().byId(id) ?: return
        db.purchaseBacklogDao().upsert(cur.copy(cooldownEndsWall = nowWall, updatedWall = nowWall))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "backlog_cooldown_cancelled", detail = "id=$id"))
    }

    suspend fun deleteBacklogItem(id: Long) = db.purchaseBacklogDao().delete(id)

    // Actual expenses (§25 - strictly manual confirmation, never candidate prices)

    fun observeMonthExpenses(monthKey: String): Flow<List<ExpenseRecordEntity>> =
        db.expenseRecordDao().observeForMonth(monthKey)

    suspend fun addExpense(
        category: ExpenseCategory,
        title: String,
        amountCents: Long,
        taskId: Long? = null,
        notes: String = "",
        nowWall: Long
    ): ExpenseRecordEntity {
        val dayKey = DayKeys.dayKey(nowWall)
        val monthKey = dayKey.take(7)
        val entity = ExpenseRecordEntity(
            monthKey = monthKey,
            dayKey = dayKey,
            category = category.code,
            title = title.trim(),
            amountCents = amountCents.coerceAtLeast(0L),
            refundCents = 0L,
            taskId = taskId,
            notes = notes.trim(),
            createdWall = nowWall
        )
        val id = db.expenseRecordDao().upsert(entity)
        db.eventDao().insert(EventEntity(wall = nowWall, type = "expense_recorded", detail = "id=$id cat=${category.code} cents=$amountCents"))
        return entity.copy(id = id)
    }

    suspend fun recordRefund(expenseId: Long, refundCents: Long, nowWall: Long) {
        db.expenseRecordDao().updateRefund(expenseId, refundCents.coerceAtLeast(0L))
        db.eventDao().insert(EventEntity(wall = nowWall, type = "expense_refunded", detail = "id=$expenseId refund=$refundCents"))
    }

    suspend fun deleteExpense(id: Long) = db.expenseRecordDao().delete(id)

    companion object {
        const val KEY_SHOPPING_BUDGET = "shopping_budget"
        const val KEY_SHOPPING_PACKAGES = "shopping_packages"
    }
}
