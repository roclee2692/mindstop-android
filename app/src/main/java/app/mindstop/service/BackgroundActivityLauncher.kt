package app.mindstop.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Reliable Activity launcher for services on Android 14–16 / HyperOS.
 *
 * Uses Android 14+ explicit BAL opt-in via [ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED]
 * on a [PendingIntent] when available, with automatic fallback to direct [Context.startActivity].
 */
object BackgroundActivityLauncher {

    private const val TAG = "MindstopBAL"

    fun launch(context: Context, intent: Intent, requestCode: Int): Boolean {
        val prepared = Intent(intent).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val pi = PendingIntent.getActivity(
                    context,
                    requestCode,
                    prepared,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val opts = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                pi.send(context, 0, null, null, null, null, opts.toBundle())
                return true
            } catch (e: Exception) {
                Log.w(TAG, "PendingIntent BAL launch failed, falling back to startActivity", e)
            }
        }
        return try {
            context.startActivity(prepared)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity failed for ${prepared.component}", e)
            false
        }
    }
}
