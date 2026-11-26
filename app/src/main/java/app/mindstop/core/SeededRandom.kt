package app.mindstop.core

/**
 * SplitMix64. Written out instead of java.util.Random so a stored challenge seed keeps producing
 * the same questions on every Android version, which is what stops a restart from refreshing them.
 */
class SeededRandom(seed: Long) {

    private var state = seed

    fun nextLong(): Long {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Uniform integer in [from, toInclusive]. */
    fun nextInt(from: Int, toInclusive: Int): Int {
        require(toInclusive >= from) { "empty range $from..$toInclusive" }
        val span = toInclusive.toLong() - from.toLong() + 1L
        return (from + (nextLong() ushr 1) % span).toInt()
    }

    fun nextBoolean(): Boolean = nextLong() < 0L

    fun <T> pick(items: List<T>): T = items[nextInt(0, items.size - 1)]

    fun <T> shuffled(items: List<T>): List<T> {
        val result = items.toMutableList()
        for (i in result.lastIndex downTo 1) {
            val j = nextInt(0, i)
            val tmp = result[i]
            result[i] = result[j]
            result[j] = tmp
        }
        return result
    }

    companion object {
        private val GAMMA = 0x9E3779B97F4A7C15uL.toLong()
        private val MIX1 = 0xBF58476D1CE4E5B9uL.toLong()
        private val MIX2 = 0x94D049BB133111EBuL.toLong()

        /** An independent stream for one purpose of one seed, for example question 3 of a challenge. */
        fun derive(seed: Long, vararg parts: Long): SeededRandom {
            var s = seed
            for (part in parts) {
                s = SeededRandom(s xor (part * GAMMA)).nextLong()
            }
            return SeededRandom(s)
        }
    }
}
