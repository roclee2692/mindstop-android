package app.mindstop.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure Kotlin domain models for Module 3: Intentional Shopping (v0.3 SPEC §15–§26).
 * Uses exact integer cents (Long) and BigDecimal for all monetary math to prevent floating-point drift.
 */

/** Six distinct launch intents when opening a shopping app (v0.3 §16). */
enum class ShoppingIntentType(val code: String, val requiresBudget: Boolean, val isFastErrand: Boolean, val defaultMinutes: Int) {
    PLANNED_BUY("planned_buy", requiresBudget = true, isFastErrand = false, defaultMinutes = 15),
    LOGISTICS("logistics", requiresBudget = false, isFastErrand = true, defaultMinutes = 5),
    CUSTOMER_SERVICE("customer_service", requiresBudget = false, isFastErrand = true, defaultMinutes = 10),
    AFTER_SALES("after_sales", requiresBudget = false, isFastErrand = true, defaultMinutes = 10),
    REVIEW("review", requiresBudget = false, isFastErrand = true, defaultMinutes = 5),
    BROWSE("browse", requiresBudget = false, isFastErrand = false, defaultMinutes = 15);

    companion object {
        fun fromCode(raw: String?): ShoppingIntentType =
            entries.firstOrNull { it.code == raw } ?: PLANNED_BUY
    }
}

/** Transparent, configurable research-time decision tiers (v0.3 §18). */
enum class DecisionTier(
    val code: String,
    val minMinutes: Int,
    val maxMinutes: Int,
    val defaultMinutes: Int,
    val multiSessionRecommended: Boolean
) {
    LOW_IMPACT("low_impact", 5, 10, 8, false),
    DAILY_ESSENTIAL("daily_essential", 10, 20, 15, false),
    MID_VALUE("mid_value", 20, 40, 30, false),
    DURABLE_HIGH("durable_high", 30, 45, 35, true);

    companion object {
        fun fromCode(raw: String?): DecisionTier =
            entries.firstOrNull { it.code == raw } ?: DAILY_ESSENTIAL
    }
}

object DecisionTierAdvisor {
    /**
     * Recommends an initial active-search tier using simple, transparent rules (v0.3 §18).
     * Safety/compatibility criticality or long-term durability can elevate even lower-priced items.
     */
    fun recommend(
        budgetCents: Long,
        monthlyDiscretionaryCents: Long = 200_000L, // default 2000 CNY
        safetyOrCompatCritical: Boolean = false,
        longTermDurable: Boolean = false
    ): DecisionTier {
        if (longTermDurable || budgetCents >= 100_000L) return DecisionTier.DURABLE_HIGH
        val share = if (monthlyDiscretionaryCents > 0L) budgetCents.toDouble() / monthlyDiscretionaryCents else 0.0
        var tier = when {
            budgetCents <= 5_000L && share < 0.05 -> DecisionTier.LOW_IMPACT
            budgetCents <= 25_000L && share < 0.15 -> DecisionTier.DAILY_ESSENTIAL
            budgetCents < 100_000L && share < 0.40 -> DecisionTier.MID_VALUE
            else -> DecisionTier.DURABLE_HIGH
        }
        if (safetyOrCompatCritical && tier.ordinal < DecisionTier.MID_VALUE.ordinal) {
            tier = DecisionTier.entries[tier.ordinal + 1]
        }
        return tier
    }
}

enum class ShoppingTaskStatus(val code: String) {
    RESEARCHING("RESEARCHING"),
    PURCHASED("PURCHASED"),
    DROPPED("DROPPED"),
    DEFERRED("DEFERRED");

    companion object {
        fun fromCode(raw: String?): ShoppingTaskStatus =
            entries.firstOrNull { it.code == raw } ?: RESEARCHING
    }
}

enum class BacklogStatus(val code: String) {
    COOLING("COOLING"),
    CONFIRMED_NEED("CONFIRMED_NEED"),
    LATER("LATER"),
    DROPPED("DROPPED");

    companion object {
        fun fromCode(raw: String?): BacklogStatus =
            entries.firstOrNull { it.code == raw } ?: COOLING
    }
}

/** Seven lightweight spending categories (v0.3 §25, inspired by Ivy Wallet design). */
enum class ExpenseCategory(val code: String) {
    FOOD("FOOD"),
    DAILY("DAILY"),
    CLOTHING("CLOTHING"),
    WORK_STUDY("WORK_STUDY"),
    DURABLE("DURABLE"),
    HOBBY("HOBBY"),
    OTHER("OTHER");

