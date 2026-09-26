package app.parley.ui.contact

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.parley.AppViewModel
import app.parley.common.ContactSummary
import app.parley.common.people.MessageRoute
import app.parley.common.people.MessengerPrefs
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M7 for list rows: the row's message button (and a "Message" swipe) uses the person's remembered way to message;
 * with none yet, it shows "Message on…" once and remembers the choice. Call [message]; place [Host] once.
 */
class QuickMessenger internal constructor(internal val open: (ContactSummary, String?, Boolean) -> Unit) {
    /** Messages [c] ([number] or their default number); [ask] always shows the sheet (long-press, "Message on…"). */
    fun message(c: ContactSummary, number: String? = null, ask: Boolean = false) = open(c, number, ask)
}

private class Pending(val reach: Reach, val lookupKey: String, val contactId: Long)

@Composable
fun rememberQuickMessenger(vm: AppViewModel): Pair<QuickMessenger, @Composable () -> Unit> {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }
    val messenger = remember(vm) {
        QuickMessenger { c, number, ask ->
            scope.launch {
                val messengers = withContext(Dispatchers.IO) { app.parley.data.Messengers.actions(context, c.id) }
                val meta = vm.c.meta.meta(c.lookupKey)
                val phones = c.phones
                val default = number ?: (phones.firstOrNull { it.isPrimary } ?: phones.firstOrNull())?.number
                val reach = Reach(
                    name = c.displayName, numbers = phones.map { it.number to Format.phoneType(res, it.type, it.label) },
                    defaultNumber = default, messengers = messengers, prefs = MessengerPrefs.decode(meta?.preferredMessenger).let { if (number != null) it.copy(number = null) else it },
                    lookupKey = c.lookupKey, contactId = c.id,
                )
                val route = if (ask) MessageRoute.Ask else ContactMessaging.route(context, reach)
                if (route == MessageRoute.Ask) pending = Pending(reach, c.lookupKey, c.id)
                else ContactMessaging.open(context, route, reach)?.let { vm.toast(it) }
            }
        }
    }
    val host: @Composable () -> Unit = {
        pending?.let { p ->
            ContactMessageSheet(p.reach, onDismiss = { pending = null }, onCall = { n -> vm.requestCall(n, p.reach.name) }) { prefs ->
                scope.launch {
                    val m = vm.c.meta.meta(p.lookupKey) ?: app.parley.data.db.ContactMetaEntity(p.lookupKey)
                    vm.c.meta.setMeta(m.copy(contactId = p.contactId, preferredMessenger = MessengerPrefs.decode(m.preferredMessenger).copy(message = prefs.message, number = prefs.number).encode()))
                }
            }
        }
    }
    return messenger to host
}
