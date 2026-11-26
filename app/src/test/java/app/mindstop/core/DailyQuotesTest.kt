package app.mindstop.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyQuotesTest {
    @Test
    fun catalogHasHundredUniqueBilingualQuotesWithoutDashes() {
        assertEquals(100, DailyQuotes.catalog.size)
        assertEquals(100, DailyQuotes.ids.toSet().size)
        val dashes = setOf('-', '–', '—')
        for (q in DailyQuotes.catalog) {
            assertTrue("Empty zh for ${q.id}", q.zh.isNotBlank())
            assertTrue("Empty en for ${q.id}", q.en.isNotBlank())
            assertFalse("Dash found in zh for ${q.id}", q.zh.any { it in dashes })
            assertFalse("Dash found in en for ${q.id}", q.en.any { it in dashes })
            q.zhSource?.let { assertFalse("Dash found in zhSource for ${q.id}", it.any { c -> c in dashes }) }
            q.enSource?.let { assertFalse("Dash found in enSource for ${q.id}", it.any { c -> c in dashes }) }
        }
    }

    @Test
    fun eachRoundUsesEveryQuoteAndBoundaryDoesNotRepeat() {
        val random = Random(9)
        val day = "2026-11-26"
        var state = DailyQuotes.select(null, day, random = random)

        repeat(5) {
            val seen = mutableSetOf(state.current)
            repeat(DailyQuotes.ids.size - 1) {
                state = DailyQuotes.select(state, day, advance = true, random = random)
                seen += state.current
            }
            assertEquals(DailyQuotes.ids.toSet(), seen)
            val last = state.current
            state = DailyQuotes.select(state, day, advance = true, random = random)
            assertNotEquals(last, state.current)
        }
    }

    @Test
    fun manualChoicePersistsThroughReopenAndNextDayAdvances() {
        val first = DailyQuotes.select(null, "2026-11-26", random = Random(42))
        val changed = DailyQuotes.select(first, first.day, advance = true)
        val reopened = checkNotNull(DailyQuotes.decode(changed.encode()))

        assertNotEquals(first.current, reopened.current)
        assertEquals(changed, reopened)
        assertSame(reopened, DailyQuotes.select(reopened, reopened.day))

        val tomorrow = DailyQuotes.select(reopened, "2026-11-27")
        assertEquals("2026-11-27", tomorrow.day)
        assertEquals(reopened.remaining.first(), tomorrow.current)
    }
}
