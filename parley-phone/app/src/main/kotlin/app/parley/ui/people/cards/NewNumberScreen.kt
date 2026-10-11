// The wizard lives with the message it sends.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.people.cards

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.common.cards.ShareLedger
import app.parley.common.cards.ShareMethod
import app.parley.common.messaging.IntroQueue
import app.parley.common.people.MeCards
import app.parley.messaging.MessagingText
import app.parley.messaging.MessengerLauncher
import app.parley.ui.Bidi
import app.parley.ui.EmptyState
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.Spacing
import app.parley.ui.showMessage
import kotlinx.coroutines.launch

/** The "Changed my number" message. */
object NewNumberText {
    fun message(res: Resources, name: String, number: String): String {
        val n = NumberText.formatInternational(number)
        return if (name.isBlank()) res.getString(R.string.card_new_number_message_no_name, n) else res.getString(R.string.card_new_number_message, name, n)
    }
}

/**
 * "Changed my number": the people "Shared with" says still have an old number of yours, one at a time. Parley opens
 * a chat (or an SMS) with the message filled in, like "Introduce myself": you press Send there, and when you come back
 * the next person is ready. "Send my card" shares your signed card instead, so their Parley offers the update by itself.
 * Nothing is ever sent for you.
 */
// The queue's steps (open, card, next, skip, stop) are one state machine, kept together like IntroduceScreen's.
@Suppress("CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewNumberScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val c = vm.c
    val own by c.people.me.card.collectAsStateWithLifecycle()
    val parts by c.people.me.shareParts.collectAsStateWithLifecycle()
    val phones = own.cleaned().phones
    val number = phones.firstOrNull()
    // Who to tell is read once and kept in saved state (name and number of each), so nobody drops out of the list
    // or gets skipped when the screen is recreated while you're in a chat.
    var saved by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val read by rememberOutdated(vm, phones)
    LaunchedEffect(read) { if (saved == null) read?.let { t -> saved = t.flatMap { listOf(it.name, it.number) } } }
    val targets = saved?.chunked(2)?.map { IntroQueue.Target(it[0], it[1]) }
    var index by rememberSaveable { mutableStateOf(0) }
    var opened by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var skipped by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var stopped by rememberSaveable { mutableStateOf(false) }
    // How each opened person got the news ("index:METHOD"), recorded when you move on from them.
    var ways by rememberSaveable { mutableStateOf(listOf<String>()) }
    val queue = IntroQueue(targets.orEmpty(), index, opened.toSet(), skipped.toSet(), stopped)
    fun set(q: IntroQueue) {
        index = q.index; opened = q.opened.toList(); skipped = q.skipped.toList(); stopped = q.stopped
    }
    // "sms" or a messenger's package.
    var via by rememberSaveable { mutableStateOf<String?>(null) }
    var awaitingReturn by rememberSaveable { mutableStateOf(false) }
    val installed = remember { MessengerLauncher.installed(context) }
    val app = installed.firstOrNull { it.packageName == via }
    val draft = number?.let { NewNumberText.message(res, own.name, it) }
    val subject = own.name.ifBlank { stringResource(R.string.me_title) }

    LifecycleResumeEffect(Unit) {
        if (awaitingReturn) {
            awaitingReturn = false
            set(queue.returned())
        }
        onPauseOrDispose { }
    }

    fun opened(method: ShareMethod) {
        ways = ways.filterNot { it.startsWith("${queue.index}:") } + "${queue.index}:${method.name}"
        set(queue.markOpened())
        awaitingReturn = true
    }

    /**
     * The person on screen counts as told only when you move on with Next: opening a chat alone doesn't mean the
     * message was sent. Their "Shared with" receipt is written then.
     */
    fun next() {
        val t = queue.current
        if (t != null && queue.index in queue.opened) {
            val method = ways.lastOrNull { it.startsWith("${queue.index}:") }?.substringAfter(':')
                ?.let { m -> ShareMethod.entries.firstOrNull { it.name == m } } ?: ShareMethod.NEW_NUMBER
            CardSharing.record(c, t.name, t.number, method, phones)
            set(queue.next())
        } else {
            set(queue.skip())
        }
    }

    fun openCurrent() {
        val t = queue.current ?: return
        if (!openNewNumber(context, via, app, t.number, draft ?: return)) return
        opened(ShareMethod.NEW_NUMBER)
    }

    fun sendCard() {
        if (queue.current == null) return
        scope.launch {
            // Name and numbers at least, so their Parley can match it to you; signed from the one card source.
            val withNumbers = parts + MeCards.Part.NAME + MeCards.Part.PHONES
            val text = CardSharing.vcard(c, withNumbers)
            if (CardSharing.shareFile(c, context, text, subject)) opened(ShareMethod.CARD_FILE)
        }
    }

    ParleyScaffold(topBar = { ParleyTopBar(stringResource(R.string.card_new_number_title), onBack = back) }) { p ->
        if (targets?.isEmpty() == true || number == null) {
            EmptyState(
                Icons.Rounded.People, stringResource(R.string.card_new_number_nobody), stringResource(R.string.card_new_number_nobody_body),
                Modifier.padding(p),
            )
            return@ParleyScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Intro(draft.orEmpty())
            when {
                via == null -> PickWay(installed) { via = it }
                queue.finished -> Finished(queue, back)
                else -> CurrentPerson(
                    queue, app?.label ?: stringResource(R.string.detail_sms), ::openCurrent, ::sendCard,
                    onNext = ::next,
                    onStop = { set(queue.stop()) }, onChangeWay = { via = null },
                )
            }
        }
    }
}

