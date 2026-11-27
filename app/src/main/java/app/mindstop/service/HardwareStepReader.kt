package app.mindstop.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot reader for the hardware [Sensor.TYPE_STEP_COUNTER] cumulative total since boot.
 *
 * Adapted from WakeNudge (`HardwareStepTotal` in `WakeProof.kt`, Apache-2.0,
 * https://github.com/riyadmondol2006/WakeNudge): uses ONLY the hardware step counter so steps
 * walked while the phone is idle are still captured, avoids accelerometer shake spoofing, and
 * gracefully returns `null` whenever the sensor is absent, permission is denied, or read times out.
 */
object HardwareStepReader {

    private const val READ_TIMEOUT_MS = 3_000L

    fun isAvailable(context: Context): Boolean {
        val manager = context.getSystemService(SensorManager::class.java) ?: return false
        val counter = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return false
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        return counter != null && granted
    }

    suspend fun readTotalSteps(context: Context): Float? = try {
        val manager = context.getSystemService(SensorManager::class.java)
        val counter = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (manager == null || counter == null || !granted) {
            null
        } else {
            withTimeoutOrNull(READ_TIMEOUT_MS) { firstValue(manager, counter) }?.takeUnless { it.isNaN() }
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    private suspend fun firstValue(manager: SensorManager, counter: Sensor): Float =
        suspendCancellableCoroutine { cont ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    manager.unregisterListener(this)
                    if (cont.isActive) cont.resumeWith(Result.success(event.values[0]))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (!manager.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL) && cont.isActive) {
                cont.resumeWith(Result.success(Float.NaN))
            }
            // Also runs immediately if cancellation happened during registration.
            cont.invokeOnCancellation { manager.unregisterListener(listener) }
        }
}
