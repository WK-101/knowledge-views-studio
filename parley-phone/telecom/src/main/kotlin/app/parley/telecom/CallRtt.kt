package app.parley.telecom

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import app.parley.common.calls.RttReader
import app.parley.common.calls.RttTranscript
import app.parley.common.calls.RttTyping
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How the audio goes with RTT on (Android's `Call.RttCall` modes). */
enum class RttMode(val telecom: Int) {
    /** Type and read; the voice stays on both ways. */
    FULL(Call.RttCall.RTT_MODE_FULL),

    /** Hearing carry-over: listen, and type instead of speaking. */
    HCO(Call.RttCall.RTT_MODE_HCO),

    /** Voice carry-over: speak, and read instead of listening. */
    VCO(Call.RttCall.RTT_MODE_VCO);

    companion object {
        fun of(telecom: Int): RttMode = entries.firstOrNull { it.telecom == telecom } ?: FULL
    }
}

/** RTT (real-time text) of one call, for the call screen. Never put in a notification. */
data class RttUi(
    /** The call's SIM (calling account) supports RTT, or the call already is one. */
    val supported: Boolean = false,
    val active: Boolean = false,
    /** Parley asked the network to switch to RTT and waits for the answer. */
    val requesting: Boolean = false,
    /** The other person asked to switch to RTT: Telecom's request id, until answered. */
    val incomingRequest: Int? = null,
    val mode: RttMode = RttMode.FULL,
    /** The last request to switch didn't work (the network or the other phone said no). */
    val failed: Boolean = false,
    val transcript: RttTranscript = RttTranscript(),
    /** The conversation was saved to the call's note. */
    val saved: Boolean = false,
    /** The call has ended; the transcript stays a little while so it can still be saved. */
    val ended: Boolean = false,
    /** What the user has typed into the message being written (the field's text, as sent so far). */
    val typing: String = "",
)

/**
 * RTT through Android's public `Call.RttCall`, which Telecom gives the default phone app's in-call service (no
 * permission needed). Watches every call with a callback of its own (so [CallManager]'s stays as it is): whether its
 * calling account has `PhoneAccount.CAPABILITY_RTT`, requests both ways, mode changes and failures. While RTT is on,
 * one background thread per call reads the other side's characters (`read()` blocks until text comes or the stream
 * closes; Android replaces the `RttCall` object on every RTT change, so the thread follows the newest one, see
 * [RttReader]) and a single writer thread sends this side's characters one at a time, as RTT expects. Main thread for
 * everything else.
 *
 * The conversation is call content: it shows on the call screen only (also over the lock screen, like the call
 * itself), never in a notification, and is kept in memory, never on disk, unless the user taps Save, which adds it to
 * the call's note (sealed like every call note). After the call it stays [KEEP_ENDED_MS] so Save still works.
 */
object CallRtt {
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "rtt-write").apply { isDaemon = true } }

    private val _state = MutableStateFlow<Map<String, RttUi>>(emptyMap())
    val state: StateFlow<Map<String, RttUi>> = _state.asStateFlow()

    private class Watched(val call: Call, val callback: Call.Callback) {
        /** The call's RTT stream now (for writing and the mode), or null while RTT is off. */
        var reading: Call.RttCall? = null

        /** The call's one reader thread, whichever `RttCall` object Telecom hands over. */
        val reader = RttReader<Call.RttCall>()
        var number: String? = null
        var connectedAt = 0L

        /** "Answer with RTT": ask to switch once the call is up. */
        var askWhenActive = false

        /** Whether the call's account offers RTT, asked once per account (details change often; this is a binder call). */
        var account: Pair<PhoneAccountHandle, Boolean>? = null
    }

    private val watched = HashMap<String, Watched>()

    /** An ended call's line and connect time, for Save after the call (only while its conversation is kept). */
    private val ended = HashMap<String, Pair<String?, Long>>()
    private lateinit var appContext: Context

    private fun update(id: String, change: (RttUi) -> RttUi) {
        _state.update { m -> m + (id to change(m[id] ?: RttUi())) }
    }

    internal fun attach(context: Context, call: Call, id: String) {
        appContext = context.applicationContext
        if (watched.containsKey(id)) return
        // A new call: earlier calls' conversations go now.
        _state.update { m -> m.filterValues { !it.ended } }
        val cb = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = refresh(id)
            override fun onDetailsChanged(call: Call, details: Call.Details) = refresh(id)
            override fun onRttStatusChanged(call: Call, enabled: Boolean, rttCall: Call.RttCall?) = refresh(id)
            override fun onRttModeChanged(call: Call, mode: Int) = update(id) { it.copy(mode = RttMode.of(mode)) }
            override fun onRttRequest(call: Call, id2: Int) = update(id) { it.copy(incomingRequest = id2) }
            override fun onRttInitiationFailure(call: Call, reason: Int) = update(id) { it.copy(requesting = false, failed = true) }
        }
        watched[id] = Watched(call, cb)
        call.registerCallback(cb, main)
        refresh(id)
    }

    internal fun detach(call: Call, id: String) {
        val w = watched.remove(id) ?: return
        call.unregisterCallback(w.callback)
        w.reading = null
        w.reader.follow(null)
        val s = _state.value[id] ?: return
        if (s.transcript.isEmpty) {
            _state.update { it - id }
            return
        }
        ended[id] = w.number to w.connectedAt
        update(id) { it.copy(active = false, requesting = false, incomingRequest = null, ended = true, typing = "", transcript = it.transcript.closeAll()) }
        main.postDelayed({
            _state.update { m -> if (m[id]?.ended == true) m - id else m }
            ended.remove(id)
        }, KEEP_ENDED_MS)
    }

    /** The in-call service went away: nothing is read or watched any more (ended conversations keep their time). */
    internal fun release() {
        watched.forEach { (_, w) ->
            runCatching { w.call.unregisterCallback(w.callback) }
            w.reader.follow(null)
        }
        watched.clear()
        _state.update { m -> m.filterValues { it.ended } }
    }

    /** The user answered [id]: with "Answer with RTT" on, ask to switch once it's connected. */
    internal fun onAnswered(id: String) {
        val w = watched[id] ?: return
        if (runCatching { TelecomGraph.dependencies.answerWithRtt() }.getOrDefault(false)) {
            w.askWhenActive = true
            refresh(id)
        }
    }

    private fun refresh(id: String) {
        val w = watched[id] ?: return
        val call = w.call
        val d = call.details
        val active = runCatching { call.isRttActive }.getOrDefault(false)
        val state = stateOf(call)
        if (state == Call.STATE_ACTIVE) noteLine(w, d)
        val supported = active || d.hasProperty(Call.Details.PROPERTY_RTT) || accountSupportsRtt(w)
        val mode = followStream(id, w, if (active) call.rttCall else null)
        val conference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE)
        update(id) { it.following(active, supported && !conference, mode) }
        if (w.askWhenActive && state == Call.STATE_ACTIVE) {
            w.askWhenActive = false
            if (!active && supported) request(id)
        }
    }

    /**
     * Reads [rtt] while RTT is on; its audio mode, or null while off. Telecom hands a new `RttCall` over the same pipe
     * on every RTT change: the call's one reader moves over to it ([RttReader]), never a second thread.
     */
    private fun followStream(id: String, w: Watched, rtt: Call.RttCall?): RttMode? {
        w.reading = rtt
        if (w.reader.follow(rtt) && rtt != null) startReading(id, w.reader, rtt)
        return rtt?.let { RttMode.of(runCatching { it.rttAudioMode }.getOrDefault(Call.RttCall.RTT_MODE_FULL)) }
    }

    /** The line and connect time, for Save (a hidden number keeps none). */
    private fun noteLine(w: Watched, d: Call.Details) {
        w.number = d.handle?.schemeSpecificPart?.takeIf { d.handlePresentation == TelecomManager.PRESENTATION_ALLOWED }
        if (w.connectedAt == 0L) w.connectedAt = d.connectTimeMillis
    }

    /** The state after Telecom's news: RTT on or off, offered or not, and the audio mode while on. */
    private fun RttUi.following(active: Boolean, supported: Boolean, mode: RttMode?) = copy(
        supported = supported,
        active = active,
        requesting = requesting && !active,
        mode = mode ?: this.mode,
        transcript = if (active) transcript else transcript.closeAll(),
        typing = if (active) typing else "",
    )

    @Suppress("DEPRECATION") // Call.getState is the only one before Android 12.
    private fun stateOf(call: Call): Int = if (Build.VERSION.SDK_INT >= 31) call.details.state else call.state

    private fun accountSupportsRtt(w: Watched): Boolean {
        val handle = w.call.details.accountHandle ?: return false
        if (!::appContext.isInitialized) return false
        w.account?.takeIf { it.first == handle }?.let { return it.second }
        val offers = try {
            appContext.getSystemService(TelecomManager::class.java)?.getPhoneAccount(handle)?.hasCapabilities(PhoneAccount.CAPABILITY_RTT) == true
        } catch (_: SecurityException) {
            false
        }
        w.account = handle to offers
        return offers
    }

    /**
     * The call's one reader: reads the other side's characters from [reader]'s current stream until RTT goes off or
     * the call ends. When Telecom replaces the stream, whatever the old object still holds is read before moving on,
     * so no character is lost; text from either object is the same call's.
     */
    private fun startReading(id: String, reader: RttReader<Call.RttCall>, first: Call.RttCall) {
        fun deliver(chunk: String?) {
            if (chunk.isNullOrEmpty()) return
            main.post { if (watched[id]?.reader === reader && reader.accepts()) update(id) { it.copy(transcript = it.transcript.received(chunk)) } }
        }
        Thread({
            var rtt: Call.RttCall? = first
            // Whatever arrived before this thread started, then each new piece as it comes.
            deliver(runCatching { first.readImmediately() }.getOrNull())
            while (rtt != null) {
                val from: Call.RttCall = rtt
                // Null once the stream closed (RTT off, or the call ended).
                val text = runCatching { from.read() }.getOrNull()
                deliver(text)
                val next = reader.next(from, closed = text == null)
                if (next != null && next !== from) {
                    // Replaced: what the old object had buffered first, then what waits on the new one.
                    if (text != null) deliver(runCatching { from.readImmediately() }.getOrNull())
                    deliver(runCatching { next.readImmediately() }.getOrNull())
                }
                rtt = next
            }
        }, "rtt-read").apply { isDaemon = true }.start()
    }

    // ---- Actions (main thread) ----

    /** Asks the network to switch [id] to RTT. */
    fun request(id: String) {
        val call = watched[id]?.call ?: return
        update(id) { it.copy(requesting = true, failed = false) }
        runCatching { call.sendRttRequest() }.onFailure { update(id) { s -> s.copy(requesting = false, failed = true) } }
    }

    /** Answers the other person's request to switch to RTT. */
    fun respond(id: String, accept: Boolean) {
        val w = watched[id] ?: return
        val req = _state.value[id]?.incomingRequest ?: return
        update(id) { it.copy(incomingRequest = null) }
        runCatching { w.call.respondToRttRequest(req, accept) }
    }

    /** Turns RTT off for [id]; the call goes on as a voice call. */
    fun stop(id: String) {
        runCatching { watched[id]?.call?.stopRtt() }
    }

    fun setMode(id: String, mode: RttMode) {
        val w = watched[id] ?: return
        val rtt = w.reading ?: return
        update(id) { it.copy(mode = mode) }
        runCatching { rtt.setRttMode(mode.telecom) }
    }

    fun dismissFailure(id: String) = update(id) { it.copy(failed = false) }

    /** The field now reads [text]: sends what changed, one character at a time. */
    fun type(id: String, text: String) {
        val before = _state.value[id]?.typing ?: return
        val diff = RttTyping.diff(before, text)
        update(id) { it.copy(typing = RttTyping.clean(text)) }
        send(id, diff)
    }

    /** Send: ends the message being written. */
    fun endMessage(id: String) {
        update(id) { it.copy(typing = "") }
        send(id, RttTyping.END_OF_MESSAGE)
    }

    private fun send(id: String, text: String) {
        if (text.isEmpty()) return
        val rtt = watched[id]?.reading ?: return
        update(id) { it.copy(transcript = it.transcript.sent(text)) }
        val chars = RttTyping.characters(text)
        writer.execute {
            for (c in chars) {
                try {
                    rtt.write(c)
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    /** Adds the conversation to the call's note ([themLabel] / [meLabel] name the sides). Only when the user asks. */
    fun save(id: String, themLabel: String, meLabel: String) {
        val s = _state.value[id] ?: return
        if (s.transcript.isEmpty) return
        val w = watched[id]
        val (number, connectedAt) = if (w != null) w.number to w.connectedAt else ended[id] ?: return
        runCatching { TelecomGraph.dependencies.saveCallNote(number, connectedAt, s.transcript.asText(themLabel, meLabel)) }
        update(id) { it.copy(saved = true) }
    }

    /** How long an ended call's conversation stays for Save (the call-ended screen closes well before). */
    private const val KEEP_ENDED_MS = 120_000L
}
