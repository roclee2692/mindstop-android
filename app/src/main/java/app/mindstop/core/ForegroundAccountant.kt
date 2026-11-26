package app.mindstop.core

/**
 * Turns foreground changes into usage segments.
 *
 * Durations come from the monotonic clock; wall times only place a segment on the calendar.
 * Moving between supervised apps keeps one session. A new session starts only after the user has
 * stayed away from every supervised app for at least [sessionGapMs].
 */
class ForegroundAccountant(private val sessionGapMs: Long) {

    data class Segment(
        val packageName: String,
        val sessionId: Long,
        val startWall: Long,
        val endWall: Long
    ) {
        val durationMs: Long get() = endWall - startWall
    }

    var currentPackage: String? = null
        private set
    var sessionId: Long? = null
        private set
    /** Trusted wall time when supervised use last stopped or was last flushed. */
    var lastSupervisedWall: Long? = null
        private set

    private var openStartElapsed = 0L
    private var openStartWall = 0L
    private var forceNewSession = false

    /** Makes the next supervised app start a new session, for example after a cooldown ended. */
    fun forceNewSession() {
        forceNewSession = true
    }

    /** Restores a session saved before the service restarted. */
    fun restore(sessionId: Long, lastSupervisedWall: Long) {
        if (currentPackage != null) return
        this.sessionId = sessionId
        this.lastSupervisedWall = lastSupervisedWall
    }

    /**
     * Records that [packageName] is now in front. Pass null when no supervised app is in front:
     * the screen went off, another app opened, or the block screen appeared.
     * Returns the segment that was closed, if any.
     */
    fun onForeground(packageName: String?, nowElapsed: Long, nowWall: Long): List<Segment> {
        if (packageName == currentPackage) return emptyList()
        val closed = close(nowElapsed)
        if (packageName != null) {
            val last = lastSupervisedWall
            if (forceNewSession || sessionId == null || last == null || nowWall - last >= sessionGapMs || nowWall < last - sessionGapMs) {
                // Two sessions in the same millisecond would merge, so keep ids strictly increasing.
                sessionId = maxOf(nowWall, (sessionId ?: Long.MIN_VALUE) + 1)
                forceNewSession = false
            }
            currentPackage = packageName
            openStartElapsed = nowElapsed
            openStartWall = nowWall
        }
        return closed
    }

    /** Closes the open portion so it can be counted, and keeps the app open. */
    fun flush(nowElapsed: Long): List<Segment> {
        val pkg = currentPackage ?: return emptyList()
        val closed = close(nowElapsed)
        currentPackage = pkg
        openStartElapsed = nowElapsed
        openStartWall = closed.lastOrNull()?.endWall ?: openStartWall
        return closed
    }

    /** Duration of the still open portion, without closing it. */
    fun openDurationMs(nowElapsed: Long): Long =
        if (currentPackage == null) 0L else maxOf(0L, nowElapsed - openStartElapsed)

    /** Trusted wall time when the still open portion began, or null when nothing is open. */
    fun openStartWall(): Long? = if (currentPackage == null) null else openStartWall

    /** True when a new session would start if a supervised app opened at [nowWall]. */
    fun wouldStartNewSession(nowWall: Long): Boolean {
        val last = lastSupervisedWall ?: return true
        return sessionId == null || nowWall - last >= sessionGapMs || nowWall < last - sessionGapMs
    }

    private fun close(nowElapsed: Long): List<Segment> {
        val pkg = currentPackage ?: return emptyList()
        val session = sessionId ?: return emptyList()
        currentPackage = null
        val duration = nowElapsed - openStartElapsed
        val endWall = openStartWall + maxOf(0L, duration)
        lastSupervisedWall = endWall
        if (duration <= 0L) return emptyList()
        return listOf(Segment(pkg, session, openStartWall, endWall))
    }
}
