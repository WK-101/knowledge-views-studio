package app.parley.telecom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import app.parley.common.calls.PostCallActions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.parley.telecom.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.parley.common.catching
import app.parley.telecom.CallUi
import app.parley.telecom.TelecomGraph
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyShapes

/** What the user did on the post-call card. */
sealed interface PostCallChoice {
    /** Touched the card: keep the call-ended screen up. */
    data object Touched : PostCallChoice
    data object Done : PostCallChoice
    data class Block(val number: String) : PostCallChoice

    /** The number is blocked already (an outgoing call to it, or it was blocked meanwhile). */
    data class Unblock(val number: String) : PostCallChoice

    /** Save as a new contact, in the app's editor ([name]: the network's name for the number, to start from). */
    data class Save(val number: String, val name: String? = null) : PostCallChoice

    /** Add the number to a contact you already have. */
    data class AddToContact(val number: String) : PostCallChoice
    data class SavePrivately(val number: String, val name: String) : PostCallChoice
    data class MessageOn(val number: String, val accountId: String?) : PostCallChoice
    data class Report(val number: String) : PostCallChoice

    /** Open the number's history in Parley, where what Parley remembers about it has its action. */
    data class NumberMemory(val number: String) : PostCallChoice

    /** Call a saved number instead ("was that really the bank?"). */
    data class Verify(val number: String) : PostCallChoice

    /** "Was it a scam?": the warning signs, handled on the call screen itself. */
    data object ScamCheck : PostCallChoice

    /** "Ask their name": the messaging app opens with the "Text me your name" reply for the user to send. */
    data class NameReply(val number: String, val text: String) : PostCallChoice

    /** "Anything to remember?" was saved (note and/or a follow-up in [followUpDays]). */
    data class Remember(val number: String, val connectTimeMillis: Long, val note: String?, val followUpDays: Int?) : PostCallChoice
}

/**
 * Shown on the call-ended screen after a call with a number that isn't in your contacts ([PostCallActions]): Save (a
 * new contact, added to one you have, or privately for 7 days), Remind me and Block (the app's one Block question) or
 * Unblock as the big buttons; More holds Message or call on…, Ask their name, Report, Was it a scam? and Call a saved
 * number. The last two come forward when the call matched a scam signal or the user checked the caller during the
 * call ([safetyChecked]: a caller who claimed to be your bank). Each opens only after the phone is unlocked, except
 * "Remind me", which only adds to the To call list.
 */
