package app.parley.telecom

import android.os.Trace

/**
 * Where the call list goes after every change: the notifications and the proximity sensor (through [onChanged],
 * set by the in-call service), and the add → first notification trace of incoming calls (Perfetto / systrace).
 */
internal class NotifierBridge {
    var onChanged: ((List<CallUi>) -> Unit)? = null

    /** Whether the in-call screen is in the foreground (proximity screen-off only applies then). */
    @Volatile
    var uiVisible: Boolean = false

    fun send(calls: List<CallUi>) {
        onChanged?.invoke(calls)
    }

    /** An incoming call arrived: its first notification is timed from now. */
    fun traceIncoming(session: CallSession) {
        if (session.noticeTraced) return
        session.noticeTraced = true
        Trace.beginAsyncSection(TRACE_NOTIFY, session.id.hashCode())
    }

    /** Its first notification was posted, or the call is gone. */
    fun endTrace(session: CallSession) {
        if (!session.noticeTraced) return
        session.noticeTraced = false
        Trace.endAsyncSection(TRACE_NOTIFY, session.id.hashCode())
    }

    private companion object {
        const val TRACE_NOTIFY = "Parley.addToNotification"
    }
}
