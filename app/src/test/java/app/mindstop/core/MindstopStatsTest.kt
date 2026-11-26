package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindstopStatsTest {

    @Test
    fun computesCoreMetricsSeparatingStudyAndEntertainment() {
        val now = 1_760_000_000_000L
        val today = DayKeys.dayKey(now)

        val segments = listOf(
            UsageRecordInput(today, 1L, "tv.danmaku.bili", now - 25 * 60_000L, now, 25 * 60_000L, "FUN"),
            UsageRecordInput(today, 2L, "tv.danmaku.bili", now - 40 * 60_000L, now, 40 * 60_000L, "STUDY"),
            UsageRecordInput(today, 3L, "com.ss.android.ugc.aweme", now - 10 * 60_000L, now, 10 * 60_000L, null)
        )
        val intentions = listOf(
            // Planned 20m, actual 25m -> 5m overtime
            IntentionRecordInput(1L, "tv.danmaku.bili", IntentPurpose.FUN, "", 20, IntentStatus.ENDED, now, now),
            // Planned 45m, actual 40m -> no overtime
            IntentionRecordInput(2L, "tv.danmaku.bili", IntentPurpose.STUDY, "Learn KMP", 45, IntentStatus.DONE, now, now)
        )
        val rewards = listOf(
            RewardRecordInput(today, 1L, "tv.danmaku.bili", RewardTrigger.ACTIVE_EXIT, RewardStatus.CONFIRMED, 10, now),
            RewardRecordInput(today, 3L, "com.ss.android.ugc.aweme", RewardTrigger.ACTIVE_EXIT, RewardStatus.CANCELLED, 10, now),
            // Focus reward must NOT skew post-exit reentry rate
            RewardRecordInput(today, 99L, "", RewardTrigger.FOCUS_COMPLETE, RewardStatus.CONFIRMED, 15, now)
        )
        val reflections = listOf(
            ReflectionRecord(1L, today, 2L, "tv.danmaku.bili", IntentPurpose.STUDY, "Learn KMP", 45, 40 * 60_000L, ReflectionOutcome.COMPLETED, emptySet(), now),
            ReflectionRecord(2L, today, 4L, "tv.danmaku.bili", IntentPurpose.STUDY, "Math", 30, 35 * 60_000L, ReflectionOutcome.PARTIAL, setOf(ReflectionReason.RECOMMENDATIONS, ReflectionReason.COMMENTS), now)
        )
        val focus = listOf(
            FocusRecordInput(today, "Deep work", 60 * 60_000L, 60 * 60_000L, 5 * 60_000L, 1, FocusStatus.COMPLETED, now - 60 * 60_000L, now)
        )
        val events = listOf(
            EventRecordInput(now, "session_exit", "session=1"),
            EventRecordInput(now, "intention_left", "pkg NEW"),
            EventRecordInput(now, "block_shown", "pkg rule=1"),
            EventRecordInput(now, "challenge_passed", "rule=1"),
            EventRecordInput(now, "cooldown_started", "rule=1"),
            EventRecordInput(now, "bedtime_unplanned_use", "pkg emergency_5m")
        )

        val summary = StatsCalculator.compute(
            StatsRange.TODAY, now, segments, intentions, rewards, reflections, focus, events
        )

        assertEquals(35 * 60_000L, summary.funTotalMs)
        assertEquals(40 * 60_000L, summary.studyTotalMs)
        assertEquals(60 * 60_000L, summary.focusCompletedMs)
        assertEquals(5 * 60_000L, summary.focusInterruptMs)
        assertEquals(1, summary.focusCompletedCount)
        assertEquals("tv.danmaku.bili", summary.topFunApps.first().packageName)
        assertEquals(7, summary.dailyTrend.size)

        // 2 active exits, 1 block -> voluntaryExitRate = 2/3, reliance = 1/3
        assertEquals(2f / 3f, summary.voluntaryExitRate!!, 0.001f)
        assertEquals(1f / 3f, summary.interventionRelianceRate!!, 0.001f)
        assertEquals(5 * 60_000L, summary.avgOvertimeMs)
        assertEquals(2, summary.plannedSessionCount)
        assertEquals(1, summary.overtimeSessionCount)
        // 1 cancelled / (1 confirmed + 1 cancelled) = 0.5
        assertEquals(0.5f, summary.reentryRate!!, 0.001f)
        // 1 completed / 2 study reflections = 0.5
        assertEquals(0.5f, summary.studyCompletionRate!!, 0.001f)
        assertEquals(1, summary.challengePassedCount)
        assertEquals(1, summary.cooldownCount)
        assertEquals(1, summary.bedtimeUnplannedCount)
        assertEquals(2, summary.topReasons.size)
    }

    @Test
    fun emptyDatasetProducesNullRatesInsteadOfFakeZeros() {
        val now = 1_760_000_000_000L
        val summary = StatsCalculator.compute(
            StatsRange.ALL, now, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
        )
        assertEquals(0L, summary.funTotalMs)
        assertNull(summary.voluntaryExitRate)
        assertNull(summary.avgOvertimeMs)
        assertNull(summary.reentryRate)
        assertNull(summary.studyCompletionRate)
        assertNull(summary.interventionRelianceRate)
        assertEquals(7, summary.dailyTrend.size)
    }

    @Test
    fun csvExporterIncludesUtf8BomAndEscapesCommasQuotesAndNewlines() {
        val now = 1_760_000_000_000L
        val day = DayKeys.dayKey(now)
        val result = StatsCsvExporter.build(
            segments = listOf(UsageRecordInput(day, 1L, "pkg.a", now, now + 60_000L, 60_000L, "FUN")),
            intentions = listOf(IntentionRecordInput(1L, "pkg.a", IntentPurpose.STUDY, "Read \"paper\", ch.1\npart 2", 30, IntentStatus.DONE, now, now)),
            rewards = emptyList(),
            reflections = emptyList(),
            focusSessions = emptyList(),
            events = emptyList()
        )
        assertTrue(result.csv.startsWith("\uFEFF" + StatsCsvExporter.HEADER))
        assertEquals(2, result.rowCount)
        assertNotNull(result.csv.indexOf("\"Read \"\"paper\"\", ch.1\npart 2\"").takeIf { it >= 0 })
    }
}
