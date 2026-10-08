package app.parley.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import app.parley.common.Decision
import app.parley.common.Verification
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.MenuPress
import app.parley.common.calls.RingFacts
import app.parley.common.calls.SpeakerDefault
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * Real `android.telecom.Call` objects for Robolectric tests, without Telecom: a hidden `Phone` builds them from
 * `ParcelableCall`s (as Telecom would), and every command a call sends (answer, hold, DTMF…) lands in [sent] instead
 * of a binder. [update] changes a call the way Telecom reports a change.
 */
internal class FakeTelecom {
    /** The commands the calls sent, e.g. "answerCall(t1, 0)", in order. */
    val sent = ArrayList<String>()

    private val adapterInterface = Class.forName("com.android.internal.telecom.IInCallAdapter")
    private val binder = Proxy.newProxyInstance(adapterInterface.classLoader, arrayOf(adapterInterface)) { proxy, method, args ->
        when (method.name) {
            "asBinder" -> null
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "FakeInCallAdapter"
            else -> {
                sent += method.name + args.orEmpty().joinToString(", ", "(", ")")
                null
            }
        }
    }
    private val adapterClass = Class.forName("android.telecom.InCallAdapter")
    private val adapter = adapterClass.getDeclaredConstructor(adapterInterface).apply { isAccessible = true }.newInstance(binder)
    private val phoneClass = Class.forName("android.telecom.Phone")
    private val phone = phoneClass.getDeclaredConstructor(adapterClass, String::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(adapter, "app.parley", 35)

    /** What Telecom says about one call. */
    data class Spec(
        val id: String,
        val state: Int,
        val number: String? = "+15551234567",
        val incoming: Boolean = state == Call.STATE_RINGING,
        val capabilities: Int = 0,
        val properties: Int = 0,
        val presentation: Int = TelecomManager.PRESENTATION_ALLOWED,
        val connectTimeMillis: Long = 0,
        val videoState: Int = VideoProfile.STATE_AUDIO_ONLY,
        val disconnectCause: DisconnectCause = DisconnectCause(DisconnectCause.UNKNOWN),
        val children: List<String> = emptyList(),
        val parent: String? = null,
        val conferenceable: List<String> = emptyList(),
        val account: PhoneAccountHandle? = null,
        val extras: Bundle = Bundle(),
    )

    private val specs = HashMap<String, Spec>()

    /** Kept here too: the hidden Phone forgets a call once it reports it disconnected, as Telecom's does. */
    private val calls = HashMap<String, Call>()

    fun spec(id: String): Spec = specs.getValue(id)

    /** A call Telecom just added (not yet handed to [CallManager]). */
    fun call(spec: Spec): Call {
        specs[spec.id] = spec
        invoke("internalAddCall", parcel(spec))
        val call = phoneClass.getDeclaredMethod("internalGetCallByTelecomId", String::class.java)
            .apply { isAccessible = true }.invoke(phone, spec.id) as Call
        calls[spec.id] = call
        return call
    }

    /** Telecom reports a change to a call. Callbacks are delivered on the main looper. */
    fun update(id: String, change: (Spec) -> Spec): Call {
        val next = change(specs.getValue(id))
        specs[id] = next
        invoke("internalUpdateCall", parcel(next))
        idle()
        return callById(id)
    }

    fun callById(id: String): Call = calls.getValue(id)

    private fun invoke(name: String, arg: Any) {
        val pc = Class.forName("android.telecom.ParcelableCall")
        phoneClass.getDeclaredMethod(name, pc).apply { isAccessible = true }.invoke(phone, arg)
    }

    private fun parcel(s: Spec): Any {
        val builderClass = Class.forName("android.telecom.ParcelableCall\$ParcelableCallBuilder")
        val b = builderClass.getDeclaredConstructor().newInstance()
        fun set(name: String, type: Class<*>, value: Any?) {
            builderClass.getMethod(name, type).invoke(b, value)
        }
        val int = Int::class.javaPrimitiveType!!
        set("setId", String::class.java, s.id)
        set("setState", int, s.state)
        set("setDisconnectCause", DisconnectCause::class.java, s.disconnectCause)
        set("setCannedSmsResponses", List::class.java, emptyList<String>())
        set("setCapabilities", int, s.capabilities)
        set("setProperties", int, s.properties)
        set("setConnectTimeMillis", Long::class.javaPrimitiveType!!, s.connectTimeMillis)
        set("setHandle", Uri::class.java, s.number?.let { Uri.fromParts("tel", it, null) })
        set("setHandlePresentation", int, s.presentation)
        set("setCallerDisplayNamePresentation", int, TelecomManager.PRESENTATION_ALLOWED)
        set("setAccountHandle", PhoneAccountHandle::class.java, s.account)
        set("setParentCallId", String::class.java, s.parent)
        set("setChildCallIds", List::class.java, s.children)
        set("setVideoState", int, s.videoState)
        set("setConferenceableCallIds", List::class.java, s.conferenceable)
        set("setIntentExtras", Bundle::class.java, Bundle())
        set("setExtras", Bundle::class.java, s.extras)
        set("setCallDirection", int, if (s.incoming) Call.Details.DIRECTION_INCOMING else Call.Details.DIRECTION_OUTGOING)
        return builderClass.getMethod("createParcelableCall").invoke(b)!!
    }

    companion object {
        fun idle() = shadowOf(Looper.getMainLooper()).idle()

        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
}

/** The app side of the call path, recording what it was told. Every lookup answers at once. */
internal class FakeDependencies : TelecomDependencies {
    val contacts = HashMap<String, CallerDisplay>()

    /** Screening's answer; null: screening is off. */
    var screen: ScreenOutcome? = null
    var screenDelayMs = 0L
    var screened = 0
    var screenFails = false

    /** "Block & decline": the new rule's id, and how long writing it takes. */
    var blockRule: Long? = 41
    var blockDelayMs = 0L
    val undone = ArrayList<Long>()
    var emergency = setOf("112", "911", "999")
    var autoAnswerConfig = CallExtrasConfig()
    var speaker = SpeakerDefault.OFF

    /** Saved callers the lookup doesn't show (private contacts in discreet mode). */
    val savedHidden = HashSet<String>()
    var overQuota = false
    val usage = ArrayList<String>()
    val quality = ArrayList<CallQualityFacts>()
    val ringFacts = ArrayList<RingFacts>()
    val ended = ArrayList<String>()
    val menuKeys = ArrayList<List<MenuPress>>()

    /** How long the "is it saved?" lookup takes (the first lookup answers at once). */
    var savedDelayMs = 0L

    override suspend fun callerInfo(number: String, accountId: String?): CallerDisplay? = contacts[number]
    override fun screeningActive() = screen != null
    override suspend fun screenCall(
        number: String?,
        hidden: Boolean,
        verification: Verification,
        accountId: String?,
        callerName: String?,
        callerNamePresentation: Int,
    ): ScreenOutcome {
        screened++
        if (screenDelayMs > 0) delay(screenDelayMs)
        if (screenFails) error("screening failed")
        return screen ?: ScreenOutcome(Decision.Allow)
    }
    override suspend fun blockForDecline(number: String): Long? {
        if (blockDelayMs > 0) delay(blockDelayMs)
        return blockRule
    }
    override suspend fun undoBlockForDecline(ruleId: Long) {
        undone += ruleId
    }
    override fun isEmergencyNumber(number: String) = number in emergency
    override suspend fun preferredAccountId(number: String): String? = null
    override fun autoAnswer() = autoAnswerConfig
    override fun speakerDefault() = speaker
    override suspend fun isSavedCaller(number: String, accountId: String?): Boolean {
        if (savedDelayMs > 0) kotlinx.coroutines.delay(savedDelayMs)
        return number in contacts || number in savedHidden
    }
    override suspend fun silenceOverQuota(number: String, accountId: String?) = overQuota
    override fun onCallUsage(number: String?, accountId: String?, incoming: Boolean, connectTimeMillis: Long, durationSec: Long) {
        usage += number.orEmpty()
    }
    override fun onCallQuality(number: String?, facts: CallQualityFacts) {
        quality += facts
    }
    override fun onRingFacts(number: String?, facts: RingFacts) {
        ringFacts += facts
    }
    override fun onCallEnded(number: String?, incoming: Boolean, connectTimeMillis: Long) {
        ended += number.orEmpty()
    }
    override fun onMenuKeys(number: String, accountId: String?, presses: List<MenuPress>) {
        menuKeys += presses
    }
    override val appearance = MutableStateFlow(InCallAppearance(loaded = true))
    override fun mainIntent(context: Context, dialpad: Boolean) = Intent()
    override fun contactIntent(context: Context, contactId: Long?, number: String?) = Intent()
}
