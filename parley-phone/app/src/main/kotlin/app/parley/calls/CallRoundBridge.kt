package app.parley.calls

import android.content.Context
import app.parley.common.calls.CallRounds
import app.parley.common.catching
import app.parley.common.security.PrivacyView
import app.parley.data.EmergencyNumbers
import app.parley.data.PhoneEnv
import app.parley.data.calls.CallRoundStore
import app.parley.telecom.CallRoundHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The call round's note for the call screen ([CallRoundHooks]): the round's message when the number is one of someone's
 * in it who wasn't reached yet. Nothing during a duress unlock (it hides like a note), nothing for a private contact
 * while private contacts are hidden, and never for an emergency number. The call screen adds "never while locked".
 */
class CallRoundBridge(
    private val app: Context,
    private val store: () -> CallRoundStore,
    private val privacy: suspend () -> PrivacyView,
) : CallRoundHooks {
    override suspend fun roundNoteFor(number: String, accountId: String?): String? = withContext(Dispatchers.IO) {
        if (catching { EmergencyNumbers.isEmergency(app, number) }.getOrDefault(true)) return@withContext null
        val view = catching { privacy() }.getOrDefault(PrivacyView.CLOSED)
        if (!view.notesShown) return@withContext null
        val round = catching { store().load() }.getOrNull()?.takeIf { it.active } ?: return@withContext null
        CallRounds.noteFor(CallRounds.shown(round, view.privateShown), number, PhoneEnv.countryIso(app, accountId))
    }
}
