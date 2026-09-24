package app.parley.calls

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One reading of the proximity sensor, for the pocket-dial guard (V8). No permission needed. The sensor reports its
 * current state right after a listener registers; if it doesn't within [timeoutMs] (or there is no sensor) the answer
 * is null, which never blocks a call.
 */
object ProximityProbe {
    suspend fun isCovered(context: Context, timeoutMs: Long = 400): Boolean? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        // "Near" is anything below the sensor's range (many sensors only report 0 or the maximum).
                        val distance = event.values.firstOrNull() ?: return
                        if (cont.isActive) cont.resume(distance < minOf(sensor.maximumRange, NEAR_CM))
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    private const val NEAR_CM = 5f
}
