package app.mindstop.core

enum class Difficulty(val level: Int) {
    EASY(1), MEDIUM(2), HARD(3);

    companion object {
        fun of(level: Int): Difficulty = entries.firstOrNull { it.level == level } ?: MEDIUM
    }
}

/** [prompt] is language neutral, for example "12 + 7 = ?" or "3x − 4 = 11\nx = ?". */
data class MathQuestion(val prompt: String, val answer: Int, val isEquation: Boolean)

/**
 * Local arithmetic questions. Every question has one integer answer: division is always exact,
 * divisors are never zero, and equations always have a nonzero coefficient.
 */
object MathGenerator {

    const val MINUS = "\u2212"
    const val TIMES = "\u00d7"
    const val DIVIDE = "\u00f7"
    private const val STREAM = 0x4D415448L

    fun generate(seed: Long, index: Int, difficulty: Difficulty): MathQuestion {
        val r = SeededRandom.derive(seed, STREAM, index.toLong(), difficulty.level.toLong())
        return when (difficulty) {
            Difficulty.EASY -> easy(r)
            Difficulty.MEDIUM -> medium(r)
            Difficulty.HARD -> hard(r)
        }
    }

    private fun easy(r: SeededRandom): MathQuestion = when (r.nextInt(0, 3)) {
        0 -> {
            val a = r.nextInt(1, 20); val b = r.nextInt(1, 20)
            arithmetic("$a + $b", a + b)
        }
        1 -> {
            val a = r.nextInt(2, 20); val b = r.nextInt(1, a)
            arithmetic("$a $MINUS $b", a - b)
        }
        2 -> {
            val a = r.nextInt(2, 9); val b = r.nextInt(2, 9)
            arithmetic("$a $TIMES $b", a * b)
        }
        else -> {
            val b = r.nextInt(2, 9); val q = r.nextInt(1, 9)
            arithmetic("${b * q} $DIVIDE $b", q)
        }
    }

    private fun medium(r: SeededRandom): MathQuestion = when (r.nextInt(0, 7)) {
        0 -> {
            val a = r.nextInt(10, 99); val b = r.nextInt(10, 99)
            arithmetic("$a + $b", a + b)
        }
        1 -> {
            val a = r.nextInt(20, 99); val b = r.nextInt(10, a)
            arithmetic("$a $MINUS $b", a - b)
        }
        2 -> {
            val a = r.nextInt(3, 12); val b = r.nextInt(3, 19)
            arithmetic("$a $TIMES $b", a * b)
        }
        3 -> {
            val b = r.nextInt(3, 12); val q = r.nextInt(2, 12)
            arithmetic("${b * q} $DIVIDE $b", q)
        }
        4 -> {
            val a = r.nextInt(1, 50); val b = r.nextInt(2, 9); val c = r.nextInt(2, 9)
            arithmetic("$a + $b $TIMES $c", a + b * c)
        }
        5 -> {
            val a = r.nextInt(2, 9); val b = r.nextInt(2, 9); val c = r.nextInt(1, a * b)
            arithmetic("$a $TIMES $b $MINUS $c", a * b - c)
        }
        6 -> {
            val a = r.nextInt(1, 20); val b = r.nextInt(1, 20); val c = r.nextInt(2, 5)
            arithmetic("($a + $b) $TIMES $c", (a + b) * c)
        }
        else -> {
            val a = r.nextInt(2, 5); val x = r.nextInt(1, 12); val b = r.nextInt(1, 20)
            equation("${a}x + $b = ${num(a * x + b)}", x)
        }
    }

    private fun hard(r: SeededRandom): MathQuestion = when (r.nextInt(0, 5)) {
        0 -> {
            val a = r.nextInt(2, 30); val b = r.nextInt(2, 30); val c = r.nextInt(2, 9)
            val d = r.nextInt(1, (a + b) * c)
            arithmetic("($a + $b) $TIMES $c $MINUS $d", (a + b) * c - d)
        }
        1 -> {
            val a = r.nextInt(2, 15); val b = r.nextInt(2, 15); val c = r.nextInt(2, 9); val d = r.nextInt(2, 9)
            arithmetic("$a $TIMES $b + $c $TIMES $d", a * b + c * d)
        }
        2 -> {
            val c = r.nextInt(2, 9); val q = r.nextInt(2, 12); val b = r.nextInt(1, 30)
            val a = b + c * q; val d = r.nextInt(1, 50)
            arithmetic("($a $MINUS $b) $DIVIDE $c + $d", q + d)
        }
        3 -> {
            val b = r.nextInt(2, 12); val q = r.nextInt(2, 12); val c = r.nextInt(2, 9)
            arithmetic("${b * q} $DIVIDE $b $TIMES $c", q * c)
        }
        4 -> {
            val a = r.nextInt(2, 9); val x = r.nextInt(-9, 20); val b = r.nextInt(1, 30)
            if (r.nextBoolean()) {
                equation("${a}x + $b = ${num(a * x + b)}", x)
            } else {
                equation("${a}x $MINUS $b = ${num(a * x - b)}", x)
            }
        }
        else -> {
            val a = r.nextInt(2, 6); val b = r.nextInt(1, 9); val x = r.nextInt(1, 20)
            equation("$a(x $MINUS $b) = ${num(a * (x - b))}", x)
        }
    }

    private fun arithmetic(expression: String, answer: Int) = MathQuestion("$expression = ?", answer, false)

    private fun equation(text: String, x: Int) = MathQuestion("$text\nx = ?", x, true)

    private fun num(n: Int): String = if (n < 0) "$MINUS${-n}" else n.toString()

    /**
     * Reads what the user typed. Accepts full width digits and the various minus signs a Chinese
     * keyboard may produce. Returns null when the text is not a whole number.
     */
    fun parseAnswer(input: String): Int? {
        val sb = StringBuilder()
        for (ch in input.trim()) {
            when {
                ch in '\uFF10'..'\uFF19' -> sb.append('0' + (ch - '\uFF10'))
                ch == '\uFF0D' || ch == '\u2212' || ch == '\uFE63' || ch == '\u2010' || ch == '\u2013' -> sb.append('-')
                ch == ' ' -> Unit
                else -> sb.append(ch)
            }
        }
        val text = sb.toString()
        if (!Regex("^-?\\d{1,6}$").matches(text)) return null
        return text.toInt()
    }
}
