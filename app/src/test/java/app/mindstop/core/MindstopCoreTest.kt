package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private const val MIN = 60_000L
private const val VIDEO = "com.example.video"
private const val GAME = "com.example.game"
private const val CHAT = "com.example.chat"
private const val LAUNCHER = "com.android.launcher"

class DayKeysTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `dayKey uses local calendar day`() {
        val wall = LocalDate.of(2026, 11, 25).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-11-25", DayKeys.dayKey(wall, zone))
    }

    @Test
    fun `splitByDay cuts a segment at local midnight`() {
        val start = LocalDate.of(2026, 11, 25).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 26).atTime(0, 5).atZone(zone).toInstant().toEpochMilli()
        val slices = DayKeys.splitByDay(start, end, zone)
        assertEquals(2, slices.size)
        assertEquals("2026-11-25", slices[0].dayKey)
        assertEquals(10 * MIN, slices[0].durationMs)
        assertEquals("2026-11-26", slices[1].dayKey)
        assertEquals(5 * MIN, slices[1].durationMs)
    }

    @Test
    fun `splitByDay of an empty range is empty`() {
        val t = LocalDate.of(2026, 11, 25).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(DayKeys.splitByDay(t, t, zone).isEmpty())
    }
}

class TrustedClockModelTest {

    private fun reading(elapsed: Long, wall: Long, auto: Boolean, boot: Int = 3) =
        ClockReading(boot, elapsed, wall, auto)

    @Test
    fun `first anchor adopts the system wall time`() {
        val decision = TrustedClockModel.resolve(null, null, reading(1_000, 100_000, auto = true))
        assertEquals(100_000L, decision.anchor.wallMs)
        assertNull(decision.event)
    }

    @Test
    fun `manual change is ignored when automatic time is off`() {
        val anchor = ClockAnchor(bootCount = 3, elapsedMs = 1_000, wallMs = 100_000)
        // One hour of monotonic time passed, but the user moved the clock to tomorrow.
        val reading = reading(elapsed = 1_000 + 60 * MIN, wall = 100_000 + 26 * 60 * MIN, auto = false)
        val decision = TrustedClockModel.resolve(anchor, 100_000, reading)
        assertEquals(ClockEvent.MANUAL_CHANGE_IGNORED, decision.event)
        // The trusted wall advances with the monotonic clock, not the changed system clock.
        assertEquals(100_000 + 60 * MIN, TrustedClockModel.trustedWall(decision.anchor, reading))
    }

    @Test
    fun `normal drift within tolerance is not flagged`() {
        val anchor = ClockAnchor(bootCount = 3, elapsedMs = 1_000, wallMs = 100_000)
        val reading = reading(elapsed = 61_000, wall = 160_000 + 30_000, auto = false)
        val decision = TrustedClockModel.resolve(anchor, 100_000, reading)
        assertNull(decision.event)
    }

    @Test
    fun `automatic time on trusts the system clock`() {
        val anchor = ClockAnchor(bootCount = 3, elapsedMs = 1_000, wallMs = 100_000)
        val reading = reading(elapsed = 61_000, wall = 900_000, auto = true)
        val decision = TrustedClockModel.resolve(anchor, 100_000, reading)
        assertNull(decision.event)
        assertEquals(900_000L, decision.anchor.wallMs)
    }

    @Test
    fun `clock moved backward before a reboot is floored at the last trusted time`() {
        val stored = ClockAnchor(bootCount = 3, elapsedMs = 1_000, wallMs = 500_000)
        val reading = reading(boot = 4, elapsed = 500, wall = 100_000, auto = true)
        val decision = TrustedClockModel.resolve(stored, 500_000, reading)
        assertEquals(ClockEvent.BACKWARD_AT_BOOT, decision.event)
        assertEquals(500_000L, decision.anchor.wallMs)
    }

