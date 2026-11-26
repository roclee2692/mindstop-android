package app.mindstop.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Price standardization, percentile analysis, 3-2-1 stopping rules, and search-cost estimation
 * (v0.3 SPEC §20–§23).
 *
 * Unit dimension & BigDecimal base-unit normalization adapted from mablanco/pricegrab
 * (https://github.com/mablanco/pricegrab, MIT License).
 */
enum class UnitDimension(val code: String) {
    COUNT("COUNT"),
    MASS("MASS"),
    VOLUME("VOLUME"),
    LENGTH("LENGTH")
}

enum class QuantityUnit(
    val code: String,
    val dimension: UnitDimension,
    val toBaseMultiplier: BigDecimal,
    val baseUnitCode: String
) {
    PIECE("PIECE", UnitDimension.COUNT, BigDecimal.ONE, "PIECE"),
    G("G", UnitDimension.MASS, BigDecimal.ONE, "G"),
    KG("KG", UnitDimension.MASS, BigDecimal("1000"), "G"),
    ML("ML", UnitDimension.VOLUME, BigDecimal.ONE, "ML"),
    L("L", UnitDimension.VOLUME, BigDecimal("1000"), "ML"),
    CM("CM", UnitDimension.LENGTH, BigDecimal.ONE, "CM"),
    M("M", UnitDimension.LENGTH, BigDecimal("100"), "CM");

    companion object {
        fun fromCode(raw: String?): QuantityUnit =
            entries.firstOrNull { it.code.equals(raw?.trim(), ignoreCase = true) } ?: PIECE
    }
}

data class CandidatePriceInput(
    val id: Long,
    val name: String,
    val comparisonGroupKey: String,
    val quantityText: String,
    val unitCode: String,
    val priceCents: Long,
    val discountCents: Long,
    val shippingCents: Long,
    val meetsMustHaves: Boolean,
    val source: String,
    val collectedWall: Long
)

enum class PricePositionBand {
    BELOW_P25,
    P25_TO_P50,
    P50_TO_P75,
    ABOVE_P75
}

data class NormalizedCandidateOffer(
    val input: CandidatePriceInput,
    val unit: QuantityUnit,
    val landedTotalCents: Long,
    val baseQuantity: BigDecimal,
    /** Exact unit price in yuan per base unit (e.g. yuan per piece, yuan per gram). */
    val unitPriceYuan: BigDecimal,
    val positionBand: PricePositionBand? = null
)

data class PriceDistribution(
    val groupKey: String,
    val dimension: UnitDimension,
    val baseUnitCode: String,
    val sampleCount: Int,
    /** True when sampleCount < 4 (statistics are unstable; always disclosed honestly). */
    val sampleUnstable: Boolean,
    val minUnitPrice: BigDecimal,
    val p25UnitPrice: BigDecimal,
    val p50UnitPrice: BigDecimal,
    val p75UnitPrice: BigDecimal,
    val maxUnitPrice: BigDecimal,
    val meanUnitPrice: BigDecimal,
    val offersSorted: List<NormalizedCandidateOffer>,
    val excludedIncompatibleCount: Int,
    val excludedMissingMustHavesCount: Int,
    val oldestCollectedWall: Long,
    val newestCollectedWall: Long,
    val sources: Set<String>
)

data class StoppingAdvice(
    val qualifiedCount: Int,
    val withinBudgetCount: Int,
    val recommendStop: Boolean,
    val reasons: List<StopReason>
)

enum class StopReason {
    THREE_TWO_ONE_READY,
    WITHIN_BUDGET_QUALIFIED,
    SEARCH_TIME_REACHED,
    MULTI_SESSION_PAUSE
}

data class SearchCostEstimate(
    val expectedBenefitCents: Long,
    val searchCostCents: Long,
    val suggestStop: Boolean
)

object PriceAnalysisEngine {

    private val HUNDRED = BigDecimal(100)
    private val CALC_MC = MathContext(12, RoundingMode.HALF_UP)

    /**
     * Normalizes one candidate into exact yuan per base unit using BigDecimal.
     * Returns null if quantity is invalid or <= 0.
     */
    fun normalize(input: CandidatePriceInput): NormalizedCandidateOffer? {
        val qty = try {
            BigDecimal(input.quantityText.trim())
        } catch (_: Exception) {
            return null
        }
        if (qty <= BigDecimal.ZERO) return null
        val unit = QuantityUnit.fromCode(input.unitCode)
        val baseQty = qty.multiply(unit.toBaseMultiplier, CALC_MC)
        if (baseQty <= BigDecimal.ZERO) return null
        val landedCents = BudgetCalculator.landedTotalCents(input.priceCents, input.discountCents, input.shippingCents)
        val landedYuan = BigDecimal.valueOf(landedCents).divide(HUNDRED, 4, RoundingMode.HALF_UP)
        val unitPrice = landedYuan.divide(baseQty, 4, RoundingMode.HALF_UP)
        return NormalizedCandidateOffer(
            input = input,
            unit = unit,
            landedTotalCents = landedCents,
            baseQuantity = baseQty,
            unitPriceYuan = unitPrice
        )
    }

    /**
     * Computes R-7 linear interpolation percentile (identical to Excel PERCENTILE.INC / NumPy default)
     * over a non-empty pre-sorted list of BigDecimals.
     */
    fun percentileR7(sorted: List<BigDecimal>, p: Double): BigDecimal {
        require(sorted.isNotEmpty()) { "Cannot compute percentile of empty list" }
        val clampedP = p.coerceIn(0.0, 1.0)
        if (sorted.size == 1) return sorted[0].setScale(4, RoundingMode.HALF_UP)
        val pos = clampedP * (sorted.size - 1)
        val lo = floor(pos).toInt().coerceIn(0, sorted.lastIndex)
        val hi = ceil(pos).toInt().coerceIn(0, sorted.lastIndex)
        if (lo == hi) return sorted[lo].setScale(4, RoundingMode.HALF_UP)
        val frac = BigDecimal.valueOf(pos - lo)
        val diff = sorted[hi].subtract(sorted[lo])
        return sorted[lo].add(diff.multiply(frac, CALC_MC)).setScale(4, RoundingMode.HALF_UP)
    }

    /**
     * Analyzes a target comparable group (`targetGroupKey` + `targetDimension`).
     * Strictly enforces comparability (SPEC §20): candidates from other groups, incompatible unit
     * dimensions, or failing must-have criteria are never mixed into the statistical distribution.
     */
    fun analyzeGroup(
        candidates: List<CandidatePriceInput>,
        targetGroupKey: String,
        targetDimension: UnitDimension
    ): PriceDistribution? {
        val normKey = targetGroupKey.trim().lowercase()
        val eligible = ArrayList<NormalizedCandidateOffer>()
        var incompatible = 0
        var missingMust = 0

        for (c in candidates) {
            if (c.comparisonGroupKey.trim().lowercase() != normKey) {
                incompatible++
                continue
            }
            val unit = QuantityUnit.fromCode(c.unitCode)
            if (unit.dimension != targetDimension) {
                incompatible++
                continue
            }
            if (!c.meetsMustHaves) {
                missingMust++
                continue
            }
            val norm = normalize(c)
            if (norm == null) {
                incompatible++
                continue
            }
            eligible.add(norm)
        }

        if (eligible.isEmpty()) return null
        val sorted = eligible.sortedBy { it.unitPriceYuan }
        val prices = sorted.map { it.unitPriceYuan }
        val min = prices.first()
        val max = prices.last()
        val p25 = percentileR7(prices, 0.25)
        val p50 = percentileR7(prices, 0.50)
        val p75 = percentileR7(prices, 0.75)
        val sum = prices.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
        val mean = sum.divide(BigDecimal.valueOf(prices.size.toLong()), 4, RoundingMode.HALF_UP)

        val annotated = sorted.map { offer ->
            val band = when {
                offer.unitPriceYuan < p25 -> PricePositionBand.BELOW_P25
                offer.unitPriceYuan <= p50 -> PricePositionBand.P25_TO_P50
                offer.unitPriceYuan <= p75 -> PricePositionBand.P50_TO_P75
                else -> PricePositionBand.ABOVE_P75
            }
            offer.copy(positionBand = band)
        }

        return PriceDistribution(
            groupKey = targetGroupKey.trim(),
            dimension = targetDimension,
            baseUnitCode = sorted.first().unit.baseUnitCode,
            sampleCount = sorted.size,
            sampleUnstable = sorted.size < 4,
            minUnitPrice = min,
            p25UnitPrice = p25,
            p50UnitPrice = p50,
            p75UnitPrice = p75,
            maxUnitPrice = max,
            meanUnitPrice = mean,
            offersSorted = annotated,
            excludedIncompatibleCount = incompatible,
            excludedMissingMustHavesCount = missingMust,
            oldestCollectedWall = sorted.minOf { it.input.collectedWall },
            newestCollectedWall = sorted.maxOf { it.input.collectedWall },
            sources = sorted.map { it.input.source }.filter { it.isNotBlank() }.toSet()
        )
    }

    /**
     * Groups all qualified candidates of a task into distinct comparable `(groupKey, UnitDimension)`
     * distributions so incompatible specifications or units are never conflated.
     */
    fun analyzeAllGroups(candidates: List<CandidatePriceInput>): List<PriceDistribution> {
        val keys = LinkedHashSet<Pair<String, UnitDimension>>()
        for (c in candidates) {
            if (c.comparisonGroupKey.isBlank()) continue
            val dim = QuantityUnit.fromCode(c.unitCode).dimension
            keys.add(c.comparisonGroupKey.trim() to dim)
        }
        return keys.mapNotNull { (gk, dim) -> analyzeGroup(candidates, gk, dim) }
    }

    /**
     * Evaluates the satisficing / 3-2-1 stopping rules (v0.3 SPEC §22–§23).
     * For durable high-value items, hitting one session's research time recommends saving progress
     * and pausing rather than forcing an immediate purchase.
     */
    fun evaluateStopping(
        candidates: List<CandidatePriceInput>,
        budgetCents: Long,
        tier: DecisionTier,
        elapsedSessionMinutes: Int
    ): StoppingAdvice {
        val qualified = candidates.filter { it.meetsMustHaves }
        val withinBudget = qualified.filter { c ->
            val landed = BudgetCalculator.landedTotalCents(c.priceCents, c.discountCents, c.shippingCents)
            budgetCents <= 0L || landed <= budgetCents
        }
        val reasons = ArrayList<StopReason>()
        if (qualified.size >= 3 && withinBudget.isNotEmpty()) {
            reasons.add(StopReason.THREE_TWO_ONE_READY)
        } else if (withinBudget.isNotEmpty()) {
            reasons.add(StopReason.WITHIN_BUDGET_QUALIFIED)
        }
        if (elapsedSessionMinutes >= tier.defaultMinutes) {
            if (tier.multiSessionRecommended) {
                reasons.add(StopReason.MULTI_SESSION_PAUSE)
            } else {
                reasons.add(StopReason.SEARCH_TIME_REACHED)
            }
        }
        val shouldStop = StopReason.THREE_TWO_ONE_READY in reasons ||
            StopReason.SEARCH_TIME_REACHED in reasons ||
            StopReason.MULTI_SESSION_PAUSE in reasons
        return StoppingAdvice(
            qualifiedCount = qualified.size,
            withinBudgetCount = withinBudget.size,
            recommendStop = shouldStop,
            reasons = reasons
        )
    }

    /**
     * Optional search-cost calculation based strictly on user-supplied assumptions (v0.3 SPEC §22):
     *   Expected Search Benefit = estimatedProbability * potentialSavings
     *   Search Cost = extraSearchMinutes * (hourlyTimeValue / 60)
     */
    fun estimateSearchCost(
        potentialSavingsCents: Long,
        estimatedProbPercent: Int,
        extraSearchMinutes: Int,
        hourlyTimeValueCents: Long
    ): SearchCostEstimate {
        val p = estimatedProbPercent.coerceIn(0, 100) / 100.0
        val benefit = (potentialSavingsCents.coerceAtLeast(0L) * p).roundToLong()
        val cost = (hourlyTimeValueCents.coerceAtLeast(0L) * extraSearchMinutes.coerceAtLeast(0) / 60.0).roundToLong()
        return SearchCostEstimate(
            expectedBenefitCents = benefit,
            searchCostCents = cost,
            suggestStop = benefit <= cost
        )
    }

    fun formatUnitPrice(bd: BigDecimal): String =
        bd.stripTrailingZeros().let {
            if (it.scale() < 2) it.setScale(2, RoundingMode.HALF_UP) else it.setScale(minOf(it.scale(), 4), RoundingMode.HALF_UP)
        }.toPlainString()
}
