package app.mindstop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Evaluates the generator's prompts with its own parser, so the check does not reuse any of the
 * generator's arithmetic. Division must be exact; anything else fails the test.
 */
private object IndependentEvaluator {

    fun evaluate(expression: String): Long {
        val tokens = tokenize(expression)
        val parser = Parser(tokens)
        val value = parser.expression()
        check(parser.done()) { "trailing tokens in $expression" }
        return value
    }

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' -> i++
                c.isDigit() -> {
                    val start = i
                    while (i < text.length && text[i].isDigit()) i++
                    out.add(text.substring(start, i))
                }
                c == '+' || c == '(' || c == ')' -> { out.add(c.toString()); i++ }
                c == '\u2212' -> { out.add("-"); i++ }
                c == '\u00d7' -> { out.add("*"); i++ }
                c == '\u00f7' -> { out.add("/"); i++ }
                else -> error("unexpected '$c' in $text")
            }
        }
        return out
    }

    private class Parser(val tokens: List<String>) {
        var pos = 0
        fun done() = pos == tokens.size
        fun expression(): Long {
            var value = term()
            while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                val op = tokens[pos++]
                val rhs = term()
                value = if (op == "+") value + rhs else value - rhs
            }
            return value
        }
        fun term(): Long {
            var value = factor()
            while (pos < tokens.size && (tokens[pos] == "*" || tokens[pos] == "/")) {
                val op = tokens[pos++]
                val rhs = factor()
                if (op == "*") {
                    value *= rhs
                } else {
                    check(rhs != 0L) { "division by zero" }
                    check(value % rhs == 0L) { "inexact division $value / $rhs" }
                    value /= rhs
                }
            }
            return value
        }
        fun factor(): Long {
            val t = tokens[pos++]
            if (t == "(") {
                val v = expression()
                check(tokens[pos++] == ")")
                return v
            }
            return t.toLong()
        }
    }

    private fun signed(text: String): Long =
        if (text.startsWith("\u2212")) -text.drop(1).toLong() else text.toLong()

    /** Solves the equation shapes the generator uses, then returns x. */
    fun solve(prompt: String): Long {
        val equation = prompt.substringBefore("\n")
        Regex("""^(\d+)x \+ (\d+) = (\S+)$""").matchEntire(equation)?.let {
            val (a, b, c) = it.destructured
            return exactDiv(signed(c) - b.toLong(), a.toLong())
        }
        Regex("""^(\d+)x \u2212 (\d+) = (\S+)$""").matchEntire(equation)?.let {
            val (a, b, c) = it.destructured
            return exactDiv(signed(c) + b.toLong(), a.toLong())
        }
        Regex("""^(\d+)\(x \u2212 (\d+)\) = (\S+)$""").matchEntire(equation)?.let {
            val (a, b, c) = it.destructured
            return exactDiv(signed(c), a.toLong()) + b.toLong()
        }
        error("unknown equation $equation")
    }

    private fun exactDiv(n: Long, d: Long): Long {
        check(d != 0L) { "zero coefficient" }
        check(n % d == 0L) { "no integer solution" }
        return n / d
    }
}

class MathGeneratorTest {

    @Test
    fun `answers match an independent evaluator for many seeds`() {
        var equations = 0
        for (difficulty in Difficulty.entries) {
            for (seed in 0L until 4000L) {
                for (index in 0 until 5) {
                    val q = MathGenerator.generate(seed * 7919L + 13L, index, difficulty)
                    val expected = if (q.isEquation) {
                        equations++
                        assertTrue(q.prompt.endsWith("\nx = ?"))
                        IndependentEvaluator.solve(q.prompt)
                    } else {
                        assertTrue(q.prompt, q.prompt.endsWith(" = ?"))
                        IndependentEvaluator.evaluate(q.prompt.removeSuffix(" = ?"))
                    }
                    assertEquals(q.prompt, expected, q.answer.toLong())
                }
            }
        }
        assertTrue("equations were generated", equations > 1000)
    }

    @Test
    fun `answers stay in a reasonable range per level`() {
        for (seed in 0L until 3000L) {
            for (index in 0 until 4) {
                val easy = MathGenerator.generate(seed, index, Difficulty.EASY)
                assertTrue(easy.prompt, easy.answer in 0..81)
                val medium = MathGenerator.generate(seed, index, Difficulty.MEDIUM)
                assertTrue(medium.prompt, medium.answer in 0..240)
                val hard = MathGenerator.generate(seed, index, Difficulty.HARD)
                assertTrue(hard.prompt, hard.answer in -9..999)
            }
        }
    }