    @Test
    fun `encode and decode round trip`() {
        val anchor = ClockAnchor(7, 12_345, 678_910)
        assertEquals(anchor, TrustedClockModel.decode(TrustedClockModel.encode(anchor)))
        assertNull(TrustedClockModel.decode("garbage"))
        assertNull(TrustedClockModel.decode(null))
    }
}

class RuleEngineTest {

    private val dayKey = "2026-11-25"

    private fun rule(
        id: String,
        scope: RuleScope,
        packages: Set<String>,
        daily: Long? = null,
        session: Long? = null
    ) = QuotaRule(id, id, scope, packages, daily, session)

    private val rules = listOf(
        rule("global", RuleScope.GLOBAL, emptySet(), daily = 60 * MIN),
        rule("video-group", RuleScope.GROUP, setOf(VIDEO, GAME), daily = 30 * MIN),
        rule("chat", RuleScope.APP, setOf(CHAT), session = 15 * MIN)
    )

    private fun evaluate(
        pkg: String,
        daily: Map<String, Long> = emptyMap(),
        session: Map<String, Long> = emptyMap(),
        grants: List<QuotaGrant> = emptyList(),
        sessionId: Long? = 1L
    ) = RuleEngine.evaluate(pkg, rules, daily, session, grants, dayKey, sessionId, emptySet())

    @Test
    fun `shared quota counts every supervised app together`() {
        // 45 minutes already used across apps in the morning and at noon, 15 left for the video app.
        val daily = mapOf(VIDEO to 25 * MIN, GAME to 20 * MIN)
        val verdict = evaluate(VIDEO, daily = daily)
        assertTrue(verdict.supervised)
        val group = verdict.statuses.single { it.rule.id == "video-group" }
        assertEquals(45 * MIN, group.usedMs)
        val global = verdict.statuses.single { it.rule.id == "global" }
        assertEquals(45 * MIN, global.usedMs)
        assertEquals(-15 * MIN, group.remainingMs)
        assertEquals(15 * MIN, global.remainingMs)
        // The group rule is stricter, so it decides.
        assertEquals("video-group", verdict.binding?.rule?.id)
        assertTrue(verdict.blocked)
    }

    @Test
    fun `strictest rule decides the block`() {
        val verdict = evaluate(VIDEO, daily = mapOf(VIDEO to 20 * MIN))
        assertFalse(verdict.blocked)
        assertEquals("video-group", verdict.binding?.rule?.id)
        assertEquals(10 * MIN, verdict.remainingMs)
    }

    @Test
    fun `app outside every rule is not supervised`() {
        assertFalse(evaluate(LAUNCHER).supervised)
        assertFalse(evaluate(LAUNCHER).blocked)
    }

    @Test
    fun `exempt apps never count`() {
        val verdict = RuleEngine.evaluate(
            VIDEO, rules, mapOf(VIDEO to 60 * MIN), emptyMap(), emptyList(), dayKey, 1L,
            exempt = setOf(VIDEO)
        )
        assertFalse(verdict.supervised)
    }

    @Test
    fun `session grant unlocks only inside its own session`() {
        val exhausted = evaluate(CHAT, session = mapOf(CHAT to 15 * MIN), sessionId = 7L)
        assertTrue(exhausted.blocked)
        val grants = RuleEngine.grantsToUnlock(exhausted, 5 * MIN, dayKey, sessionId = 7L)
        assertEquals(1, grants.size)
        assertEquals(LimitKind.SESSION, grants[0].kind)
        assertEquals(7L, grants[0].sessionId)
        // Same session: unlocked with exactly 5 minutes left.
        val unlocked = evaluate(CHAT, session = mapOf(CHAT to 15 * MIN), grants = grants, sessionId = 7L)
        assertFalse(unlocked.blocked)
        assertEquals(5 * MIN, unlocked.remainingMs)
        // A different session does not see the grant.
        val otherSession = evaluate(CHAT, session = mapOf(CHAT to 15 * MIN), grants = grants, sessionId = 8L)
        assertTrue(otherSession.blocked)
    }

