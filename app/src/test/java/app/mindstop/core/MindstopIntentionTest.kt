package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINUTE = 60_000L

class PlanNodesTest {

    private fun remainingOf(nodes: List<ReminderPlanner.PlanNode>) = nodes.map { it.remainingMs }

    @Test
    fun `a 30 minute plan merges the 50 percent point into 15 minutes`() {
        val nodes = ReminderPlanner.planNodes(30 * MINUTE, listOf(15, 10, 5), listOf(50))
        assertEquals(listOf(5 * MINUTE, 10 * MINUTE, 15 * MINUTE), remainingOf(nodes))
        assertEquals(listOf("plan_300s", "plan_600s", "plan_900s"), nodes.map { it.id })
    }

    @Test
    fun `points at or above the plan length are dropped`() {
        val nodes = ReminderPlanner.planNodes(10 * MINUTE, listOf(15, 10, 5), listOf(50))
        assertEquals(listOf(5 * MINUTE), remainingOf(nodes))
    }

    @Test
    fun `a short plan keeps only the percent point`() {
        val nodes = ReminderPlanner.planNodes(5 * MINUTE, listOf(15, 10, 5), listOf(50))
        assertEquals(listOf(150_000L), remainingOf(nodes))
        assertTrue(nodes.single().lastStretch)
    }

    @Test
    fun `points less than a minute apart merge into the more urgent one`() {
        // 50 percent of 9 minutes is 4.5 minutes, 30 seconds from the 5 minute point.
        val nodes = ReminderPlanner.planNodes(9 * MINUTE, listOf(5), listOf(50))
        assertEquals(listOf(270_000L), remainingOf(nodes))
    }

    @Test
    fun `only points within five minutes count as the last stretch`() {
        val nodes = ReminderPlanner.planNodes(30 * MINUTE, listOf(15, 10, 5), emptyList())
        assertEquals(listOf(true, false, false), nodes.map { it.lastStretch })
    }

    @Test
    fun `jumping past several points fires only the most urgent`() {
        val plan = 30 * MINUTE
        val nodes = ReminderPlanner.planNodes(plan, listOf(15, 10, 5), emptyList())
        assertEquals("plan_300s", ReminderPlanner.planReminder(plan, 27 * MINUTE, nodes))
    }

    @Test
    fun `no points means only the end reminder`() {
        val plan = 20 * MINUTE
        assertNull(ReminderPlanner.planReminder(plan, 19 * MINUTE, emptyList()))
        assertEquals(ReminderPlanner.PLAN_END, ReminderPlanner.planReminder(plan, plan, emptyList()))
    }
}

class SessionCountdownTest {

    @Test
    fun `countdown follows whichever ends first`() {
        assertEquals(3 * MINUTE, SessionCountdown.targetMs(10 * MINUTE, 3 * MINUTE))
        assertEquals(4 * MINUTE, SessionCountdown.targetMs(4 * MINUTE, 30 * MINUTE))
        assertEquals(4 * MINUTE, SessionCountdown.targetMs(4 * MINUTE, null))
    }

    @Test
    fun `a used up plan hands the countdown to the quota`() {
        assertEquals(3 * MINUTE, SessionCountdown.targetMs(0, 3 * MINUTE))
        assertEquals(3 * MINUTE, SessionCountdown.targetMs(-MINUTE, 3 * MINUTE))
        assertNull(SessionCountdown.targetMs(-MINUTE, null))
        assertNull(SessionCountdown.targetMs(null, null))
    }

    @Test
    fun `stages tighten at 5 minutes, 1 minute and 10 seconds`() {
        assertEquals(CountdownStage.HIDDEN, SessionCountdown.stage(6 * MINUTE, showGoal = false))
        assertEquals(CountdownStage.GOAL, SessionCountdown.stage(6 * MINUTE, showGoal = true))
        assertEquals(CountdownStage.STEADY, SessionCountdown.stage(5 * MINUTE, showGoal = false))
        assertEquals(CountdownStage.STRONG, SessionCountdown.stage(MINUTE, showGoal = true))
        assertEquals(CountdownStage.FINAL, SessionCountdown.stage(10_000L, showGoal = false))
        assertEquals(CountdownStage.FINAL, SessionCountdown.stage(1_000L, showGoal = true))
    }

    @Test
    fun `without any limit the goal still shows when wanted`() {
        assertEquals(CountdownStage.GOAL, SessionCountdown.stage(null, showGoal = true))
        assertEquals(CountdownStage.HIDDEN, SessionCountdown.stage(null, showGoal = false))
    }
}

class CalmWaitTest {

    @Test
    fun `wait counts down from its start`() {
        assertEquals(30_000L, CalmWait.remainingMs(1_000L, 30, 1_000L))
        assertEquals(10_000L, CalmWait.remainingMs(1_000L, 30, 21_000L))
        assertEquals(0L, CalmWait.remainingMs(1_000L, 30, 31_000L))
        assertEquals(0L, CalmWait.remainingMs(1_000L, 0, 1_000L))
    }

    @Test
    fun `turning the clock back cannot lengthen the wait`() {
        assertEquals(30_000L, CalmWait.remainingMs(100_000L, 30, 50_000L))
    }