    @Test
    fun `same seed and index give the same question`() {
        for (difficulty in Difficulty.entries) {
            val a = MathGenerator.generate(42L, 3, difficulty)
            val b = MathGenerator.generate(42L, 3, difficulty)
            assertEquals(a, b)
        }
        val prompts = (0 until 20).map { MathGenerator.generate(42L, it, Difficulty.MEDIUM).prompt }.toSet()
        assertTrue("questions vary inside one challenge", prompts.size > 10)
    }

    @Test
    fun `parseAnswer accepts full width digits and minus signs`() {
        assertEquals(12, MathGenerator.parseAnswer(" 12 "))
        assertEquals(-7, MathGenerator.parseAnswer("\u2212" + "7"))
        assertEquals(-7, MathGenerator.parseAnswer("\uFF0D" + "7"))
        assertEquals(35, MathGenerator.parseAnswer("\uFF13\uFF15"))
        assertNull(MathGenerator.parseAnswer("3.5"))
        assertNull(MathGenerator.parseAnswer(""))
        assertNull(MathGenerator.parseAnswer("abc"))
    }
}

class CsvQuestionParserTest {

    private val specSample = """
        type,question,option_a,option_b,option_c,option_d,answer,explanation,difficulty
        word,reluctant,愿意的,不情愿的,可靠的,迅速的,B,不情愿的,1
        synonym,alleviate the pressure,increase,ignore,reduce,create,C,减轻压力,2
        custom,TCP属于哪一层？,应用层,网络层,传输层,数据链路层,C,TCP属于传输层,1
    """.trimIndent()

    @Test
    fun `spec sample imports three questions`() {
        val result = CsvQuestionParser.parse(specSample)
        assertEquals(emptyList<CsvRowError>(), result.errors)
        assertEquals(3, result.questions.size)
        val word = result.questions[0]
        assertEquals(QuestionType.WORD, word.type)
        assertEquals("不情愿的", word.options[word.answerIndex])
        val custom = result.questions[2]
        assertEquals("传输层", custom.options[custom.answerIndex])
        assertEquals(1, custom.difficulty)
    }

    @Test
    fun `BOM, CRLF, quoted commas, doubled quotes and line breaks are handled`() {
        val text = "\uFEFFtype,question,option_a,option_b,option_c,option_d,answer\r\n" +
            "custom,\"1,2,3 中最大的是？\",1,2,3,4,C\r\n" +
            "custom,\"\"\"Hi\"\" 有几个字母\",1,2,3,4,b\r\n" +
            "custom,\"第一行\n第二行\",甲,乙,丙,丁,A\r\n"
        val result = CsvQuestionParser.parse(text)
        assertEquals(emptyList<CsvRowError>(), result.errors)
        assertEquals("1,2,3 中最大的是？", result.questions[0].question)
        assertEquals("\"Hi\" 有几个字母", result.questions[1].question)
        assertEquals(1, result.questions[1].answerIndex)
        assertEquals("第一行\n第二行", result.questions[2].question)
    }

    @Test
    fun `header columns may be reordered and use any case`() {
        val text = "Answer, Question ,TYPE,option_d,option_c,option_b,option_a\nA,quick,word,慢的,大的,小的,快的\n"
        val result = CsvQuestionParser.parse(text)
        assertEquals(emptyList<CsvRowError>(), result.errors)
        assertEquals("快的", result.questions.single().options[0])
    }

