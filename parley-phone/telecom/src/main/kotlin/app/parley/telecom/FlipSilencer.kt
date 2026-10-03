package app.parley.telecom

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import app.parley.common.calls.EmergencyPolicy
import app.parley.common.calls.EmergencyPolicy.Safeguard
import app.parley.common.calls.FlipDetector

/**
 * Settings › Calls › Answering › "Flip to silence": listens to the gravity sensor (the accelerometer where there is
 * none; neither needs a permission) only while a call rings on its own, and silences it like the Silence button when
 * the phone is turned face down ([FlipDetector]). It never declines. Off while another call goes on: Telecom plays
 * only a quiet waiting tone then, and the phone may be at the user's ear. Never for an emergency call or during the
 * emergency call-back window ([Safeguard.SILENCE]): the operator calling back must be heard.
 */
internal class FlipSilencer(
    context: Context,
    private val inEmergencyWindow: () -> Boolean = { runCatching { ScreeningGuard.inEmergencyWindow(context) }.getOrDefault(false) },
    private val silence: (String) -> Unit = { CallManager.ignore(it) },
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = FlipDetector()

    /** The ringing call being watched, or null while not listening. */
    private var ringingId: String? = null

    /** The ringing call being watched (tests). */
    internal val watching: String? get() = ringingId

    fun update(calls: List<CallUi>, enabled: Boolean) {
        val live = calls.filter { it.isLive }
        val ringing = live.singleOrNull()?.takeIf { it.state == CallState.RINGING && !it.silenced }
        val want = ringing?.id?.takeIf { enabled && sensor != null && !bypassed(ringing) }
        if (want == ringingId) return
        stop()
        if (want != null) {
            detector.reset()
            ringingId = want
            sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun bypassed(call: CallUi): Boolean =
        EmergencyPolicy.bypasses(Safeguard.SILENCE, EmergencyPolicy.Facts(emergencyNumber = call.isEmergency, inWindow = inEmergencyWindow()))

    fun stop() {
        if (ringingId != null) sensors?.unregisterListener(this)
        ringingId = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val id = ringingId ?: return
        val v = event.values
        if (v.size < 3) return
        if (detector.onSample(v[0], v[1], v[2], SystemClock.elapsedRealtime())) {
            stop()
            silence(id)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