    @Test
    fun `wait seconds are clamped`() {
        assertEquals(0, CalmWait.clamp(-5))
        assertEquals(CalmWait.MAX_SECONDS, CalmWait.clamp(99_999))
        assertEquals(30, CalmWait.clamp(30))
    }

    @Test
    fun `errands skip the wait and fun has no goal`() {
        assertFalse(IntentPurpose.ERRAND.usesCalmWait)
        assertTrue(IntentPurpose.STUDY.usesCalmWait)
        assertFalse(IntentPurpose.FUN.hasGoal)
        assertTrue(IntentPurpose.ERRAND.hasGoal)
        assertEquals(IntentPurpose.STUDY, IntentPurpose.fromCode("study"))
        assertNull(IntentPurpose.fromCode("other"))
    }
}

class IntentionSettingsTest {

    @Test
    fun `missing settings use the defaults`() {
        val settings = IntentionSettings.decode(null)
        assertEquals(30, settings.waitSeconds)
        assertEquals(listOf(15, 10, 5), settings.nodeMinutes)
        assertEquals(listOf(50), settings.nodePercents)
        assertTrue(settings.overlayEnabled)
    }

    @Test
    fun `settings survive a round trip`() {
        val settings = IntentionSettings(
            waitSeconds = 60,
            nodeMinutes = listOf(20, 3),
            nodePercents = listOf(75, 25),
            overlayEnabled = false,
            position = OverlayPosition.BOTTOM,
            size = OverlaySize.LARGE,
            intensity = OverlayIntensity.LIGHT
        )
        assertEquals(settings, IntentionSettings.decode(settings.encode()))
    }

    @Test
    fun `reminder points parse minutes and percents in any separator`() {
        assertEquals(listOf(15, 10, 5) to listOf(50), IntentionSettings.parseNodes("5, 15，10 50%"))
        assertEquals(emptyList<Int>() to emptyList<Int>(), IntentionSettings.parseNodes(""))
    }

    @Test
    fun `invalid reminder points are refused`() {
        assertNull(IntentionSettings.parseNodes("abc"))
        assertNull(IntentionSettings.parseNodes("0"))
        assertNull(IntentionSettings.parseNodes("100%"))
        assertNull(IntentionSettings.parseNodes("10, 999"))
    }

    @Test
    fun `cleared reminder points stay cleared and bad values fall back`() {
        val cleared = IntentionSettings.decode("nodes=")
        assertEquals(emptyList<Int>(), cleared.nodeMinutes)
        assertEquals(emptyList<Int>(), cleared.nodePercents)
        val broken = IntentionSettings.decode("wait=abc;nodes=xyz;position=SIDEWAYS")
        assertEquals(IntentionSettings(), broken)
    }
}

class IntentionGateTest {

    private fun intent(minutes: Int?, base: Long = 0L, status: IntentStatus = IntentStatus.ACTIVE) =
        SessionIntent(IntentPurpose.STUDY, "Transformer", "multi-head attention", minutes, base, status, 1L)

    @Test
    fun `a session without an intention asks first`() {
        assertEquals(PromptMode.NEW, IntentionGate.promptNeeded(null, 0L))
    }

    @Test
    fun `an active plan with time left lets the user in`() {
        assertNull(IntentionGate.promptNeeded(intent(10), 9 * MINUTE))
    }

    @Test
    fun `a used up plan asks to extend`() {
        assertEquals(PromptMode.EXTEND, IntentionGate.promptNeeded(intent(10), 10 * MINUTE))
        assertEquals(PromptMode.EXTEND, IntentionGate.promptNeeded(intent(10), 25 * MINUTE))
    }

    @Test
    fun `an open ended intention never runs out`() {
        assertNull(IntentionGate.promptNeeded(intent(null), 300 * MINUTE))
    }

    @Test
    fun `an ended or finished intention asks again in the same session`() {
        assertEquals(PromptMode.NEW, IntentionGate.promptNeeded(intent(10, status = IntentStatus.ENDED), 0L))
        assertEquals(PromptMode.NEW, IntentionGate.promptNeeded(intent(10, status = IntentStatus.DONE), 0L))
    }

    @Test
    fun `a later plan counts only time used after it started`() {
        val later = intent(10, base = 14 * MINUTE)
        assertEquals(10 * MINUTE, later.planRemainingMs(14 * MINUTE))
        assertEquals(MINUTE, later.planRemainingMs(23 * MINUTE))
        assertNull(IntentionGate.promptNeeded(later, 20 * MINUTE))
        assertEquals(PromptMode.EXTEND, IntentionGate.promptNeeded(later, 24 * MINUTE))
    }

    @Test
    fun `extending adds minutes to the same plan`() {
        val extended = intent(10).copy(minutes = 15)
        assertEquals(5 * MINUTE, extended.planRemainingMs(10 * MINUTE))
        assertNull(IntentionGate.promptNeeded(extended, 10 * MINUTE))
    }

    @Test
    fun `goal summary keeps short goals and trims long ones`() {
        assertEquals("Transformer", intent(10).goalSummary())
        val long = intent(10).copy(goal = "学习 Transformer 多头注意力机制并做笔记")
        assertTrue(long.goalSummary().length <= 17)
        assertTrue(long.goalSummary().endsWith("…"))
    }
}
