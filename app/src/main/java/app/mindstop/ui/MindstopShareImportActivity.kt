package app.mindstop.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.MoneyFormat
import app.mindstop.core.ShareLinkParser
import app.mindstop.service.ShoppingRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R

/**
 * Android ACTION_SEND (`text/plain`) receiver for importing shared product links from e-commerce
 * apps or browsers directly into the Product Candidate Pool or 24h Purchase Backlog (v0.3 §19, §24).
 */
class MindstopShareImportActivity : AppCompatActivity() {

    private lateinit var repo: ShoppingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ShoppingRepository(this)
        val raw = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        } else {
            ""
        }
        val parsed = ShareLinkParser.parse(raw)
        if (parsed.title.isBlank() && parsed.url.isBlank()) {
            finish()
            return
        }
        showImportDialog(parsed.title, parsed.url)
    }

    private fun showImportDialog(initialTitle: String, url: String) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_item_name_hint)
            setText(initialTitle.ifBlank { url.take(40) })
        }
        val priceInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_price_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val reasonInput = EditText(this).apply {
            hint = getString(R.string.mindstop_shop_reason_or_spec_hint)
        }
        val urgentSwitch = MaterialSwitch(this).apply {
            setText(R.string.mindstop_shop_backlog_urgent_switch)
        }
        box.addView(nameInput)
        box.addView(priceInput)
        box.addView(reasonInput)
        box.addView(urgentSwitch)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mindstop_shop_share_title)
            .setView(box)
            .setPositiveButton(R.string.mindstop_shop_share_save_backlog) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { initialTitle }
                val cents = MoneyFormat.parseYuanToCents(priceInput.text?.toString()) ?: 0L
                val reason = reasonInput.text?.toString()?.trim().orEmpty()
                val urgent = urgentSwitch.isChecked
                lifecycleScope.launch {
                    val (_, rewarded) = withContext(Dispatchers.IO) {
                        repo.addBacklogItem(
                            name = name,
                            estimatedCents = cents,
                            reasonNeeded = reason,
                            hasAlternative = false,
                            isUrgent = urgent,
                            url = url,
                            nowWall = System.currentTimeMillis()
                        )
                    }
                    val msg = if (rewarded) R.string.mindstop_shop_backlog_saved_reward else R.string.mindstop_shop_backlog_saved
                    Toast.makeText(this@MindstopShareImportActivity, msg, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .setNeutralButton(R.string.mindstop_shop_share_save_candidate) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { initialTitle }
                val cents = MoneyFormat.parseYuanToCents(priceInput.text?.toString()) ?: 0L
                val spec = reasonInput.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val now = System.currentTimeMillis()
                        val task = repo.activeTasks().firstOrNull()
                            ?: repo.createTask(itemName = name, purpose = "", budgetCents = cents, nowWall = now)
                        repo.addCandidate(
                            taskId = task.id,
                            name = name,
                            url = url,
                            brandModelSpec = spec,
                            priceCents = cents,
                            source = "share_intent",
                            nowWall = now
                        )
                    }
                    Toast.makeText(this@MindstopShareImportActivity, R.string.mindstop_shop_candidate_saved, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