    @Test
    fun `every bad row is reported with its line`() {
        val text = listOf(
            "type,question,option_a,option_b,option_c,option_d,answer,explanation,difficulty",
            "word,good,好的,坏的,大的,小的,A,,1",
            "poem,bad type,a,b,c,d,A,,1",
            "word,bad answer,a,b,c,d,E,,1",
            "word,blank option,a,,c,d,A,,1",
            "word,dup option,a,b,A,d,A,,1",
            "word,bad difficulty,a,b,c,d,A,,5",
            "word,,a,b,c,d,A,,1",
            "word,good,好的,坏的,大的,小的,A,,1",
            "word,short row,a,b",
            "",
            "word,last good,a,b,c,d,D,,"
        ).joinToString("\n")
        val result = CsvQuestionParser.parse(text)
        assertEquals(listOf("good", "last good"), result.questions.map { it.question })
        assertEquals(2, result.questions[0].line)
        val kinds = result.errors.associate { it.line to it.kind }
        assertEquals(CsvErrorKind.BAD_TYPE, kinds[3])
        assertEquals(CsvErrorKind.BAD_ANSWER, kinds[4])
        assertEquals(CsvErrorKind.BLANK_OPTION, kinds[5])
        assertEquals(CsvErrorKind.DUPLICATE_OPTION, kinds[6])
        assertEquals(CsvErrorKind.BAD_DIFFICULTY, kinds[7])
        assertEquals(CsvErrorKind.MISSING_FIELD, kinds[8])
        assertEquals(CsvErrorKind.DUPLICATE_IN_FILE, kinds[9])
        assertEquals("2", result.errors.first { it.line == 9 }.detail)
        assertEquals(CsvErrorKind.BLANK_OPTION, kinds[10])
        assertEquals(8, result.errors.size)
        assertEquals(10, result.dataRows)
        assertEquals(1, result.questions[1].difficulty)
    }

    @Test
    fun `questions already in the bank are reported, not imported twice`() {
        val existing = setOf(CsvQuestionParser.questionKey(QuestionType.WORD, "  Reluctant "))
        val result = CsvQuestionParser.parse(specSample, existing)
        assertEquals(2, result.questions.size)
        assertEquals(CsvErrorKind.DUPLICATE_EXISTING, result.errors.single().kind)
        assertEquals(2, result.errors.single().line)
    }

    @Test
    fun `missing required columns stop the import`() {
        val result = CsvQuestionParser.parse("type,question,option_a,answer\nword,x,a,A\n")
        assertTrue(result.questions.isEmpty())
        assertEquals(CsvErrorKind.MISSING_COLUMNS, result.errors.single().kind)
        assertEquals("option_b,option_c,option_d", result.errors.single().detail)
    }

    @Test
    fun `non UTF-8 bytes are rejected and empty files are reported`() {
        val gbk = "type,question\n不情愿".toByteArray(charset("GBK"))
        assertEquals(CsvErrorKind.NOT_UTF8, CsvQuestionParser.parseBytes(gbk).errors.single().kind)
        assertEquals(CsvErrorKind.EMPTY_FILE, CsvQuestionParser.parse("\n\n").errors.single().kind)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + specSample.toByteArray(Charsets.UTF_8)
        assertEquals(3, CsvQuestionParser.parseBytes(bom).questions.size)
    }

    @Test
    fun `an unclosed quote is reported`() {
        val text = "type,question,option_a,option_b,option_c,option_d,answer\nword,ok,a,b,c,d,A\ncustom,\"never closed,a,b,c,d,A\n"
        val result = CsvQuestionParser.parse(text)
        assertEquals(1, result.questions.size)
        assertEquals(CsvRowError(3, CsvErrorKind.UNCLOSED_QUOTE), result.errors.single())
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.exists() } ?: error("missing $path")

    @Test
    fun `built in English bank and shipped samples are valid`() {
        val builtin = CsvQuestionParser.parseBytes(projectFile("src/main/assets/mindstop/builtin_english.csv").readBytes())
        assertEquals(emptyList<CsvRowError>(), builtin.errors)
        assertEquals(95, builtin.questions.size)
        assertEquals(65, builtin.questions.count { it.type == QuestionType.WORD })
        assertEquals(30, builtin.questions.count { it.type == QuestionType.SYNONYM })
        // Every word explanation repeats the right option, so a wrong answer key would show here.
        builtin.questions.filter { it.type == QuestionType.WORD }.forEach {
            assertTrue(it.question, it.explanation.endsWith(it.options[it.answerIndex]))
        }
        val sample = CsvQuestionParser.parseBytes(projectFile("../docs/mindstop/question-bank-sample.csv").readBytes())
        assertEquals(emptyList<CsvRowError>(), sample.errors)
        assertEquals(5, sample.questions.size)
        val template = CsvQuestionParser.parseBytes(projectFile("../docs/mindstop/question-bank-template.csv").readBytes())
        assertTrue(template.errors.isEmpty() && template.questions.isEmpty())
    }
}

class ChallengeEngineTest {

    private fun bank(): List<ChoiceQuestion> = (1L..12L).map { id ->
        val type = if (id <= 6) QuestionType.WORD else QuestionType.CUSTOM
        ChoiceQuestion(id, type, "q$id", listOf("a$id", "b$id", "c$id", "d$id"), (id % 4).toInt(), "", 1)
    }

