package app.parley.messaging

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.TemporaryContacts
import app.parley.data.messaging.OpenedChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Chat, then decide" (M4): when you come back to Parley after opening a chat with a number that isn't a contact,
 * offer to keep it as a temporary contact that deletes itself in 7 days. Shown once per chat, within an hour.
 * F5: saved privately by default (Parley's vault), so WhatsApp and other apps can't read it; the dialog offers
 * "Save visible to other apps" instead. [openContact] opens a system contact, [openPrivate] a private one.
 */
@Composable
fun ChatThenDecideHost(snackbar: SnackbarHostState, openPrivate: (Long) -> Unit = {}, openContact: (Long) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Pair<OpenedChat, String>?>(null) }
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val c = context.container
            val chat = c.messaging.takeOpenedChat() ?: return@repeatOnLifecycle
            if (withContext(Dispatchers.IO) { ChatThenDecide.stillUnknown(c, chat) }.not()) return@repeatOnLifecycle
            val region = PhoneEnv.countryIso(context)
            WhatsAppNotice.maybeShow(c, chat, snackbar)
            val shown = TemporaryContact.suggestedName(chat.number, null, region)
            val r = snackbar.showSnackbar(
                "Save $shown privately for ${TemporaryContact.DEFAULT_DAYS} days?",
                actionLabel = "Save…", withDismissAction = true, duration = SnackbarDuration.Long,
            )
            if (r == SnackbarResult.ActionPerformed) asking = chat to TemporaryContact.suggestedName(chat.number, chat.appLabel, region)
        }
    }
    asking?.let { (chat, suggested) ->
        TemporaryNameDialog(suggested, onDismiss = { asking = null }) { name, visible ->
            asking = null
            scope.launch {
                val c = context.container
                val saved = withContext(Dispatchers.IO) { runCatching { TemporaryContact.save(c, chat.number, name, private = !visible) }.getOrNull() }
                val r = snackbar.showSnackbar(TemporaryContact.savedMessage(saved), actionLabel = saved?.let { "Open" })
                if (saved != null && r == SnackbarResult.ActionPerformed) if (saved.private) openPrivate(saved.id) else openContact(saved.id)
            }
        }
    }
}

object ChatThenDecide {
    /** The number is still not a contact (nor a private one), so the offer makes sense. */
    suspend fun stillUnknown(c: DataContainer, chat: OpenedChat): Boolean =
        c.contacts.lookup(chat.number) == null && c.vault.lookup(chat.number) == null

    suspend fun save(c: DataContainer, chat: OpenedChat, region: String, private: Boolean = true): TemporaryContacts.Saved? = withContext(Dispatchers.IO) {
        runCatching { TemporaryContact.save(c, chat.number, TemporaryContact.suggestedName(chat.number, chat.appLabel, region), private = private) }.getOrNull()
    }
}

/**
 * F30: once, after coming back from a WhatsApp chat with an unsaved number: newer WhatsApp versions may ask to sync
 * contacts before opening such a chat. That's WhatsApp's question, and declining is fine.
 */
object WhatsAppNotice {
    /** For "Who can see your contacts". */
    const val REVOKE_TEXT = "You can revoke WhatsApp's Contacts permission and still start chats from Parley. Some WhatsApp versions may still refuse; Parley can't detect that."

    const val TEXT = "WhatsApp may ask to sync your contacts before opening a chat. You can decline — Parley doesn't need it."

    suspend fun maybeShow(c: DataContainer, chat: OpenedChat, snackbar: SnackbarHostState) {
        if (!chat.appLabel.startsWith("WhatsApp") || c.messaging.whatsappSyncNoticeShown) return
        c.messaging.whatsappSyncNoticeShown = true
        snackbar.showSnackbar(TEXT, actionLabel = "OK", duration = SnackbarDuration.Long)
    }
}