/**
 * The people "Shared with" says have an old number of yours (null while it's read). Private contacts are named from
 * the vault, and left out in discreet mode.
 */
@Composable
private fun rememberOutdated(vm: AppViewModel, phones: List<String>): State<List<IntroQueue.Target>?> = produceState<List<IntroQueue.Target>?>(null) {
    val ledger = vm.c.people.shareLedger
    ledger.load()
    val shown = CardSharing.shown(vm.c, ledger.receipts.value, vm.privacy.value.privateHidden)
    value = ShareLedger.outdated(shown, phones, vm.countryIso).mapNotNull { p ->
        p.number?.let { NumberText.toE164(it, vm.countryIso) }?.let { IntroQueue.Target(p.name, it) }
    }
}

/** What happens, and the message itself. */
@Composable
private fun Intro(draft: String) {
    Text(
        stringResource(R.string.card_new_number_explainer), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(Modifier.fillMaxWidth()) {
        ParleyListItem(
            headlineContent = { Text(draft) },
            supportingContent = { Text(stringResource(R.string.intro_the_message)) },
            leadingContent = { Icon(Icons.Rounded.Badge, null) },
        )
    }
}

/** How to send: each chat app, then SMS. */
@Composable
private fun PickWay(installed: List<MessengerApp>, onPick: (String) -> Unit) {
    Text(stringResource(R.string.intro_open_in), style = MaterialTheme.typography.titleMedium)
    installed.forEach { a ->
        ParleyListItem(
            headlineContent = { Text(a.label) },
            supportingContent = { Text(stringResource(if (a.takesText) R.string.card_new_number_filled_in else R.string.card_new_number_for_pasting)) },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
            modifier = Modifier.clickable { onPick(a.packageName) },
        )
    }
    ParleyListItem(
        headlineContent = { Text(stringResource(R.string.detail_sms)) },
        supportingContent = { Text(stringResource(R.string.card_new_number_filled_in)) },
        leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) },
        modifier = Modifier.clickable { onPick(SMS) },
    )
}

@Composable
private fun Finished(queue: IntroQueue, back: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        Text(MessagingText.introSummary(LocalResources.current, queue), style = MaterialTheme.typography.titleMedium)
    }
    Button(back) { Text(stringResource(R.string.dc_done)) }
}

/** The person whose turn it is: open the chat (or SMS), or send the card; next, skip, stop, another way. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentPerson(
    queue: IntroQueue, way: String, onOpen: () -> Unit, onSendCard: () -> Unit, onNext: () -> Unit, onStop: () -> Unit, onChangeWay: () -> Unit,
) {
    val t = queue.current ?: return
    Text(MessagingText.introProgress(LocalResources.current, queue), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    LinearProgressIndicator(progress = { queue.index.toFloat() / queue.targets.size }, modifier = Modifier.fillMaxWidth())
    Text(t.name.ifBlank { Bidi.ltr(NumberText.formatInternational(t.number)) }, style = MaterialTheme.typography.headlineSmall)
    if (t.name.isNotBlank()) Text(Bidi.ltr(NumberText.formatInternational(t.number)), color = MaterialTheme.colorScheme.onSurfaceVariant)
    val wasOpened = queue.index in queue.opened
    Button(onOpen, Modifier.fillMaxWidth()) { Text(stringResource(if (wasOpened) R.string.intro_open_again else R.string.intro_open_in_app, way)) }
    OutlinedButton(onSendCard, Modifier.fillMaxWidth()) { Text(stringResource(R.string.card_new_number_send_card)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedButton(onNext) { Text(stringResource(if (wasOpened) R.string.pin_next else R.string.intro_skip)) }
        TextButton(onStop) { Text(stringResource(R.string.blk_stop)) }
        TextButton(onChangeWay) { Text(stringResource(R.string.intro_change_app)) }
    }
}

private const val SMS = "sms"

/** Opens the chat (or SMS, [via] = [SMS]) to [number] with [text]; false when it couldn't (the reason was shown). */
private fun openNewNumber(context: android.content.Context, via: String?, app: MessengerApp?, number: String, text: String): Boolean {
    val error = when {
        via == SMS -> MessengerLauncher.open(context, MessengerLinks.sms(number, number, text, MessengerLauncher.smsPackage(context)), null)
        app != null -> MessengerLauncher.openChat(context, app, number, text)
        else -> return false
    }
    if (error != null) {
        showMessage(context, error, long = true)
        return false
    }
    if (app != null && !app.takesText) showMessage(context, context.getString(R.string.card_new_number_copied), long = true)
    return true
}
