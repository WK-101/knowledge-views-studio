package app.parley.messaging

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.parley.MainActivity
import app.parley.common.NumberText
import app.parley.container
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.data.PlaceResult
import app.parley.ui.ParleyTheme
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A small sheet over the current app for a phone number found in text (M3): "Call / Message with Parley" in text
 * selection menus, and text shared to Parley. Also hosts the "Message on…" sheet for places outside Parley's own
 * screens (missed-call notification, in-call screen).
 *
 * Nothing starts without a tap: several numbers show a picker, and every action is a button. The text is only used
 * to find numbers and is never stored. This activity doesn't handle `tel:` links (the keypad does).
 */
class NumberActionActivity : ComponentActivity() {
    private sealed interface Stage {
        data object NoNumber : Stage
        data class Pick(val found: List<NumberText.Found>) : Stage
        data class Actions(val number: String) : Stage
        data class Message(val number: String) : Stage
        data class Offer(val number: String, val via: String) : Stage
    }

    private var stage by mutableStateOf<Stage>(Stage.NoNumber)
    /** A chat with an unknown number was opened from here; offer a temporary contact when the user comes back. */
    private var awaitingReturn = false
    private var leftForChat = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        stage = initialStage(intent)
        val settings = container.settings.settings.value
        if (settings.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                Sheet()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        awaitingReturn = false
        stage = initialStage(intent)
    }

