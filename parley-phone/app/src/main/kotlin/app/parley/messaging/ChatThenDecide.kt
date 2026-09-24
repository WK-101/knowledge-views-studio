package app.parley.messaging

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.messaging.OpenedChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Chat, then decide" (M4): when you come back to Parley after opening a chat with a number that isn't a contact,
 * offer to keep it as a temporary contact that deletes itself in 7 days. Shown once per chat, within an hour.
 */
@Composable
fun ChatThenDecideHost(snackbar: SnackbarHostState, openContact: (Long) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val c = context.container
            val chat = c.messaging.takeOpenedChat() ?: return@repeatOnLifecycle
            if (withContext(Dispatchers.IO) { ChatThenDecide.stillUnknown(c, chat) }.not()) return@repeatOnLifecycle
            val region = PhoneEnv.countryIso(context)
            val shown = TemporaryContact.suggestedName(chat.number, null, region)
            val r = snackbar.showSnackbar(
                "Save $shown as a temporary contact (deletes in ${TemporaryContact.DEFAULT_DAYS} days)?",
                actionLabel = "Save", withDismissAction = true, duration = SnackbarDuration.Long,
            )
            if (r != SnackbarResult.ActionPerformed) return@repeatOnLifecycle
            val id = ChatThenDecide.save(c, chat, region)
            if (id == null) {
                snackbar.showSnackbar("Couldn't save the contact")
            } else if (snackbar.showSnackbar("Saved for ${TemporaryContact.DEFAULT_DAYS} days", actionLabel = "Open") == SnackbarResult.ActionPerformed) {
                openContact(id)
            }
        }
    }
}

object ChatThenDecide {
    /** The number is still not a contact (nor a private one), so the offer makes sense. */
    suspend fun stillUnknown(c: DataContainer, chat: OpenedChat): Boolean =
        c.contacts.lookup(chat.number) == null && c.vault.lookup(chat.number) == null

    suspend fun save(c: DataContainer, chat: OpenedChat, region: String): Long? = withContext(Dispatchers.IO) {
        runCatching { TemporaryContact.save(c, chat.number, TemporaryContact.suggestedName(chat.number, chat.appLabel, region)) }.getOrNull()
    }
}