    @Test
    fun `daily grant carries no session and survives a new session`() {
        val dailyRule = listOf(rule("video-group", RuleScope.GROUP, setOf(VIDEO), daily = 30 * MIN))
        val grants = listOf(QuotaGrant("video-group", dayKey, LimitKind.DAILY, null, 10 * MIN))
        val verdict = RuleEngine.evaluate(
            VIDEO, dailyRule, mapOf(VIDEO to 30 * MIN), emptyMap(), grants, dayKey, 99L, emptySet()
        )
        assertFalse(verdict.blocked)
        assertEquals(10 * MIN, verdict.remainingMs)
    }

    @Test
    fun `grants are created only for exhausted rules`() {
        val verdict = evaluate(VIDEO, daily = mapOf(VIDEO to 25 * MIN, GAME to 20 * MIN))
        // Group rule exhausted (45 of 30), global rule still has 15 minutes.
        assertEquals(1, verdict.exhausted.size)
        val grants = RuleEngine.grantsToUnlock(verdict, 5 * MIN, dayKey, 1L)
        assertEquals(1, grants.size)
        assertEquals("video-group", grants[0].ruleId)
        // 15 minutes over the limit, so the grant covers the overshoot plus the unlock.
        assertEquals(20 * MIN, grants[0].amountMs)
    }
}

class ForegroundAccountantTest {

    private val gap = 10 * MIN
    private val day = LocalDate.of(2026, 11, 25).atStartOfDay(ZoneId.of("Asia/Shanghai"))
        .toInstant().toEpochMilli()

    @Test
    fun `switching between supervised apps keeps one session`() {
        val acc = ForegroundAccountant(gap)
        acc.onForeground(VIDEO, nowElapsed = 0, nowWall = day)
        val closed = acc.onForeground(GAME, nowElapsed = 20 * MIN, nowWall = day + 20 * MIN)
        assertEquals(1, closed.size)
        assertEquals(VIDEO, closed[0].packageName)
        assertEquals(20 * MIN, closed[0].durationMs)
        assertEquals(acc.sessionId, closed[0].sessionId)
        val sessionAfterSwitch = acc.sessionId
        assertNotNull(sessionAfterSwitch)
        val closed2 = acc.onForeground(null, nowElapsed = 30 * MIN, nowWall = day + 30 * MIN)
        assertEquals(1, closed2.size)
        assertEquals(sessionAfterSwitch, closed2[0].sessionId)
    }

    @Test
    fun `a gap beyond the threshold starts a new session`() {
        val acc = ForegroundAccountant(gap)
        acc.onForeground(VIDEO, nowElapsed = 0, nowWall = day)
        acc.onForeground(null, nowElapsed = 5 * MIN, nowWall = day + 5 * MIN)
        val first = acc.sessionId
        acc.onForeground(VIDEO, nowElapsed = 20 * MIN, nowWall = day + 20 * MIN)
        assertNotEquals(first, acc.sessionId)
    }

    @Test
    fun `a short gap keeps the same session`() {
        val acc = ForegroundAccountant(gap)
        acc.onForeground(VIDEO, nowElapsed = 0, nowWall = day)
        acc.onForeground(null, nowElapsed = 5 * MIN, nowWall = day + 5 * MIN)
        val first = acc.sessionId
        acc.onForeground(VIDEO, nowElapsed = 12 * MIN, nowWall = day + 12 * MIN)
        assertEquals(first, acc.sessionId)
    }

    @Test
    fun `flush closes the counted part but keeps the app open`() {
        val acc = ForegroundAccountant(gap)
        acc.onForeground(VIDEO, nowElapsed = 0, nowWall = day)
        val flushed = acc.flush(nowElapsed = 15 * MIN)
        assertEquals(1, flushed.size)
        assertEquals(15 * MIN, flushed[0].durationMs)
        assertEquals(VIDEO, acc.currentPackage)
        val rest = acc.onForeground(null, nowElapsed = 25 * MIN, nowWall = day + 25 * MIN)
        assertEquals(1, rest.size)
        assertEquals(10 * MIN, rest[0].durationMs)
    }

