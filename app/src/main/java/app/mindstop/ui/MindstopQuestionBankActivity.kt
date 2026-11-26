package app.mindstop.ui

import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.mindstop.core.CsvErrorKind
import app.mindstop.core.CsvRowError
import app.mindstop.core.QuestionType
import app.mindstop.data.BankQuestionEntity
import app.mindstop.service.ChallengeRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopQuestionBankBinding
import neth.iecal.curbox.databinding.ItemMindstopBankQuestionBinding

/** Lists the local question bank, imports CSV files, and turns questions on, off or deletes them. */
class MindstopQuestionBankActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMindstopQuestionBankBinding
    private lateinit var challenges: ChallengeRepository
    private val adapter = BankAdapter()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopQuestionBankBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.bankRoot)
        MindstopUi.setupToolbar(this, binding.bankToolbar, R.string.mindstop_open_question_bank)
        challenges = ChallengeRepository(this)

        binding.bankList.layoutManager = LinearLayoutManager(this)
        binding.bankList.adapter = adapter
        binding.bankResult.movementMethod = ScrollingMovementMethod()
        // Phones label .csv files inconsistently (text/csv, text/comma-separated-values,
        // application/vnd.ms-excel, octet-stream), so any file can be picked and the parser decides.
        binding.bankImport.setOnClickListener { picker.launch(arrayOf("*/*")) }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { challenges.ensureBuiltinBank(System.currentTimeMillis()) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                challenges.observeBank().collect { render(it) }
            }
        }
    }

    private fun render(questions: List<BankQuestionEntity>) {
        adapter.submitList(questions)
        binding.bankEmpty.visibility = if (questions.isEmpty()) View.VISIBLE else View.GONE
        binding.bankSummary.text = getString(
            R.string.mindstop_bank_summary,
            questions.count { it.type == QuestionType.WORD.code },
            questions.count { it.type == QuestionType.SYNONYM.code },
            questions.count { it.type == QuestionType.CUSTOM.code },
            questions.count { !it.enabled }
        )
    }

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = input.readNBytesCompat(MAX_BYTES + 1)
                        buffer
                    }
                } catch (_: Exception) {
                    null
                }
            }
            when {
                bytes == null -> showResult(getString(R.string.mindstop_bank_read_failed))
                bytes.size > MAX_BYTES -> showResult(getString(R.string.mindstop_bank_too_big))
                else -> {
                    val outcome = withContext(Dispatchers.IO) { challenges.importCsv(bytes, System.currentTimeMillis()) }
                    val errors = outcome.result.errors
                    val text = StringBuilder()
                    if (errors.isEmpty()) {
                        text.append(getString(R.string.mindstop_bank_import_ok, outcome.inserted))
                    } else {
                        text.append(getString(R.string.mindstop_bank_import_result, outcome.inserted, errors.size))
                        errors.take(MAX_LISTED_ERRORS).forEach { text.append('\n').append(describe(it)) }
                        if (errors.size > MAX_LISTED_ERRORS) {
                            text.append('\n').append(getString(R.string.mindstop_bank_more_errors, errors.size - MAX_LISTED_ERRORS))
                        }
                    }
                    showResult(text.toString())
                }
            }
        }
    }

    private fun showResult(text: String) {
        binding.bankResult.text = text
        binding.bankResult.visibility = View.VISIBLE
        binding.bankResult.scrollTo(0, 0)
    }

    private fun describe(error: CsvRowError): String {
        val message = when (error.kind) {
            CsvErrorKind.NOT_UTF8 -> getString(R.string.mindstop_csv_not_utf8)
            CsvErrorKind.EMPTY_FILE -> getString(R.string.mindstop_csv_empty)
            CsvErrorKind.MISSING_COLUMNS -> getString(R.string.mindstop_csv_missing_columns, error.detail)
            CsvErrorKind.UNCLOSED_QUOTE -> getString(R.string.mindstop_csv_unclosed_quote)
            CsvErrorKind.MISSING_FIELD, CsvErrorKind.BLANK_OPTION -> getString(R.string.mindstop_csv_missing_field, error.detail)
            CsvErrorKind.BAD_TYPE -> getString(R.string.mindstop_csv_bad_type, error.detail)
            CsvErrorKind.BAD_ANSWER -> getString(R.string.mindstop_csv_bad_answer, error.detail)
            CsvErrorKind.DUPLICATE_OPTION -> getString(R.string.mindstop_csv_duplicate_option)
            CsvErrorKind.BAD_DIFFICULTY -> getString(R.string.mindstop_csv_bad_difficulty, error.detail)
            CsvErrorKind.TOO_LONG -> getString(R.string.mindstop_csv_too_long, error.detail)
            CsvErrorKind.DUPLICATE_IN_FILE -> getString(R.string.mindstop_csv_duplicate_in_file, error.detail)
            CsvErrorKind.DUPLICATE_EXISTING -> getString(R.string.mindstop_csv_duplicate_existing)
        }
        return if (error.line > 0) getString(R.string.mindstop_csv_line, error.line, message) else message
    }

    private fun typeLabel(code: String): String = getString(
        when (QuestionType.fromCode(code)) {
            QuestionType.WORD -> R.string.mindstop_type_word
            QuestionType.SYNONYM -> R.string.mindstop_type_synonym
            else -> R.string.mindstop_type_custom
        }
    )

    private fun confirmDelete(question: BankQuestionEntity) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.mindstop_bank_delete_confirm) + "\n\n" + question.question)
            .setPositiveButton(R.string.mindstop_bank_delete) { _, _ ->
                lifecycleScope.launch { withContext(Dispatchers.IO) { challenges.deleteQuestion(question.id) } }
            }
            .setNegativeButton(R.string.mindstop_bank_cancel, null)
            .show()
    }

    private inner class BankAdapter : ListAdapter<BankQuestionEntity, BankAdapter.Holder>(DIFF) {

        inner class Holder(val row: ItemMindstopBankQuestionBinding) : RecyclerView.ViewHolder(row.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemMindstopBankQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val options = listOf(item.optionA, item.optionB, item.optionC, item.optionD)
            val letter = listOf("A", "B", "C", "D").getOrElse(item.answer) { "?" }
            holder.row.bankRowQuestion.text = item.question
            holder.row.bankRowDetail.text = typeLabel(item.type) + " · " +
                getString(R.string.mindstop_bank_answer, "$letter. ${options.getOrElse(item.answer) { "" }}")
            holder.row.bankRowEnabled.setOnCheckedChangeListener(null)
            holder.row.bankRowEnabled.isChecked = item.enabled
            holder.row.bankRowEnabled.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { withContext(Dispatchers.IO) { challenges.setQuestionEnabled(item.id, checked) } }
            }
            holder.row.bankRowDelete.setOnClickListener { confirmDelete(item) }
        }
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_LISTED_ERRORS = 50

        private val DIFF = object : DiffUtil.ItemCallback<BankQuestionEntity>() {
            override fun areItemsTheSame(a: BankQuestionEntity, b: BankQuestionEntity) = a.id == b.id
            override fun areContentsTheSame(a: BankQuestionEntity, b: BankQuestionEntity) = a == b
        }
    }
}

/** Reads at most [limit] bytes; InputStream.readNBytes needs API 33. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (read < 0) break
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}
