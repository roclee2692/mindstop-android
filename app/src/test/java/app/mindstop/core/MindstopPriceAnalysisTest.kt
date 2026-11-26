package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MindstopPriceAnalysisTest {

    @Test
    fun unitPriceNormalizationMatchesSpecSocksAndMassExamples() {
        val now = 1_760_000_000_000L
        // Spec §20 example: 3 pairs for 19.90 vs 5 pairs for 29.90
        val socks3 = CandidatePriceInput(1L, "Socks 3-pack", "cotton-socks", "3", "PIECE", 1990L, 0L, 0L, true, "manual", now)
        val socks5 = CandidatePriceInput(2L, "Socks 5-pack", "cotton-socks", "5", "PIECE", 2990L, 0L, 0L, true, "manual", now)

        val dist = PriceAnalysisEngine.analyzeGroup(listOf(socks3, socks5), "cotton-socks", UnitDimension.COUNT)
        assertNotNull(dist)
        dist!!
        assertEquals(2, dist.sampleCount)
        assertTrue(dist.sampleUnstable) // < 4 samples must flag unstable
        assertEquals(2L, dist.offersSorted.first().input.id) // 5-pack is cheaper per pair (5.98 vs 6.6333)
        assertEquals(BigDecimal("5.9800"), dist.minUnitPrice)
        assertEquals(BigDecimal("6.6333"), dist.maxUnitPrice)

        // Mass conversion: 500g for 25.00 (0.05/g) vs 1kg for 40.00 (0.04/g)
        val coffee500g = CandidatePriceInput(3L, "Beans 500g", "beans", "500", "G", 2500L, 0L, 0L, true, "manual", now)
        val coffee1kg = CandidatePriceInput(4L, "Beans 1kg", "beans", "1", "KG", 4000L, 0L, 0L, true, "manual", now)
        val massDist = PriceAnalysisEngine.analyzeGroup(listOf(coffee500g, coffee1kg), "beans", UnitDimension.MASS)!!
        assertEquals(BigDecimal("0.0400"), massDist.minUnitPrice)
        assertEquals(BigDecimal("0.0500"), massDist.maxUnitPrice)
    }

    @Test
    fun incompatibleSpecsDimensionsAndUnqualifiedItemsAreNeverMixed() {
        val now = 1_760_000_000_000L
        val list = listOf(
            CandidatePriceInput(1L, "Glove A", "typing-gloves", "1", "PIECE", 3000L, 0L, 0L, true, "manual", now),
            CandidatePriceInput(2L, "Glove B", "typing-gloves", "1", "PIECE", 4000L, 0L, 0L, true, "manual", now),
            CandidatePriceInput(3L, "Glove C", "typing-gloves", "1", "PIECE", 5000L, 0L, 0L, true, "manual", now),
            CandidatePriceInput(4L, "Glove D", "typing-gloves", "1", "PIECE", 6000L, 0L, 0L, true, "manual", now),
            // Incompatible unit dimension (MASS instead of COUNT)
            CandidatePriceInput(5L, "Wrong Unit", "typing-gloves", "200", "G", 2000L, 0L, 0L, true, "manual", now),
            // Different spec group
            CandidatePriceInput(6L, "Heated Glove", "heated-gloves", "1", "PIECE", 12000L, 0L, 0L, true, "manual", now),
            // Fails must-have requirement
            CandidatePriceInput(7L, "Half Finger", "typing-gloves", "1", "PIECE", 1500L, 0L, 0L, false, "manual", now)
        )
        val dist = PriceAnalysisEngine.analyzeGroup(list, "typing-gloves", UnitDimension.COUNT)!!
        assertEquals(4, dist.sampleCount)
        assertFalse(dist.sampleUnstable) // >= 4 samples
        assertEquals(2, dist.excludedIncompatibleCount)
        assertEquals(1, dist.excludedMissingMustHavesCount)

        // R-7 percentiles on [30, 40, 50, 60]:
        // pos(0.25) = 0.75 -> 30 + 0.75*10 = 37.50
        // pos(0.50) = 1.50 -> 45.00
        // pos(0.75) = 2.25 -> 52.50
        assertEquals(BigDecimal("30.0000"), dist.minUnitPrice)
        assertEquals(BigDecimal("37.5000"), dist.p25UnitPrice)
        assertEquals(BigDecimal("45.0000"), dist.p50UnitPrice)
        assertEquals(BigDecimal("52.5000"), dist.p75UnitPrice)
        assertEquals(BigDecimal("60.0000"), dist.maxUnitPrice)
        assertEquals(BigDecimal("45.0000"), dist.meanUnitPrice)
    }

    @Test
    fun stoppingRulesAndSearchCostCalculatorRespectUserAssumptions() {
        val now = 1_760_000_000_000L
        val threeQualified = listOf(
            CandidatePriceInput(1L, "A", "g", "1", "PIECE", 4500L, 0L, 0L, true, "manual", now),
            CandidatePriceInput(2L, "B", "g", "1", "PIECE", 4800L, 0L, 0L, true, "manual", now),
            CandidatePriceInput(3L, "C", "g", "1", "PIECE", 5200L, 0L, 0L, true, "manual", now)
        )
        val advice = PriceAnalysisEngine.evaluateStopping(threeQualified, budgetCents = 5000L, tier = DecisionTier.DAILY_ESSENTIAL, elapsedSessionMinutes = 10)
        assertTrue(advice.recommendStop)
        assertTrue(StopReason.THREE_TWO_ONE_READY in advice.reasons)

        // Durable high-value item reaching session time -> recommends pause, not forced immediate buy
        val durableAdvice = PriceAnalysisEngine.evaluateStopping(threeQualified.take(1), budgetCents = 500_000L, tier = DecisionTier.DURABLE_HIGH, elapsedSessionMinutes = 35)
        assertTrue(StopReason.MULTI_SESSION_PAUSE in durableAdvice.reasons)

        // Search cost: 30% chance to save 20 yuan = 6 yuan benefit vs 30m at 60 yuan/hr = 30 yuan cost -> suggest stop
        val stopEst = PriceAnalysisEngine.estimateSearchCost(
            potentialSavingsCents = 2_000L,
            estimatedProbPercent = 30,
            extraSearchMinutes = 30,
            hourlyTimeValueCents = 6_000L
        )
        assertEquals(600L, stopEst.expectedBenefitCents)
        assertEquals(3_000L, stopEst.searchCostCents)
        assertTrue(stopEst.suggestStop)

        // High-value saving: 50% chance to save 400 yuan = 200 yuan benefit vs 30m at 60 yuan/hr = 30 yuan cost -> worth researching
        val keepEst = PriceAnalysisEngine.estimateSearchCost(
            potentialSavingsCents = 40_000L,
            estimatedProbPercent = 50,
            extraSearchMinutes = 30,
            hourlyTimeValueCents = 6_000L
        )
        assertFalse(keepEst.suggestStop)
    }
}