    @Test
    fun `shuffled options still point at the right answer`() {
        val questions = bank()
        val map = questions.associateBy { it.id }
        for (seed in 0L until 500L) {
            val snap = ChallengeEngine.snapshot(seed, questions, setOf(QuestionType.WORD, QuestionType.CUSTOM))
            for (position in 0 until 10) {
                val q = ChallengeEngine.questionAt(seed, position, setOf(QuestionType.WORD, QuestionType.CUSTOM), Difficulty.EASY, snap, map)
                q as PresentedQuestion.Choice
                assertEquals(q.source.options.toSet(), q.options.toSet())
                assertEquals(q.source.options[q.source.answerIndex], q.options[q.correctOption])
                assertTrue(ChallengeEngine.isCorrect(q, null, q.correctOption))
                assertFalse(ChallengeEngine.isCorrect(q, null, (q.correctOption + 1) % 4))
            }
        }
    }

    @Test
    fun `reloading a saved challenge shows the same question`() {
        val questions = bank()
        val types = setOf(QuestionType.MATH, QuestionType.WORD, QuestionType.CUSTOM)
        val seed = 987654321L
        val snap = ChallengeEngine.snapshot(seed, questions, types)
        // Simulate a restart: the snapshot goes through its stored text form.
        val restored = ChallengeEngine.decodeIds(ChallengeEngine.encodeIds(snap))
        val restoredTypes = ChallengeEngine.decodeTypes(ChallengeEngine.encodeTypes(types))
        val map = questions.associateBy { it.id }
        for (position in 0 until 30) {
            assertEquals(
                ChallengeEngine.questionAt(seed, position, types, Difficulty.HARD, snap, map),
                ChallengeEngine.questionAt(seed, position, restoredTypes, Difficulty.HARD, restored, map)
            )
        }
    }

    @Test
    fun `a challenge mixes the selected types and does not repeat bank questions early`() {
        val questions = bank()
        val types = setOf(QuestionType.MATH, QuestionType.WORD, QuestionType.CUSTOM)
        val snap = ChallengeEngine.snapshot(5L, questions, types)
        val map = questions.associateBy { it.id }
        val shown = (0 until 30).map { ChallengeEngine.questionAt(5L, it, types, Difficulty.MEDIUM, snap, map) }
        assertEquals(types, shown.map { it.type }.toSet())
        val firstWords = shown.filterIsInstance<PresentedQuestion.Choice>().filter { it.type == QuestionType.WORD }.take(6)
        assertEquals(firstWords.size, firstWords.map { it.source.id }.toSet().size)
    }

    @Test
    fun `empty bank falls back to math`() {
        val q = ChallengeEngine.questionAt(1L, 0, setOf(QuestionType.WORD), Difficulty.EASY, emptyList(), emptyMap())
        assertTrue(q is PresentedQuestion.Math)
        q as PresentedQuestion.Math
        assertTrue(ChallengeEngine.isCorrect(q, q.question.answer.toString(), null))
        assertFalse(ChallengeEngine.isCorrect(q, (q.question.answer + 1).toString(), null))
        assertFalse(ChallengeEngine.isCorrect(q, "", null))
    }

    @Test
    fun `wrong answer policies`() {
        val start = ChallengeEngine.start(3)
        val next = ChallengeEngine.submit(start.copy(correct = 2), false, WrongAnswerPolicy.NEXT, 3)
        assertEquals(ChallengeProgress(3, 2, 1, 1), next)
        var addOne = start
        repeat(5) { addOne = ChallengeEngine.submit(addOne, false, WrongAnswerPolicy.ADD_ONE, 3) }
        assertEquals(6, addOne.required)
        val reset = ChallengeEngine.submit(start.copy(correct = 2), false, WrongAnswerPolicy.RESET, 3)
        assertEquals(0, reset.correct)
        var pass = start
        repeat(3) { pass = ChallengeEngine.submit(pass, true, WrongAnswerPolicy.NEXT, 3) }
        assertTrue(pass.passed)
        assertEquals(pass, ChallengeEngine.submit(pass, true, WrongAnswerPolicy.NEXT, 3))
    }

