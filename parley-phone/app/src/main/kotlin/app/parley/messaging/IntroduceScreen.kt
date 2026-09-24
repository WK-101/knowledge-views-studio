package app.parley.messaging

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.ContactSummary
import app.parley.common.MessageDrafts
import app.parley.common.NumberText
import app.parley.common.messaging.IntroQueue
import app.parley.ui.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Starting points of "Introduce myself…" (M13). */
object IntroduceStart {
    /** From Contacts multi-select: each person's mobile number (else the first one). False when none has a number. */
    fun fromContacts(vm: AppViewModel, chosen: List<ContactSummary>): Boolean {
        val targets = chosen.mapNotNull { c ->
            val phone = c.phones.firstOrNull { it.type == 2 } ?: c.phones.firstOrNull() ?: return@mapNotNull null
            val e164 = NumberText.toE164(phone.number, vm.countryIso) ?: return@mapNotNull null
            IntroQueue.Target(c.displayName, e164)
        }.distinctBy { it.number }
        if (targets.isEmpty()) return false
        MessagingInbox.introTargets = targets
        vm.navigate(NavEvent.Route(MessagingRoutes.INTRODUCE))
        return true
    }

    /** From the bulk-add result. */
    fun fromList(targets: List<IntroQueue.Target>, open: (String) -> Unit) {
        MessagingInbox.introTargets = targets
        open(MessagingRoutes.INTRODUCE)
    }
}

private val QueueSaver = Saver<IntroQueue, ArrayList<Int>>(
    save = { q -> arrayListOf(q.index, if (q.stopped) 1 else 0, q.opened.size) .apply { addAll(q.opened); addAll(q.skipped) } },
    restore = { l ->
        val opened = l.drop(3).take(l[2]).toSet()
        IntroQueue(MessagingInbox.introTargets, index = l[0], opened = opened, skipped = l.drop(3 + l[2]).toSet(), stopped = l[1] == 1)
    },
)

/**
 * M13 "Introduce myself to a list": opens one chat at a time in the chosen messenger with "Send my details"
 * prefilled. You press Send there; when you come back, Parley moves on to the next person. No automation, no SMS
 * permission: every message is sent by you.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntroduceScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val store = vm.c.messaging
    var queue by rememberSaveable(stateSaver = QueueSaver) { mutableStateOf(IntroQueue(MessagingInbox.introTargets)) }
    var appPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var awaitingReturn by rememberSaveable { mutableStateOf(false) }
    var editDetails by remember { mutableStateOf(false) }
    val details by store.myDetails.collectAsStateWithLifecycle()
    val draft = MessageDrafts.myDetails(details.name, details.number)
    val installed = remember { MessengerLauncher.installed(context) }
    val app = installed.firstOrNull { it.packageName == appPackage }

    // Back from the messenger after opening the current chat: on to the next person.
    LifecycleResumeEffect(Unit) {
        if (awaitingReturn) {
            awaitingReturn = false
            queue = queue.returned()
        }
        onPauseOrDispose { }
    }

    fun openCurrent() {
        val a = app ?: return
        val t = queue.current ?: return
        val error = MessengerLauncher.openChat(context, a, t.number, draft)
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            return
        }
        if (!a.takesText && draft != null) Toast.makeText(context, "Your details were copied. Paste them in the chat.", Toast.LENGTH_LONG).show()
        store.lastApp = a.packageName
        store.recordOpened(t.number, a, a.label, isContact = true)
        queue = queue.markOpened()
        awaitingReturn = true
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Introduce myself") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
    }) { p ->
        if (queue.targets.isEmpty()) {
            EmptyState(Icons.Rounded.Groups, "Nobody to introduce yourself to", "Select contacts and choose “Introduce myself…”, or add several numbers first.", Modifier.padding(p))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Parley opens one chat at a time with your details filled in. You press Send in the messenger; when you come back, the next person is ready. Nothing is sent for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(draft ?: "Your details aren't set up yet") },
                    supportingContent = { Text("The message") },
                    leadingContent = { Icon(Icons.Rounded.Badge, null) },
                    trailingContent = { TextButton({ editDetails = true }) { Text(if (draft == null) "Set up" else "Edit") } },
                )
            }
            when {
                app == null -> {
                    Text("Open the chats in", style = MaterialTheme.typography.titleMedium)
                    if (installed.isEmpty()) Text("No chat apps found. Install or enable WhatsApp, Signal, Telegram or Viber.")
                    installed.forEach { a ->
                        ListItem(
                            headlineContent = { Text(a.label) },
                            supportingContent = { Text(if (a.takesText) "Your details are filled in" else "Your details are copied for pasting") },
                            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                            modifier = Modifier.clickable(enabled = draft != null) { appPackage = a.packageName },
                        )
                    }
                    if (draft == null) Text("Set up your details first.", color = MaterialTheme.colorScheme.error)
                }
                queue.finished -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Text(queue.summary(), style = MaterialTheme.typography.titleMedium)
                    }
                    Button(back) { Text("Done") }
                }
                else -> {
                    val t = queue.current!!
                    Text(queue.progress, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { queue.index.toFloat() / queue.targets.size }, modifier = Modifier.fillMaxWidth())
                    Text(t.name.ifBlank { NumberText.formatInternational(t.number) }, style = MaterialTheme.typography.headlineSmall)
                    if (t.name.isNotBlank()) Text(NumberText.formatInternational(t.number), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val opened = queue.index in queue.opened
                    Button(::openCurrent, Modifier.fillMaxWidth()) {
                        Text(if (opened) "Open ${app.label} again" else "Open in ${app.label}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ queue = if (opened) queue.next() else queue.skip() }) { Text(if (opened) "Next" else "Skip") }
                        TextButton({ queue = queue.stop() }) { Text("Stop") }
                        TextButton({ appPackage = null }) { Text("Change app") }
                    }
                }
            }
        }
    }
    if (editDetails) {
        MyDetailsDialog(
            initial = details,
            suggestNumber = { withContext(Dispatchers.IO) { vm.c.sims.ownNumbers().firstOrNull() } },
            onDismiss = { editDetails = false },
        ) { d ->
            editDetails = false
            store.setMyDetails(d)
        }
    }
}
