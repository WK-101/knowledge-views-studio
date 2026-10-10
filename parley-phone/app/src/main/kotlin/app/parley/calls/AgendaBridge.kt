package app.parley.calls

import android.content.Context
import app.parley.common.catching
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
import app.parley.data.circle.AgendaStore
import app.parley.data.circle.AgendaTarget
import app.parley.telecom.AgendaAdded
import app.parley.telecom.AgendaHooks
import app.parley.telecom.CallerAgenda
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The agenda for the call path ([AgendaHooks]), on [DataContainer.agenda]: the open items of the person on the line,
 * ticked off or added to from the call screen. Never for an emergency number. Whether the lock screen may show the
 * items is the user's: notes on the lock screen (Settings › Privacy & security › Caller on the lock screen, "Name and
 * notes"), or the Circle's "Show notes and promises on the lock screen".
 */
class AgendaBridge(private val app: Context, private val c: DataContainer) : AgendaHooks {
    private fun emergency(number: String): Boolean = catching { EmergencyNumbers.isEmergency(app, number) }.getOrDefault(true)

    private suspend fun target(number: String, accountId: String?): AgendaTarget? =
        if (emergency(number)) null else c.agenda.targetFor(number, accountId)

    override suspend fun agendaFor(number: String, accountId: String?): CallerAgenda? = withContext(Dispatchers.IO) {
        val target = target(number, accountId) ?: return@withContext null
        val items = c.agenda.open(target)?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val onLock = c.circle.config.value.memoryOnLockScreen || c.privacy.now().lockScreen.showsNotes
        CallerAgenda(items, textOnLockScreen = onLock, privateContact = target is AgendaTarget.Private)
    }

    override suspend fun setAgendaDone(number: String, accountId: String?, text: String, done: Boolean): Boolean {
        val target = target(number, accountId) ?: return false
        return AgendaTicks.setDone(c, target, text, done)
    }

    override suspend fun addAgendaItem(number: String, accountId: String?, text: String): AgendaAdded {
        val target = target(number, accountId) ?: return AgendaAdded.FAILED
        return when (c.agenda.add(target, text)) {
            AgendaStore.Added.ADDED -> AgendaAdded.ADDED
            AgendaStore.Added.ALREADY -> AgendaAdded.ALREADY
            AgendaStore.Added.LOCKED -> AgendaAdded.LOCKED
            AgendaStore.Added.FAILED -> AgendaAdded.FAILED
        }
    }
}

/** Ticking an agenda item, wherever it is ticked: the item, then what an expected call made from its note follows. */
object AgendaTicks {
    suspend fun setDone(c: DataContainer, target: AgendaTarget, text: String, done: Boolean): Boolean {
        val note = c.agenda.setDone(target, text, done) ?: return false
        // I7: a number's note may have opened an expected call; it follows the note as it is now.
        catching { ExpectedCallHints.promiseTicked(c, target.parleyKey.orEmpty(), note) }
        return true
    }
}
