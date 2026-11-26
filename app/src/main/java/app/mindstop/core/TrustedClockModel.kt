package app.mindstop.core

import kotlin.math.abs

/** A fixed point that ties the monotonic clock of one boot to a trusted wall time. */
data class ClockAnchor(val bootCount: Int, val elapsedMs: Long, val wallMs: Long)

data class ClockReading(
    val bootCount: Int,
    val elapsedMs: Long,
    val systemWallMs: Long,
    val autoTimeEnabled: Boolean
)

enum class ClockEvent { MANUAL_CHANGE_IGNORED, BACKWARD_AT_BOOT }

data class AnchorDecision(val anchor: ClockAnchor, val event: ClockEvent?)

/**
 * Decides which wall time quota accounting should trust.
 *
 * With network time on, the system clock is trusted. With network time off, a manual change of
 * the clock is ignored until the next reboot, so moving the clock to tomorrow does not refill
 * today's quota. Across a reboot the clock is never allowed to move behind the last trusted time.
 */
object TrustedClockModel {

    const val DRIFT_TOLERANCE_MS = 120_000L

    fun isSameBoot(anchor: ClockAnchor, reading: ClockReading): Boolean {
        if (anchor.bootCount >= 0 && reading.bootCount >= 0) {
            return anchor.bootCount == reading.bootCount && reading.elapsedMs >= anchor.elapsedMs
        }
        return reading.elapsedMs >= anchor.elapsedMs
    }

    fun resolve(stored: ClockAnchor?, lastTrustedWall: Long?, reading: ClockReading): AnchorDecision {
        if (stored == null || !isSameBoot(stored, reading)) {
            val floor = lastTrustedWall ?: Long.MIN_VALUE
            val event = if (lastTrustedWall != null && reading.systemWallMs < lastTrustedWall - DRIFT_TOLERANCE_MS) {
                ClockEvent.BACKWARD_AT_BOOT
            } else null
            val wall = maxOf(reading.systemWallMs, floor)
            return AnchorDecision(ClockAnchor(reading.bootCount, reading.elapsedMs, wall), event)
        }
        if (reading.autoTimeEnabled) {
            return AnchorDecision(ClockAnchor(reading.bootCount, reading.elapsedMs, reading.systemWallMs), null)
        }
        val expected = stored.wallMs + (reading.elapsedMs - stored.elapsedMs)
        val event = if (abs(reading.systemWallMs - expected) > DRIFT_TOLERANCE_MS) ClockEvent.MANUAL_CHANGE_IGNORED else null
        return AnchorDecision(stored, event)
    }

    fun trustedWall(anchor: ClockAnchor?, reading: ClockReading): Long {
        if (anchor == null || !isSameBoot(anchor, reading)) return reading.systemWallMs
        if (reading.autoTimeEnabled) return reading.systemWallMs
        return anchor.wallMs + (reading.elapsedMs - anchor.elapsedMs)
    }

    fun encode(anchor: ClockAnchor): String = "${anchor.bootCount},${anchor.elapsedMs},${anchor.wallMs}"

    fun decode(value: String?): ClockAnchor? {
        val parts = value?.split(',') ?: return null
        if (parts.size != 3) return null
        val boot = parts[0].toIntOrNull() ?: return null
        val elapsed = parts[1].toLongOrNull() ?: return null
        val wall = parts[2].toLongOrNull() ?: return null
        return ClockAnchor(boot, elapsed, wall)
    }
}
