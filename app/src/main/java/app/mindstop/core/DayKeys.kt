package app.mindstop.core

import java.time.Instant
import java.time.ZoneId

/** Local calendar day helpers. Day keys look like `2026-11-25`. */
object DayKeys {

    data class DaySlice(val dayKey: String, val startWall: Long, val endWall: Long) {
        val durationMs: Long get() = endWall - startWall
    }

    fun dayKey(wallMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().toString()

    fun startOfDay(wallMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Splits `[startWall, endWall)` at every local midnight it crosses. */
    fun splitByDay(startWall: Long, endWall: Long, zone: ZoneId = ZoneId.systemDefault()): List<DaySlice> {
        if (endWall <= startWall) return emptyList()
        val slices = ArrayList<DaySlice>(1)
        var cursor = startWall
        while (cursor < endWall) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(endWall, nextMidnight)
            slices.add(DaySlice(date.toString(), cursor, sliceEnd))
            cursor = sliceEnd
        }
        return slices
    }
}
