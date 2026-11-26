package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindstopShoppingTest {

    @Test
    fun decisionTierRecommendationsAndSafetyOverrideWorkTransparently() {
        // 30 yuan low-impact item -> LOW_IMPACT (5-10m)
        assertEquals(DecisionTier.LOW_IMPACT, DecisionTierAdvisor.recommend(3_000L, 200_000L))
        // 30 yuan safety/compatibility critical -> elevated to DAILY_ESSENTIAL
        assertEquals(
            DecisionTier.DAILY_ESSENTIAL,
            DecisionTierAdvisor.recommend(3_000L, 200_000L, safetyOrCompatCritical = true)
        )
        // 150 yuan daily essential -> DAILY_ESSENTIAL (10-20m)
        assertEquals(DecisionTier.DAILY_ESSENTIAL, DecisionTierAdvisor.recommend(15_000L, 200_000L))
        // 500 yuan mid-value -> MID_VALUE (20-40m)
        assertEquals(DecisionTier.MID_VALUE, DecisionTierAdvisor.recommend(50_000L, 200_000L))
        // Durable laptop -> DURABLE_HIGH (multi-session 30-45m)
        val durable = DecisionTierAdvisor.recommend(500_000L, 200_000L, longTermDurable = true)
        assertEquals(DecisionTier.DURABLE_HIGH, durable)
        assertTrue(durable.multiSessionRecommended)
    }

    @Test
    fun moneyFormatParsesAndFormatsExactCentsWithoutFloatDrift() {
        assertEquals(1990L, MoneyFormat.parseYuanToCents("19.9"))
        assertEquals(1999L, MoneyFormat.parseYuanToCents("¥19.99"))
        assertEquals(5000L, MoneyFormat.parseYuanToCents("50"))
        assertEquals(123456L, MoneyFormat.parseYuanToCents("1,234.56"))
        assertNull(MoneyFormat.parseYuanToCents("-10"))
        assertNull(MoneyFormat.parseYuanToCents("abc"))
        assertEquals("19.90", MoneyFormat.formatYuan(1990L))
        assertEquals("0.05", MoneyFormat.formatYuan(5L))
    }

    @Test
    fun shareLinkParserExtractsUrlAndCleanTitle() {
        val raw = "【淘宝】冬季打字手套半指全指保暖 https://e.tb.cn/h.abc123 点击链接直接打开"
        val parsed = ShareLinkParser.parse(raw)
        assertEquals("https://e.tb.cn/h.abc123", parsed.url)
        assertTrue(parsed.title.contains("冬季打字手套"))
        assertFalse(parsed.title.contains("https://"))
    }

    @Test
    fun budgetCalculatorReflectsRefundsAndSeparatesActualSpending() {
        val settings = ShoppingBudgetSettings(monthlyTotalCents = 200_000L, monthlyDiscretionaryCents = 80_000L)
        val expenses = listOf(
            ExpenseItemInput(ExpenseCategory.DAILY, amountCents = 5_000L, refundCents = 0L),
            ExpenseItemInput(ExpenseCategory.CLOTHING, amountCents = 20_000L, refundCents = 20_000L), // full return
            ExpenseItemInput(ExpenseCategory.WORK_STUDY, amountCents = 12_000L, refundCents = 2_000L) // partial refund
        )
        val summary = BudgetCalculator.summarize(settings, expenses)
        assertEquals(15_000L, summary.totalSpentCents)
        assertEquals(185_000L, summary.remainingTotalCents)
        assertEquals(0L, summary.byCategoryCents[ExpenseCategory.CLOTHING])
        assertEquals(10_000L, summary.byCategoryCents[ExpenseCategory.WORK_STUDY])

        // Landed total calculation: price - discount + shipping
        assertEquals(4790L, BudgetCalculator.landedTotalCents(priceCents = 4990L, discountCents = 500L, shippingCents = 300L))
        // Budget codec round-trip
        assertEquals(settings, ShoppingBudgetSettings.decode(ShoppingBudgetSettings.encode(settings)))
    }
}