    override fun onPause() {
        super.onPause()
        if (awaitingReturn) leftForChat = true
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingReturn || !leftForChat) return
        awaitingReturn = false
        leftForChat = false
        val chat = container.messaging.takeOpenedChat()
        if (chat == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val unknown = withContext(Dispatchers.IO) { ChatThenDecide.stillUnknown(container, chat) }
            if (unknown) stage = Stage.Offer(chat.number, chat.appLabel) else finish()
        }
    }

    private fun initialStage(intent: Intent): Stage {
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ACTION_MESSAGE_ON -> intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let { return Stage.Message(it) }
            else -> null
        }.orEmpty().take(MAX_TEXT)
        val found = NumberText.find(text, PhoneEnv.countryIso(this))
        return when (found.size) {
            0 -> Stage.NoNumber
            1 -> Stage.Actions(found[0].e164 ?: found[0].raw)
            else -> Stage.Pick(found)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet() {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        when (val s = stage) {
            is Stage.Offer -> OfferDialog(s)
            else -> ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheetState) {
                when (s) {
                    Stage.NoNumber -> Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No phone number found", style = MaterialTheme.typography.titleLarge)
                        Text("Parley couldn't find a phone number in this text.", style = MaterialTheme.typography.bodyMedium)
                        TextButton({ finish() }) { Text("Close") }
                    }
                    is Stage.Pick -> PickNumber(s.found)
                    is Stage.Actions -> NumberActions(s.number)
                    is Stage.Message -> MessageOnContent(s.number) { app -> afterLaunch(app != null) }
                    is Stage.Offer -> Unit
                }
            }
        }
    }

    private fun afterLaunch(messenger: Boolean) {
        if (messenger && container.messaging.openedChat.value != null) {
            // Stay (invisible behind the chat) to offer a temporary contact on return.
            awaitingReturn = true
            leftForChat = false
        } else {
            finish()
        }
    }

    @Composable
    private fun PickNumber(found: List<NumberText.Found>) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Choose a number", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            found.forEach { f ->
                ListItem(
                    headlineContent = { Text(f.e164?.let(NumberText::formatInternational) ?: f.raw) },
                    supportingContent = { Text("“${f.raw}” in the text") },
                    modifier = Modifier.clickable { stage = Stage.Actions(f.e164 ?: f.raw) },
                )
            }
        }
    }

    @Composable
    private fun NumberActions(number: String) {
        val scope = rememberCoroutineScope()
        val region = remember { PhoneEnv.countryIso(this) }
        val e164 = remember(number) { NumberText.toE164(number, region) }
        var contactName by remember { mutableStateOf<String?>(null) }
        var askTemporary by remember { mutableStateOf(false) }
        val appLock = remember { container.settings.settings.value.appLock }
        LaunchedEffect(number) {
            // With the app lock on, don't reveal who this is over another app.
            if (!appLock) contactName = withContext(Dispatchers.IO) { container.contacts.lookup(number)?.name }
        }
        val where = remember(number) { NumberInfo.location(number, region) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(contactName ?: e164?.let(NumberText::formatInternational) ?: Format.number(number, region), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            val sub = listOfNotNull(if (contactName != null) e164?.let(NumberText::formatInternational) ?: number else null, where).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            ListItem(
                headlineContent = { Text("Call") },
                leadingContent = { Icon(Icons.Rounded.Call, null) },
                modifier = Modifier.clickable { call(number) },
            )
            ListItem(
                headlineContent = { Text("Message on…") },
                supportingContent = { Text("WhatsApp, Signal, Telegram, Viber or SMS") },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                modifier = Modifier.clickable { stage = Stage.Message(number) },
            )
            if (contactName == null) {
                ListItem(
                    headlineContent = { Text("Add to contacts") },
                    leadingContent = { Icon(Icons.Rounded.PersonAdd, null) },
                    modifier = Modifier.clickable {
                        startActivity(
                            Intent(this@NumberActionActivity, MainActivity::class.java)
                                .setAction(Intent.ACTION_INSERT_OR_EDIT)
                                .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                .putExtra(ContactsContract.Intents.Insert.PHONE, e164 ?: number),
                        )
                        finish()
                    },
                )
                ListItem(
                    headlineContent = { Text("Save as a temporary contact") },
                    supportingContent = { Text("Deletes itself in ${TemporaryContact.DEFAULT_DAYS} days") },
                    leadingContent = { Icon(Icons.Rounded.Timer, null) },
                    modifier = Modifier.clickable { askTemporary = true },
                )
            }
        }
        if (askTemporary) {
            TemporaryNameDialog(TemporaryContact.suggestedName(number, null, region), onDismiss = { askTemporary = false }) { name ->
                askTemporary = false
                scope.launch { saveTemporary(e164 ?: number, name) }
            }
        }
    }

    @Composable
    private fun OfferDialog(s: Stage.Offer) {
        val scope = rememberCoroutineScope()
        val region = remember { PhoneEnv.countryIso(this) }
        TemporaryNameDialog(
            TemporaryContact.suggestedName(s.number, s.via, region),
            title = "Save as a temporary contact?",
            onDismiss = { finish() },
        ) { name -> scope.launch { saveTemporary(s.number, name) } }
    }

    private suspend fun saveTemporary(number: String, name: String) {
        val id = withContext(Dispatchers.IO) { runCatching { TemporaryContact.save(container, number, name) }.getOrNull() }
        Toast.makeText(
            this,
            if (id != null) "Saved. Deletes itself in ${TemporaryContact.DEFAULT_DAYS} days." else "Couldn't save the contact",
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    private fun call(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // Not the phone app: hand the number to Parley's keypad instead.
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setClass(this, MainActivity::class.java))
            finish()
            return
        }
        lifecycleScope.launch {
            val r = container.placer.call(number)
            if (r is PlaceResult.Failed) Toast.makeText(this@NumberActionActivity, r.reason, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        const val ACTION_MESSAGE_ON = "app.parley.action.MESSAGE_ON"
        const val EXTRA_NUMBER = "number"
        /** Selected or shared text beyond this is ignored (a phone number is never that far in). */
        private const val MAX_TEXT = 10_000
    }
}

/** Name for a temporary contact, prefilled with "WhatsApp · +92 300 1234567". */
@Composable
fun TemporaryNameDialog(suggested: String, title: String = "Save as a temporary contact", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("It's saved on this phone only and deletes itself, with its call history, in ${TemporaryContact.DEFAULT_DAYS} days. Keep it any time from the contact's page.")
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Not now") } },
    )
}
