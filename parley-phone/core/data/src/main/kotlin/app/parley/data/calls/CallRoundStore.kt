package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.CallRound
import app.parley.common.calls.CallRounds
import app.parley.data.security.RecordSealing
import app.parley.data.security.SealedDocument
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The call round under way ([CallRounds]): one small [SealedDocument] (`docs/call_round` in no-backup storage), sealed
 * with the small-records key, so the call screen can read it while the phone is locked (it shows nothing then, but
 * reading must not fail). It stays on this phone: a round is a few days' errand, not something to restore.
 *
 * A stored round that can't be opened right now is never taken for none: changes are refused until it reads again.
 */
class CallRoundStore internal constructor(
    context: Context,
    /** Seals the encoded round; null when sealing isn't possible right now (tests replace it). */
    sealOverride: ((String) -> String?)?,
) : RecordSealing.Resealable {
    constructor(context: Context) : this(context, null)

    private val appContext = context.applicationContext

    private val doc = SealedDocument(
        appContext, File(appContext.noBackupFilesDir, "docs/call_round"), SealedDocument.Whole(CallRounds::encode, CallRounds::decode),
        sealOverride = sealOverride,
    )

    /** The round as stored; none until [load] ran. */
    val state: StateFlow<CallRound> = doc.state

    /** Whether the stored round could be read. */
    val available: Boolean get() = doc.available

    suspend fun load(): CallRound = doc.load()

    /** Applies [f] and writes the result; null when the stored round can't be read now (nothing changed). */
    suspend fun update(f: (CallRound) -> CallRound): CallRound? = doc.write(f).takeIf { doc.available }?.state

    /** Ends the round: nothing of it is kept. */
    suspend fun clear(): Boolean = update { CallRound() } != null

    override suspend fun resealPlain(): Boolean = doc.resealPlain()
}