    @Test
    fun `screen off closes the segment without counting further time`() {
        val acc = ForegroundAccountant(gap)
        acc.onForeground(VIDEO, nowElapsed = 0, nowWall = day)
        val closed = acc.onForeground(null, nowElapsed = 8 * MIN, nowWall = day + 8 * MIN)
        assertEquals(8 * MIN, closed[0].durationMs)
        // 3 hours with the screen off: nothing more is counted.
        val nothing = acc.flush(nowElapsed = 8 * MIN + 3 * 60 * MIN)
        assertTrue(nothing.isEmpty())
    }

    @Test
    fun `restored session continues only inside the gap`() {
        val acc = ForegroundAccountant(gap)
        acc.restore(sessionId = day, lastSupervisedWall = day + 5 * MIN)
        assertFalse(acc.wouldStartNewSession(day + 8 * MIN))
        assertTrue(acc.wouldStartNewSession(day + 20 * MIN))
    }
}

class ReminderPlannerTest {

    private val hour = 60 * MIN

    @Test
    fun `no reminder while more than half the limit remains`() {
        assertNull(ReminderPlanner.quotaReminder(hour, 0, 20 * MIN))
    }

    @Test
    fun `half remaining triggers the 50 percent reminder`() {
        assertEquals("remain_50p", ReminderPlanner.quotaReminder(hour, 0, 31 * MIN))
    }

    @Test
    fun `a quarter remaining triggers the 25 percent reminder`() {
        assertEquals("remain_25p", ReminderPlanner.quotaReminder(hour, 0, 50 * MIN))
    }

    @Test
    fun `ten percent remaining triggers the 10 percent reminder`() {
        // Four hour limit, 20 minutes left: 10 percent fires before the absolute 5 minute one.
        assertEquals("remain_10p", ReminderPlanner.quotaReminder(4 * hour, 0, 4 * hour - 20 * MIN))
    }

    @Test
    fun `five minutes left beats the ratio reminders`() {
        assertEquals("remain_5m", ReminderPlanner.quotaReminder(hour, 0, 55 * MIN + 30_000))
    }

    @Test
    fun `one minute left is the most severe reminder`() {
        assertEquals("remain_1m", ReminderPlanner.quotaReminder(hour, 0, hour - 30_000))
    }

    @Test
    fun `no reminder when the quota is exhausted because blocking takes over`() {
        assertNull(ReminderPlanner.quotaReminder(hour, 0, hour))
        assertNull(ReminderPlanner.quotaReminder(hour, 0, hour + 1))
    }

    @Test
    fun `grants extend the limit so reminders move with it`() {
        // 60 min limit plus a 30 min grant: half of 90 min is 45 min remaining.
        assertNull(ReminderPlanner.quotaReminder(hour, 30 * MIN, 40 * MIN))
        assertEquals("remain_50p", ReminderPlanner.quotaReminder(hour, 30 * MIN, 46 * MIN))
    }

    @Test
    fun `plan reminders fire at the most urgent point passed and at the end`() {
        val plan = 30 * MIN
        val nodes = ReminderPlanner.planNodes(plan, listOf(15, 10, 5), listOf(50))
        assertNull(ReminderPlanner.planReminder(plan, 14 * MIN, nodes))
        assertEquals("plan_900s", ReminderPlanner.planReminder(plan, 15 * MIN, nodes))
        assertEquals("plan_600s", ReminderPlanner.planReminder(plan, 21 * MIN, nodes))
        assertEquals("plan_300s", ReminderPlanner.planReminder(plan, 26 * MIN, nodes))
        assertEquals(ReminderPlanner.PLAN_END, ReminderPlanner.planReminder(plan, 30 * MIN, nodes))
        assertEquals(ReminderPlanner.PLAN_END, ReminderPlanner.planReminder(plan, 45 * MIN, nodes))
    }

    @Test
    fun `no plan reminder without a plan`() {
        assertNull(ReminderPlanner.planReminder(0, 60 * MIN, emptyList()))
    }
}