    @Test
    fun `renewal tiers follow the spec example and respect the maximum`() {
        val config = ChallengeConfig()
        assertEquals(RenewalTier(5, 10), config.tierFor(0))
        assertEquals(RenewalTier(10, 10), config.tierFor(1))
        assertEquals(RenewalTier(20, 5), config.tierFor(2))
        assertNull(config.tierFor(3))
        val more = config.copy(maxRenewals = 5)
        assertEquals(RenewalTier(20, 5), more.tierFor(4))
        assertNull(config.copy(maxRenewals = 0).tierFor(0))
    }

    @Test
    fun `config round trips and broken text falls back to defaults`() {
        val config = ChallengeConfig(
            tiers = listOf(RenewalTier(3, 7), RenewalTier(8, 4)),
            maxRenewals = 4,
            difficulty = Difficulty.HARD,
            types = setOf(QuestionType.CUSTOM, QuestionType.MATH),
            wrongPolicy = WrongAnswerPolicy.RESET,
            cooldownMinutes = 45
        )
        assertEquals(config, ChallengeConfig.decode(config.encode()))
        assertEquals(ChallengeConfig(), ChallengeConfig.decode("garbage;;tiers=0x0;max=-1;types=;cooldown=9999"))
        assertEquals(ChallengeConfig(), ChallengeConfig.decode(null))
    }
}

class CooldownRuleTest {

    private val day = "2026-11-25"
    private val video = "com.example.video"
    private val game = "com.example.game"
    private val videoRule = QuotaRule("video", "Video", RuleScope.APP, setOf(video), null, 10 * 60_000L)
    private val gameRule = QuotaRule("game", "Game", RuleScope.APP, setOf(game), 30 * 60_000L, null)

    private fun eval(pkg: String, session: Map<String, Long>, cooldowns: List<QuotaCooldown>, now: Long) =
        RuleEngine.evaluate(pkg, listOf(videoRule, gameRule), session, session, emptyList(), day, 1L, emptySet(), cooldowns, now)

    @Test
    fun `cooldown blocks the rule's apps until it ends, even with quota left`() {
        val cooldown = listOf(QuotaCooldown("video", endWall = 1_000L, sessionId = 1L))
        val during = eval(video, emptyMap(), cooldown, now = 999L)
        assertTrue(during.blocked)
        assertTrue(during.inCooldown)
        assertEquals(1_000L, during.cooldownUntilWall)
        assertFalse(eval(video, emptyMap(), cooldown, now = 1_000L).blocked)
        assertFalse("other rules are untouched", eval(game, emptyMap(), cooldown, now = 999L).blocked)
    }

    @Test
    fun `cooldown is offered only when per session limits ran out`() {
        val sessionOut = eval(video, mapOf(video to 10 * 60_000L), emptyList(), 0L)
        assertTrue(sessionOut.cooldownHelps)
        val made = RuleEngine.cooldownsFor(sessionOut, 60 * 60_000L, 500L, 1L)
        assertEquals(listOf(QuotaCooldown("video", 500L + 60 * 60_000L, 1L)), made)
        val dailyOut = eval(game, mapOf(game to 30 * 60_000L), emptyList(), 0L)
        assertFalse(dailyOut.cooldownHelps)
        assertTrue(RuleEngine.cooldownsFor(dailyOut, 60 * 60_000L, 500L, 1L).isEmpty())
    }

    @Test
    fun `challenge grants never cover rules that were not exhausted`() {
        val shared = QuotaRule("shared", "Shared", RuleScope.GLOBAL, emptySet(), 20 * 60_000L, null)
        val rules = listOf(videoRule, shared)
        val usage = mapOf(video to 10 * 60_000L)
        val verdict = RuleEngine.evaluate(video, rules, usage, usage, emptyList(), day, 1L, emptySet())
        val grants = RuleEngine.grantsToUnlock(verdict, 10 * 60_000L, day, 1L)
        assertEquals(listOf("video"), grants.map { it.ruleId })
        assertNotEquals(null, verdict.binding)
    }

    @Test
    fun `forced new session starts even inside the gap`() {
        val acc = ForegroundAccountant(10 * 60_000L)
        acc.onForeground(video, 0L, 1_000L)
        val first = acc.sessionId
        acc.onForeground(null, 1_000L, 2_000L)
        acc.forceNewSession()
        acc.onForeground(video, 2_000L, 3_000L)
        assertNotNull(acc.sessionId)
        assertNotEquals(first, acc.sessionId)
        val second = acc.sessionId
        acc.onForeground(null, 3_000L, 4_000L)
        acc.onForeground(video, 4_000L, 5_000L)
        assertEquals("the flag is used once", second, acc.sessionId)
    }
}
