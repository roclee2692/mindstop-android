package app.mindstop.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class QuestionType(val code: String) {
    MATH("math"), WORD("word"), SYNONYM("synonym"), CUSTOM("custom");

    companion object {
        val BANK_TYPES = listOf(WORD, SYNONYM, CUSTOM)
        fun fromCode(code: String): QuestionType? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/** A four option question from the bank. [answerIndex] points into [options], 0 is A. */
data class ChoiceQuestion(
    val id: Long,
    val type: QuestionType,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
    val difficulty: Int
)

enum class CsvErrorKind {
    NOT_UTF8, EMPTY_FILE, MISSING_COLUMNS, UNCLOSED_QUOTE, MISSING_FIELD, BAD_TYPE, BAD_ANSWER,
    BLANK_OPTION, DUPLICATE_OPTION, BAD_DIFFICULTY, TOO_LONG, DUPLICATE_IN_FILE, DUPLICATE_EXISTING
}

/** [line] is the 1 based line in the file where the record starts; 0 for file level errors. */
data class CsvRowError(val line: Int, val kind: CsvErrorKind, val detail: String = "")

data class ParsedQuestion(
    val line: Int,
    val type: QuestionType,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
    val difficulty: Int
) {
    val key: String get() = CsvQuestionParser.questionKey(type, question)
}

data class CsvImportResult(
    val questions: List<ParsedQuestion>,
    val errors: List<CsvRowError>,
    val dataRows: Int
)

/**
 * Reads the question bank CSV that users export from Excel or Numbers:
 * type,question,option_a,option_b,option_c,option_d,answer[,explanation][,difficulty]
 * Handles a UTF-8 BOM, quoted fields with commas, doubled quotes, line breaks inside quotes and
 * CRLF. Bad rows are reported with their line number instead of being dropped silently.
 */
object CsvQuestionParser {

    val REQUIRED = listOf("type", "question", "option_a", "option_b", "option_c", "option_d", "answer")
    const val MAX_FIELD_LENGTH = 500
    private val OPTION_COLUMNS = listOf("option_a", "option_b", "option_c", "option_d")

    /** Strict UTF-8 decoding. Returns null for GBK and other encodings instead of garbling text. */
    fun decodeUtf8(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun parseBytes(bytes: ByteArray, existingKeys: Set<String> = emptySet()): CsvImportResult {
        val text = decodeUtf8(bytes)
            ?: return CsvImportResult(emptyList(), listOf(CsvRowError(0, CsvErrorKind.NOT_UTF8)), 0)
        return parse(text, existingKeys)
    }

    fun questionKey(type: QuestionType, question: String): String =
        type.code + "|" + normalize(question)

    private fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase()

    fun parse(rawText: String, existingKeys: Set<String> = emptySet()): CsvImportResult {
        val text = rawText.removePrefix("\uFEFF")
        val (records, unclosedLine) = splitRecords(text)
        val errors = ArrayList<CsvRowError>()
        val nonBlank = records.filter { record -> record.fields.any { it.isNotBlank() } }
        if (nonBlank.isEmpty()) {
            return CsvImportResult(emptyList(), listOf(CsvRowError(0, CsvErrorKind.EMPTY_FILE)), 0)
        }

        val header = nonBlank.first()
        val columns = HashMap<String, Int>()
        header.fields.forEachIndexed { index, name ->
            val key = name.removePrefix("\uFEFF").trim().lowercase()
            if (key.isNotEmpty() && key !in columns) columns[key] = index
        }
        val missing = REQUIRED.filter { it !in columns }
        if (missing.isNotEmpty()) {
            return CsvImportResult(
                emptyList(),
                listOf(CsvRowError(header.line, CsvErrorKind.MISSING_COLUMNS, missing.joinToString(","))),
                nonBlank.size - 1
            )
        }

        val questions = ArrayList<ParsedQuestion>()
        val seen = HashMap<String, Int>()
        for (record in nonBlank.drop(1)) {
            fun field(name: String): String {
                val index = columns[name] ?: return ""
                return record.fields.getOrNull(index)?.trim().orEmpty()
            }
            val result = validate(record.line, ::field)
            if (result is ParsedQuestion) {
                val key = result.key
                val firstLine = seen[key]
                when {
                    key in existingKeys -> errors.add(CsvRowError(record.line, CsvErrorKind.DUPLICATE_EXISTING))
                    firstLine != null -> errors.add(CsvRowError(record.line, CsvErrorKind.DUPLICATE_IN_FILE, firstLine.toString()))
                    else -> {
                        seen[key] = record.line
                        questions.add(result)
                    }
                }
            } else {
                errors.add(result as CsvRowError)
            }
        }
        if (unclosedLine != null) errors.add(CsvRowError(unclosedLine, CsvErrorKind.UNCLOSED_QUOTE))
        return CsvImportResult(questions, errors.sortedBy { it.line }, nonBlank.size - 1)
    }

    private fun validate(line: Int, field: (String) -> String): Any {
        val all = REQUIRED + listOf("explanation", "difficulty")
        all.firstOrNull { field(it).length > MAX_FIELD_LENGTH }?.let {
            return CsvRowError(line, CsvErrorKind.TOO_LONG, it)
        }
        val typeText = field("type")
        if (typeText.isEmpty()) return CsvRowError(line, CsvErrorKind.MISSING_FIELD, "type")
        val type = QuestionType.fromCode(typeText)
        if (type == null || type == QuestionType.MATH) return CsvRowError(line, CsvErrorKind.BAD_TYPE, typeText)
        val question = field("question")
        if (question.isEmpty()) return CsvRowError(line, CsvErrorKind.MISSING_FIELD, "question")
        val options = OPTION_COLUMNS.map { field(it) }
        options.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 }?.let {
            return CsvRowError(line, CsvErrorKind.BLANK_OPTION, OPTION_COLUMNS[it])
        }
        if (options.map { normalize(it) }.toSet().size != options.size) {
            return CsvRowError(line, CsvErrorKind.DUPLICATE_OPTION)
        }
        val answerText = field("answer")
        if (answerText.isEmpty()) return CsvRowError(line, CsvErrorKind.MISSING_FIELD, "answer")
        val answerIndex = listOf("A", "B", "C", "D").indexOf(answerText.uppercase())
        if (answerIndex < 0) return CsvRowError(line, CsvErrorKind.BAD_ANSWER, answerText)
        val difficultyText = field("difficulty")
        val difficulty = if (difficultyText.isEmpty()) 1 else difficultyText.toIntOrNull()
        if (difficulty == null || difficulty !in 1..3) {
            return CsvRowError(line, CsvErrorKind.BAD_DIFFICULTY, difficultyText)
        }
        return ParsedQuestion(line, type, question, options, answerIndex, field("explanation"), difficulty)
    }

    private class Record(val line: Int, val fields: List<String>)

    /** Splits text into records. Returns the start line of an unclosed quoted record, if any. */
    private fun splitRecords(text: String): Pair<List<Record>, Int?> {
        val records = ArrayList<Record>()
        var fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        current.append('"'); i++
                    }
                    ch == '"' -> inQuotes = false
                    else -> {
                        if (ch == '\n') line++
                        current.append(ch)
                    }
                }
            } else {
                when (ch) {
                    '"' -> if (current.isBlank()) {
                        current.setLength(0); inQuotes = true
                    } else {
                        current.append(ch)
                    }
                    ',' -> {
                        fields.add(current.toString()); current.setLength(0)
                    }
                    '\r', '\n' -> {
                        if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        fields.add(current.toString()); current.setLength(0)
                        records.add(Record(recordLine, fields))
                        fields = ArrayList()
                        line++
                        recordLine = line
                    }
                    else -> current.append(ch)
                }
            }
            i++
        }
        val unclosed = if (inQuotes) recordLine else null
        if (!inQuotes && (current.isNotEmpty() || fields.isNotEmpty())) {
            fields.add(current.toString())
            records.add(Record(recordLine, fields))
        }
        return records to unclosed
    }
}