    companion object {
        fun fromCode(raw: String?): ExpenseCategory =
            entries.firstOrNull { it.code == raw } ?: OTHER
    }
}

/** Exact currency parser & formatter (yuan <-> integer cents). Zero Double rounding drift. */
object MoneyFormat {
    private val HUNDRED = BigDecimal(100)

    fun parseYuanToCents(raw: String?): Long? {
        val cleaned = raw?.trim()?.replace("¥", "")?.replace("￥", "")?.replace(",", "").orEmpty()
        if (cleaned.isBlank()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd < BigDecimal.ZERO) return null
            bd.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    fun formatYuan(cents: Long): String {
        val bd = BigDecimal.valueOf(cents).divide(HUNDRED, 2, RoundingMode.HALF_UP)
        return bd.toPlainString()
    }
}

/** Extracts clean title & URL from e-commerce share texts (Taobao, JD, PDD, Xianyu, browser). */
object ShareLinkParser {
    private val URL_REGEX = Regex("https?://[^\\s\\u3000\"'<>]+")

    data class ParsedShare(val title: String, val url: String)

    fun parse(rawText: String?): ParsedShare {
        val text = rawText?.trim().orEmpty()
        if (text.isBlank()) return ParsedShare("", "")
        val match = URL_REGEX.find(text)
        val url = match?.value.orEmpty()
        val cleanedTitle = text
            .replace(url, " ")
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(80)
        return ParsedShare(title = cleanedTitle, url = url)
    }
}

data class ShoppingBudgetSettings(
    val monthlyTotalCents: Long = 300_000L,         // default 3000.00
    val monthlyDiscretionaryCents: Long = 100_000L, // default 1000.00
    val hourlyTimeValueCents: Long = 5_000L         // default 50.00 / hour (used in Phase 4 search cost)
) {
    companion object {
        fun encode(s: ShoppingBudgetSettings): String =
            "total=${s.monthlyTotalCents.coerceAtLeast(0L)};free=${s.monthlyDiscretionaryCents.coerceAtLeast(0L)};hr=${s.hourlyTimeValueCents.coerceAtLeast(0L)}"

        fun decode(raw: String?): ShoppingBudgetSettings {
            if (raw.isNullOrBlank()) return ShoppingBudgetSettings()
            val map = HashMap<String, String>()
            for (part in raw.split(';')) {
                val eq = part.indexOf('=')
                if (eq > 0) map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            return ShoppingBudgetSettings(
                monthlyTotalCents = map["total"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 300_000L,
                monthlyDiscretionaryCents = map["free"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 100_000L,
                hourlyTimeValueCents = map["hr"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 5_000L
            )
        }
    }
}

data class ExpenseItemInput(
    val category: ExpenseCategory,
    val amountCents: Long,
    val refundCents: Long
) {
    val netCents: Long get() = (amountCents - refundCents).coerceAtLeast(0L)
}

data class MonthlyBudgetSummary(
    val totalSpentCents: Long,
    val totalBudgetCents: Long,
    val discretionaryBudgetCents: Long,
    val remainingTotalCents: Long,
    val byCategoryCents: Map<ExpenseCategory, Long>
)

object BudgetCalculator {
    /** Computes actual confirmed net spending (amount - refund). Candidate prices are NEVER included. */
    fun summarize(settings: ShoppingBudgetSettings, expenses: List<ExpenseItemInput>): MonthlyBudgetSummary {
        val byCat = LinkedHashMap<ExpenseCategory, Long>()
        var total = 0L
        for (e in expenses) {
            val net = e.netCents
            total += net
            byCat[e.category] = (byCat[e.category] ?: 0L) + net
        }
        return MonthlyBudgetSummary(
            totalSpentCents = total,
            totalBudgetCents = settings.monthlyTotalCents,
            discretionaryBudgetCents = settings.monthlyDiscretionaryCents,
            remainingTotalCents = settings.monthlyTotalCents - total,
            byCategoryCents = byCat
        )
    }

    fun landedTotalCents(priceCents: Long, discountCents: Long, shippingCents: Long): Long =
        (priceCents - discountCents + shippingCents).coerceAtLeast(0L)
}
