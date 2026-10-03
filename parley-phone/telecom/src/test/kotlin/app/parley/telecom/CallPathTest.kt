package app.parley.telecom

import android.app.Application
import android.telecom.Call
import android.telecom.DisconnectCause
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [CallManager] driven with real calls from [FakeTelecom] and the app side faked by [FakeDependencies]. */
@RunWith(RobolectricTestRunner::class)
internal abstract class CallPathTest {
    protected val telecom = FakeTelecom()
    protected val deps = FakeDependencies()
    protected val context: Application get() = RuntimeEnvironment.getApplication()

    @Before fun installDependencies() {
        TelecomGraph.install(deps)
        // The clock starts again in every test while CallManager lives on: an earlier test's own silence request
        // would read as a fresh echo. With no call up, answering them here changes nothing else.
        repeat(ECHOES_LEFT_BEHIND) { CallManager.onSystemSilence() }
    }

    @After fun clearCalls() {
        CallManager.clear()
        CallManager.dismissDeclineBlock()
        CallManager.stopMenuReplay()
        CallManager.updateAudio(AudioUi())
        CallManager.routeRequests = { CallManager.service?.requestRoute(it) }
        ScreeningGuard.forgetDecisions()
    }

    /** Telecom adds a call and the in-call service hands it to [CallManager]. */
    protected fun add(spec: FakeTelecom.Spec): Call {
        val call = telecom.call(spec)
        CallManager.add(context, call)
        FakeTelecom.idle()
        return call
    }

    protected fun ringing(id: String = "t1", number: String? = "+15551234567") = add(FakeTelecom.Spec(id, Call.STATE_RINGING, number))

    protected fun active(id: String, capabilities: Int = 0, number: String? = "+15550000001", properties: Int = 0) = add(
        FakeTelecom.Spec(
            id, Call.STATE_ACTIVE, number, incoming = false, capabilities = capabilities, properties = properties,
            connectTimeMillis = System.currentTimeMillis(),
        ),
    )

    protected fun held(id: String, capabilities: Int = 0, number: String? = "+15550000002") = add(
        FakeTelecom.Spec(id, Call.STATE_HOLDING, number, incoming = false, capabilities = capabilities, connectTimeMillis = System.currentTimeMillis()),
    )

    protected fun dialling(id: String, number: String? = "+15550000003") = add(FakeTelecom.Spec(id, Call.STATE_DIALING, number, incoming = false))

    protected fun contact(number: String, name: String = "Ada Lovelace", chosen: Boolean = false) {
        deps.contacts[number] = CallerDisplay(name, "content://photo/1", "Mobile", 7L, "ada", note = "Ask about the invoice", autoAnswerChosen = chosen)
    }

    protected fun str(res: Int): String = context.getString(res)

    /** The call ends: Telecom reports it disconnected with [cause], then removes it. */
    protected fun end(id: String, cause: Int = DisconnectCause.LOCAL, reason: String? = null) {
        val call = telecom.update(id) { it.copy(state = Call.STATE_DISCONNECTED, disconnectCause = DisconnectCause(cause, null, null, reason)) }
        CallManager.remove(call)
        FakeTelecom.idle()
    }

    protected fun idOf(call: Call) = CallManager.idOf(call)

    protected fun ui(call: Call): CallUi = CallManager.state.value.first { it.id == idOf(call) }

    protected fun sent(command: String): Boolean = telecom.sent.any { it.startsWith(command) }

    /** The ended call the call-ended screen shows. */
    protected fun ended(): CallUi = CallManager.lastEnded.value ?: error("no ended call")

    private companion object {
        /** More than any one test asks Telecom to silence the ringer. */
        const val ECHOES_LEFT_BEHIND = 8
    }
}
