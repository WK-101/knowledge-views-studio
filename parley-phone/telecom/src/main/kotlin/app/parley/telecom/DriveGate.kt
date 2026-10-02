package app.parley.telecom

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.CarDevice
import app.parley.common.calls.DriveProfile
import app.parley.common.calls.DriveProfileConfig

/**
 * The drive profile on the call path (I11; [DriveProfile] decides). Whether a marked car is connected is read from
 * the audio outputs (a car's hands-free and media links are Bluetooth outputs with its address; BLUETOOTH_CONNECT is
 * already held), so nothing runs in the background and the profile is off the moment the car disconnects. While it
 * is on: the caller's name is said once through the car, the auto-answer scope is handed to [AutoAnswerGate], and
 * unknown callers may ring silently. Main thread only.
 */
internal class DriveGate(private val config: () -> DriveProfileConfig) {
    private var checkedAt = -1L
    private var car: CarDevice? = null

    /** Calls the drive profile silenced (the status pill says why). */
    private val silenced = HashSet<String>()
    private val announcer = Announcer()

    fun config(): DriveProfileConfig = runCatching { config.invoke() }.getOrDefault(DriveProfileConfig())

    /** The marked car connected now, or null (read at most every [CHECK_MS]; nothing is read with no car marked). */
    fun car(context: Context): CarDevice? {
        val cfg = config()
        if (!cfg.enabled) {
            car = null
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (checkedAt < 0 || now - checkedAt >= CHECK_MS) {
            checkedAt = now
            car = DriveProfile.connectedCar(cfg, CarAudio.connected(context))
        }
        return car
    }

    fun driving(context: Context): Boolean = car(context) != null

    /** The drive profile's auto-answer scope now, or null. */
    fun answerScope(context: Context): AutoAnswer.DriveScope? = DriveProfile.answerScope(config(), driving(context))

    /** Says "<name> is calling" once for call [id] when [DriveProfile.announces] lets it. */
    fun announce(context: Context, id: String, name: String, caller: DriveProfile.Caller) {
        val c = caller.copy(quiet = caller.quiet || !ringerOn(context))
        if (!DriveProfile.announces(config(), driving(context), c)) return
        announcer.say(context, id, context.getString(R.string.incall_is_calling, name))
    }

    /** Whether to let an unknown caller ring silently now (the caller of this asks [DriveProfile.silences]). */
    fun silences(context: Context, caller: DriveProfile.Caller): Boolean = DriveProfile.silences(config(), driving(context), caller)

    /** Only worth asking whether an unknown number is saved when silencing could follow. */
    fun mightSilence(context: Context): Boolean = config().silenceUnknown && driving(context)

    fun markSilenced(id: String) {
        silenced += id
    }

    fun silencedHere(id: String): Boolean = id in silenced

    /** The call stopped ringing (answered, declined, silenced): the name isn't said over it. */
    fun quiet(id: String? = null) = announcer.stop(id)

    fun forget(id: String) {
        silenced -= id
        announcer.stop(id)
        announcer.forget(id)
    }

    /** On-device text-to-speech, once per call, as navigation guidance: cars play it through their speakers. */
    private class Announcer {
        private var tts: TextToSpeech? = null
        private var speakingFor: String? = null
        private val said = HashSet<String>()
        private val main = Handler(Looper.getMainLooper())

        fun say(context: Context, id: String, text: String) {
            if (!said.add(id)) return
            stop(null)
            speakingFor = id
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context.applicationContext) { status ->
                val e = engine
                if (status != TextToSpeech.SUCCESS || e == null || tts !== e) return@TextToSpeech
                runCatching {
                    e.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            main.post { if (tts === e) stop(null) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            main.post { if (tts === e) stop(null) }
                        }
                    })
                    e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "parley-drive-$id")
                }
            }
            tts = engine
        }

        /** Stops speaking (for [id] only, when given). */
        fun stop(id: String?) {
            if (id != null && id != speakingFor) return
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            speakingFor = null
        }

        fun forget(id: String) {
            said -= id
        }
    }

    companion object {
        private const val CHECK_MS = 1_000L

        /** The phone rings aloud: ringer on and Do Not Disturb off (a silent phone stays silent, in the car too). */
        fun ringerOn(context: Context): Boolean = runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            val nm = context.getSystemService(NotificationManager::class.java)
            val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                (filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        }.getOrDefault(false)
    }
}

/**
 * The Bluetooth audio devices connected now (a car's hands-free and media links), with their addresses and names.
 * Shared by the call path and Settings › Calls › Drive profile ("Connected now"). Android hides the addresses from
 * apps without BLUETOOTH_CONNECT; the drive profile then matches by name.
 */
object CarAudio {
    // AudioDeviceInfo.TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER (Android 12); older versions never report them.
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27

    /** Bluetooth outputs a car can be: hands-free (SCO), media (A2DP) and LE audio. */
    private val CAR_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER)

    fun connected(context: Context): List<DriveProfile.Connected> = runCatching {
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in CAR_TYPES }
            .map { DriveProfile.Connected(it.address, it.productName?.toString()) }
            .distinct()
    }.getOrDefault(emptyList())
}
