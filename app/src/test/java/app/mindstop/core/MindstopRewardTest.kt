package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MindstopRewardTest {

    private val videoGroup = QuotaRule(
        id = "grp_video",
        name = "Video",
        scope = RuleScope.GROUP,
        packages = setOf("tv.danmaku.bili", "com.xingin.xhs"),
        dailyLimitMs = 60 * 60_000L,
        sessionLimitMs = 30 * 60_000L
    )
    private val globalRule = QuotaRule(
        id = "global_1",
        name = "Global",
        scope = RuleScope.GLOBAL,
        packages = emptySet(),
        dailyLimitMs = 90 * 60_000L,
        sessionLimitMs = null
    )
    private val appRule = QuotaRule(
        id = "app_bili",
        name = "Bili Only",
        scope = RuleScope.APP,
        packages = setOf("tv.danmaku.bili"),
        dailyLimitMs = 30 * 60_000L,
        sessionLimitMs = null
    )

    @Test
    fun activeExitIssuesPendingRewardWithFixedEnergyAnd15MinWindow() {
        val now = 1_000_000L
        val decision = RewardEngine.evaluateGrant(
            RewardRequest(
                dayKey = "2026-11-26",
                groupKey = "grp_video",
                packageName = "tv.danmaku.bili",
                sessionId = 100L,
                trigger = RewardTrigger.ACTIVE_EXIT,
                nowWall = now
            ),
            existing = emptyList()
        )
        assertTrue(decision is RewardGrantDecision.Grant)
        val pending = (decision as RewardGrantDecision.Grant).pending
        assertEquals(RewardStatus.PENDING, pending.status)
        assertEquals(RewardEngine.FULL_ENERGY, pending.energy)
        assertTrue(pending.isFull)
        assertEquals(now + 15 * 60_000L, pending.verifyUntilWall)
    }

    @Test
    fun blockStopEarnsDistinctPartialReward() {
        val decision = RewardEngine.evaluateGrant(
            RewardRequest(
                dayKey = "2026-11-26",
                groupKey = "grp_video",
                packageName = "tv.danmaku.bili",
                sessionId = 101L,
                trigger = RewardTrigger.BLOCK_STOP,
                nowWall = 2_000_000L
            ),
            existing = emptyList()
        )
        assertTrue(decision is RewardGrantDecision.Grant)
        val pending = (decision as RewardGrantDecision.Grant).pending
        assertEquals(RewardTrigger.BLOCK_STOP, pending.trigger)
        assertEquals(RewardEngine.PARTIAL_ENERGY, pending.energy)
        assertFalse(pending.isFull)
    }

    @Test
    fun confirmsAfterWindowAndNeverCancelsConfirmedReward() {
        val now = 1_000_000L
        val pending = (RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "tv.danmaku.bili", 1L, RewardTrigger.ACTIVE_EXIT, now),
            emptyList()
        ) as RewardGrantDecision.Grant).pending

        // Before 15 minutes: not due yet.
        assertTrue(RewardEngine.dueToConfirm(listOf(pending), now + 14 * 60_000L).isEmpty())

        // Exactly at 15 minutes: due to confirm, NOT cancelled even if user re-enters now.
        val at15m = now + 15 * 60_000L
        val due = RewardEngine.dueToConfirm(listOf(pending), at15m)
        assertEquals(1, due.size)
        assertTrue(
            RewardEngine.dueToCancelOnReentry(
                listOf(pending),
                "tv.danmaku.bili",
                setOf("grp_video", "pkg:tv.danmaku.bili"),
                at15m
            ).isEmpty()
        )

        // Confirmed record is never cancelled on subsequent entertainment.
        val confirmed = pending.copy(status = RewardStatus.CONFIRMED, resolvedWall = at15m)
        assertTrue(
            RewardEngine.dueToCancelOnReentry(
                listOf(confirmed),
                "tv.danmaku.bili",
                setOf("grp_video", "pkg:tv.danmaku.bili"),
                at15m + 60_000L
            ).isEmpty()
        )
    }

    @Test
    fun reentryToAnotherAppInSameGroupCancelsPendingReward() {
        val now = 1_000_000L
        val rules = listOf(globalRule, videoGroup)
        val groupKey = RewardEngine.groupKeyFor("tv.danmaku.bili", rules, emptySet())
        assertEquals("grp_video", groupKey)

        val pending = (RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", groupKey, "tv.danmaku.bili", 1L, RewardTrigger.ACTIVE_EXIT, now),
            emptyList()
        ) as RewardGrantDecision.Grant).pending

        // User opens Xiaohongshu (same group) 5 minutes later -> pending reward cancelled!
        val xhsKeys = RewardEngine.matchingGroupKeys("com.xingin.xhs", rules, emptySet())
        val cancelled = RewardEngine.dueToCancelOnReentry(listOf(pending), "com.xingin.xhs", xhsKeys, now + 5 * 60_000L)
        assertEquals(1, cancelled.size)

        // Unrelated app does NOT cancel pending reward.
        val otherKeys = RewardEngine.matchingGroupKeys("com.example.reader", rules, emptySet())
        assertTrue(
            RewardEngine.dueToCancelOnReentry(listOf(pending), "com.example.reader", otherKeys, now + 5 * 60_000L).isEmpty()
        )
    }

    @Test
    fun antiArbitrageBlocksDuplicateInSameSessionAndGroupDailyCap() {
        val now = 1_000_000L
        val first = (RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "tv.danmaku.bili", 10L, RewardTrigger.ACTIVE_EXIT, now),
            emptyList()
        ) as RewardGrantDecision.Grant).pending

        // Same session cannot earn a second reward.
        val sameSession = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "tv.danmaku.bili", 10L, RewardTrigger.ACTIVE_EXIT, now + 10_000L),
            listOf(first)
        )
        assertEquals(RewardGrantDecision.Deny(RewardDenyReason.ALREADY_REWARDED_IN_SESSION), sameSession)

        // New session same day same group blocked by default daily cap = 1.
        val confirmedFirst = first.copy(status = RewardStatus.CONFIRMED, resolvedWall = now + 15 * 60_000L)
        val secondSessionSameDay = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "com.xingin.xhs", 20L, RewardTrigger.ACTIVE_EXIT, now + 60 * 60_000L),
            listOf(confirmedFirst)
        )
        assertEquals(RewardGrantDecision.Deny(RewardDenyReason.GROUP_DAILY_CAP_REACHED), secondSessionSameDay)

        // Configurable daily cap = 2 allows the second session.
        val allowedWithCap2 = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "com.xingin.xhs", 20L, RewardTrigger.ACTIVE_EXIT, now + 60 * 60_000L),
            listOf(confirmedFirst),
            RewardSettings(maxFullPerGroupPerDay = 2)
        )
        assertTrue(allowedWithCap2 is RewardGrantDecision.Grant)

        // Next day resets cap cleanly.
        val nextDay = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-27", "grp_video", "tv.danmaku.bili", 30L, RewardTrigger.ACTIVE_EXIT, now + 86_400_000L),
            listOf(confirmedFirst)
        )
        assertTrue(nextDay is RewardGrantDecision.Grant)
    }

    @Test
    fun antiArbitrageStopsRepeatedCancelFarmingInSameSession() {
        val now = 1_000_000L
        val c1 = RewardRecord(1, "2026-11-26", "grp_video", "tv.danmaku.bili", 55L, RewardTrigger.ACTIVE_EXIT, 10, true, RewardStatus.CANCELLED, now, now + 900_000L, now + 60_000L)
        val c2 = c1.copy(id = 2, createdWall = now + 120_000L)

        // One cancelled attempt allows one honest retry in the same session.
        val retry1 = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "tv.danmaku.bili", 55L, RewardTrigger.ACTIVE_EXIT, now + 120_000L),
            listOf(c1)
        )
        assertTrue(retry1 is RewardGrantDecision.Grant)

        // More than MAX_CANCELLED_RETRIES_PER_SESSION cancellations blocks further spam in this session.
        val spam = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "tv.danmaku.bili", 55L, RewardTrigger.ACTIVE_EXIT, now + 240_000L),
            listOf(c1, c2)
        )
        assertEquals(RewardGrantDecision.Deny(RewardDenyReason.CANCEL_COOLDOWN_IN_SESSION), spam)
    }

    @Test
    fun experimentalRequireReminderFirstFlag() {
        val settings = RewardSettings(requireReminderFirst = true)
        val denied = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "tv.danmaku.bili", 1L, RewardTrigger.ACTIVE_EXIT, 1000L, hadReminderInSession = false),
            emptyList(),
            settings
        )
        assertEquals(RewardGrantDecision.Deny(RewardDenyReason.REMINDER_REQUIRED_FIRST), denied)

        val granted = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "tv.danmaku.bili", 1L, RewardTrigger.ACTIVE_EXIT, 1000L, hadReminderInSession = true),
            emptyList(),
            settings
        )
        assertTrue(granted is RewardGrantDecision.Grant)

        // Post-block stop is never blocked by the experimental flag.
        val blockStop = RewardEngine.evaluateGrant(
            RewardRequest("2026-11-26", "grp_video", "tv.danmaku.bili", 1L, RewardTrigger.BLOCK_STOP, 1000L, hadReminderInSession = false),
            emptyList(),
            settings
        )
        assertTrue(blockStop is RewardGrantDecision.Grant)
    }

    @Test
    fun groupKeyPrefersMostSpecificRule() {
        val rules = listOf(globalRule, videoGroup, appRule)
        assertEquals("app_bili", RewardEngine.groupKeyFor("tv.danmaku.bili", rules, emptySet()))
        assertEquals("grp_video", RewardEngine.groupKeyFor("com.xingin.xhs", rules, emptySet()))
    }

    @Test
    fun settingsEncodeDecodeRoundTripAndFallback() {
        val custom = RewardSettings(verifyWindowMinutes = 20, maxFullPerGroupPerDay = 3, requireReminderFirst = true, feedbackEnabled = false)
        assertEquals(custom, RewardSettings.decode(RewardSettings.encode(custom)))
        assertEquals(RewardSettings(), RewardSettings.decode(null))
        assertEquals(RewardSettings(), RewardSettings.decode("broken;;data"))
        val clamped = RewardSettings.decode("verify=999;cap=0;reminderFirst=0;feedback=1")
        assertEquals(60, clamped.verifyWindowMinutes)
        assertEquals(1, clamped.maxFullPerGroupPerDay)
    }

    @Test
    fun growthWorldComputesTreesStagesAndPendingSeparately() {
        val records = listOf(
            RewardRecord(1, "2026-11-25", "g1", "a", 1, RewardTrigger.ACTIVE_EXIT, 10, true, RewardStatus.CONFIRMED, 1, 2, 2),
            RewardRecord(2, "2026-11-25", "g1", "a", 2, RewardTrigger.ACTIVE_EXIT, 10, true, RewardStatus.CONFIRMED, 3, 4, 4),
            RewardRecord(3, "2026-11-26", "g1", "a", 3, RewardTrigger.ACTIVE_EXIT, 10, true, RewardStatus.CONFIRMED, 5, 6, 6),
            RewardRecord(4, "2026-11-26", "g1", "a", 4, RewardTrigger.BLOCK_STOP, 4, false, RewardStatus.CONFIRMED, 7, 8, 8),
            RewardRecord(5, "2026-11-26", "g2", "b", 5, RewardTrigger.ACTIVE_EXIT, 10, true, RewardStatus.PENDING, 9, 10),
            RewardRecord(6, "2026-11-26", "g3", "c", 6, RewardTrigger.ACTIVE_EXIT, 10, true, RewardStatus.CANCELLED, 11, 12, 12)
        )
        val state = GrowthWorldPlanner.compute(records, "2026-11-26")
        assertEquals(34, state.confirmedEnergy)
        assertEquals(10, state.pendingEnergy)
        assertEquals(3, state.confirmedTrees)
        assertEquals(4, state.nextTreeProgress)
        assertEquals(1, state.pendingSaplings)
        assertEquals(GardenStage.GROVE, state.stage)
        assertEquals(14, state.confirmedTodayEnergy)
        assertEquals(1, state.pendingTodayCount)
        assertEquals(3, state.activeExitsConfirmed)
        assertEquals(1, state.blockStopsConfirmed)
        assertEquals(4, state.plants.size) // 3 confirmed + 1 pending
        assertEquals(1, state.plants.count { it.isPending })
        // Deterministic placement across calls
        assertEquals(state.plants, GrowthWorldPlanner.compute(records, "2026-11-26").plants)
    }
}