@Composable
internal fun PostCallCard(call: CallUi, safetyChecked: Boolean, onChoice: (PostCallChoice) -> Unit) {
    val number = call.number ?: return
    var saving by remember { mutableStateOf(false) }
    val blocked by produceState(false, number) { value = catching { TelecomGraph.dependencies.isBlocked(number) }.getOrDefault(false) }
    // A call with an emergency service is never blocked or reported: neither is offered.
    val emergency by produceState(false, number) {
        value = withContext(Dispatchers.IO) { catching { TelecomGraph.dependencies.isEmergencyNumber(number) }.getOrDefault(false) }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ParleyShapes.sheet,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            // Any touch keeps the screen up (it would otherwise close a moment after the call).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onChoice(PostCallChoice.Touched)
                    }
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.incall_not_in_contacts), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(call.location, stringResource(R.string.postcall_what_to_do)).joinToString(stringResource(R.string.tc_separator)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberMemoryPostCall(call) { onChoice(PostCallChoice.NumberMemory(number)) }
            Spacer(Modifier.height(12.dp))
            PostCallButtons(call, number, blocked, emergency, safetyChecked, onChoice, onSavePrivately = { saving = true })
            // After a call that looked like a sales line, "Block this range?".
            BlockRangeOffer(call)
            TextButton({ onChoice(PostCallChoice.Done) }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.tc_done)) }
        }
    }
    if (saving) {
        // The network's name for the number, when it sent one; else a name made from the number and where it's from.
        var name by remember {
            mutableStateOf(call.networkName ?: runCatching { TelecomGraph.dependencies.suggestedName(number) }.getOrDefault(number))
        }
        ConfirmDialog(
            title = stringResource(R.string.postcall_save_title),
            text = null,
            confirmLabel = stringResource(R.string.tc_save),
            onConfirm = {
                saving = false
                onChoice(PostCallChoice.SavePrivately(number, name.trim().ifEmpty { number }))
            },
            onDismiss = { saving = false },
            dismissLabel = stringResource(R.string.call_auto_answer_cancel),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.postcall_name)) }, singleLine = true)
                    Text(
                        stringResource(R.string.postcall_save_explainer),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/** The card's buttons ([PostCallActions]): the big ones in a row that wraps, then More. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongParameterList") // One argument per fact the layout weighs, and the two ways out.
private fun PostCallButtons(
    call: CallUi,
    number: String,
    blocked: Boolean,
    emergency: Boolean,
    safetyChecked: Boolean,
    onChoice: (PostCallChoice) -> Unit,
    onSavePrivately: () -> Unit,
) {
    // "Text me your name", for the user to send from the messaging app.
    val nameReply = nameReplyFor(call)
    val layout = PostCallActions.layout(
        PostCallActions.Facts(
            blocked = blocked, emergency = emergency, nameReply = nameReply != null,
            suspicious = safetyChecked || call.verdictWarn || call.reputation != null,
        ),
    )
    val choose: (PostCallActions.Action) -> Unit = { a -> choiceFor(a, number, call.accountId, nameReply)?.let(onChoice) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        layout.primary.forEach { a ->
            when (a) {
                // Saving is what most people do after a first call: a new contact, one you have, or a private week.
                PostCallActions.Action.SAVE -> SaveAction(
                    onNew = { onChoice(PostCallChoice.Save(number, call.networkName)) },
                    onAdd = { onChoice(PostCallChoice.AddToContact(number)) },
                    onPrivately = onSavePrivately,
                )
                // Call them back later, from the To call list (saved without unlocking, like a note).
                PostCallActions.Action.REMIND_ME -> RemindMeAction(number, call.accountId) { onChoice(PostCallChoice.Done) }
                else -> postCallLabel(a).let { (icon, text) -> Action(icon, text) { choose(a) } }
            }
        }
        if (layout.more.isNotEmpty()) MoreAction(layout.more, choose)
    }
}

/** What a plain button or More item of the card chooses; Save and Remind me have menus of their own (null). */
private fun choiceFor(a: PostCallActions.Action, number: String, accountId: String?, nameReply: String?): PostCallChoice? = when (a) {
    PostCallActions.Action.SAVE, PostCallActions.Action.REMIND_ME -> null
    PostCallActions.Action.BLOCK -> PostCallChoice.Block(number)
    PostCallActions.Action.UNBLOCK -> PostCallChoice.Unblock(number)
    PostCallActions.Action.MESSAGE_OR_CALL_ON -> PostCallChoice.MessageOn(number, accountId)
    PostCallActions.Action.REPORT -> PostCallChoice.Report(number)
    PostCallActions.Action.ASK_NAME -> nameReply?.let { PostCallChoice.NameReply(number, it) }
    PostCallActions.Action.SCAM_CHECK -> PostCallChoice.ScamCheck
    PostCallActions.Action.CALL_SAVED_NUMBER -> PostCallChoice.Verify(number)
}

/** The icon and words of a post-call action drawn as a plain button or a More item. */
@Composable
private fun postCallLabel(a: PostCallActions.Action): Pair<ImageVector, String> = when (a) {
    PostCallActions.Action.BLOCK -> Icons.Rounded.Block to stringResource(R.string.postcall_block)
    PostCallActions.Action.UNBLOCK -> Icons.Rounded.RemoveModerator to stringResource(R.string.postcall_unblock)
    PostCallActions.Action.MESSAGE_OR_CALL_ON -> Icons.AutoMirrored.Rounded.Chat to stringResource(R.string.postcall_message_or_call)
    PostCallActions.Action.REPORT -> Icons.Rounded.Flag to stringResource(R.string.postcall_report)
    PostCallActions.Action.ASK_NAME -> Icons.Rounded.Sms to stringResource(R.string.postcall_name_reply)
    PostCallActions.Action.SCAM_CHECK -> Icons.Rounded.Shield to stringResource(R.string.scam_postcall)
    PostCallActions.Action.CALL_SAVED_NUMBER -> Icons.Rounded.VerifiedUser to stringResource(R.string.verify_postcall)
    PostCallActions.Action.SAVE -> Icons.Rounded.PersonAdd to stringResource(R.string.tc_save)
    PostCallActions.Action.REMIND_ME -> Icons.Rounded.AlarmAdd to stringResource(R.string.remind_me)
}

/** Save, the card's first and strongest button: New contact · Add to a contact · Privately for 7 days. */
@Composable
private fun SaveAction(onNew: () -> Unit, onAdd: () -> Unit, onPrivately: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button({ open = true }) {
            Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.tc_save))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            MenuItem(Icons.Rounded.PersonAdd, stringResource(R.string.postcall_new_contact)) { open = false; onNew() }
            MenuItem(Icons.Rounded.PersonSearch, stringResource(R.string.postcall_add_to_contact)) { open = false; onAdd() }
            MenuItem(Icons.Rounded.Lock, stringResource(R.string.postcall_save_privately)) { open = false; onPrivately() }
        }
    }
}

/** More: the card's rarer actions, in a menu. */
@Composable
private fun MoreAction(actions: List<PostCallActions.Action>, onAction: (PostCallActions.Action) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) {
            Icon(Icons.Rounded.MoreHoriz, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.incall_more))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                val (icon, text) = postCallLabel(a)
                MenuItem(icon, text) { open = false; onAction(a) }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(label)
    }
}
