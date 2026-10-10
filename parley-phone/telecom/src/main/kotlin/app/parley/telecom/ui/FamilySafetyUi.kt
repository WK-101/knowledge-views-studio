// Family safety on the call screen: one state holder and the composables that read it.
@file:Suppress("MatchingDeclarationName")

package app.parley.telecom.ui

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.common.PhoneIdentity
import app.parley.common.calls.HelperStage
import app.parley.common.calls.Helpers
import app.parley.common.calls.SafeWords
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.HelperCalls
import app.parley.telecom.HelperProgress
import app.parley.telecom.HelperUi
import app.parley.telecom.R
import app.parley.telecom.SafeWordPrompt
import app.parley.telecom.TelecomGraph
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.CallColors
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import app.parley.ui.rowColors
import app.parley.ui.systemMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Family safety during a call: what the screen loaded for the call in front (safe-word questions, helpers) and
 * what the user did ("Says they're family", closing the card, choosing a helper). Kept for the screen's life.
 */
internal class FamilyCallState {
    /** Calls the user said claim to be family (More › Says they're family). */
    val claimed = mutableStateMapOf<String, Boolean>()

    /** Calls whose safe-word card was closed. */
    val dismissed = mutableStateMapOf<String, Boolean>()

    /** The safe-word questions for the call with this id (never answers). */
    var prompts by mutableStateOf<Pair<String, List<SafeWordPrompt>>?>(null)
    var helpers by mutableStateOf<List<HelperUi>>(emptyList())

    /** The "Add my helper" list is open. */
    var pickHelper by mutableStateOf(false)

    fun promptsFor(call: CallUi): List<SafeWordPrompt> = prompts?.takeIf { it.first == call.id }?.second.orEmpty()

    /** The facts [SafeWords] decides on, [seconds] after the call connected. */
    fun facts(call: CallUi, seconds: Long) = SafeWords.Facts(
        hasSafeWord = promptsFor(call).isNotEmpty(),
        // A call from someone: an unknown number calling in (or a hidden one), not one the user dialled.
        unknownCaller = call.incoming && (call.hidden || call.noContact),
        emergency = call.isEmergency || call.isConference,
        connected = call.state == CallState.ACTIVE || call.state == CallState.HOLDING,
        connectedSeconds = seconds,
        claimedFamily = claimed[call.id] == true,
        dismissed = dismissed[call.id] == true,
    )

    /** The helpers "Add my helper" may call now: never during an emergency call, never the caller themselves. */
    fun helpersFor(call: CallUi, others: List<CallUi>, joining: Boolean): List<HelperUi> {
        val connected = call.state == CallState.ACTIVE
        val emergency = call.isEmergency || others.any { it.isEmergency }
        if (!Helpers.offered(helpers.size, connected, emergency, others.size, joining)) return emptyList()
        return helpers.filterNot { h -> call.number != null && PhoneIdentity.same(h.number, call.number, null) }
    }
}

/** Loads the helpers once and the safe-word questions for the call in front (off the main thread) into [st]. */
@Composable
internal fun LoadFamilyCallState(primary: CallUi?, st: FamilyCallState) {
    LaunchedEffect(Unit) {
        st.helpers = runCatching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.helpers() } }.getOrDefault(emptyList())
    }
    val call = primary?.takeIf { !it.isEmergency && it.state != CallState.SELECT_ACCOUNT }
    LaunchedEffect(call?.id, call?.number) {
        if (call == null) return@LaunchedEffect
        val number = call.number?.takeIf { !call.hidden && it.isNotBlank() }
        val found = runCatching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.safeWordsFor(number, call.accountId) } }.getOrDefault(emptyList())
        st.prompts = call.id to found
    }
}

/** The helper being brought in and the safe-word card, under the caller. */
@Composable
internal fun FamilySafetyCards(primary: CallUi, live: List<CallUi>, st: FamilyCallState, onUnlock: (() -> Unit) -> Unit) {
    val join by HelperCalls.join.collectAsStateWithLifecycle()
    join?.let { j -> HelperJoinCard(HelperCalls.progress(j, live)) }
    SafeWordCard(primary, st, onUnlock)
}

/**
 * "Claims to be family? Ask: what's our word?" on a call from an unknown number after 20 seconds, or at once after
 * More › Says they're family. The question shows only while the phone is unlocked; the answer only while the user
 * presses and holds it, after the fingerprint or screen lock when the phone or Parley is locked.
 */
@Composable
internal fun SafeWordCard(call: CallUi, st: FamilyCallState, onUnlock: (() -> Unit) -> Unit) {
    val seconds by rememberCallSeconds(call.connectTimeMillis)
    if (!SafeWords.cardShows(st.facts(call, seconds))) return
    val prompts = st.promptsFor(call)
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        shape = ParleyShapes.card,
        modifier = Modifier.padding(top = Spacing.l).widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = Spacing.l, bottom = Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FamilyRestroom, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.m))
                Text(
                    stringResource(R.string.safeword_card_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                IconButton({ st.dismissed[call.id] = true }) { Icon(Icons.Rounded.Close, stringResource(R.string.safeword_close)) }
            }
            Column(Modifier.padding(end = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                prompts.forEach { p -> SafeWordRow(p, labelled = prompts.size > 1, onUnlock = onUnlock) }
            }
        }
    }
}

/**
 * Whether a safe word's answer may show: the phone unlocked (unlocking is the check) and, with Parley's app lock
 * locked, the fingerprint or screen lock confirmed once for this card. The answer is read only when first shown.
 */
private class Reveal(private val label: String, private val appLocked: () -> Boolean) {
    var confirmed by mutableStateOf(false)
    var revealed by mutableStateOf(false)
    var answer by mutableStateOf<String?>(null)

    /** True when the answer may show now; otherwise [ask] confirms who it is first (hold again afterwards). */
    fun mayShow(locked: Boolean, unlock: () -> Unit, ask: () -> Unit): Boolean {
        when {
            locked -> unlock()
            // Read at each reveal: an app lock that engaged during the call is honoured too.
            appLocked() && !confirmed -> ask()
            else -> return true
        }
        return false
    }

    suspend fun load() {
        if (answer == null) answer = runCatching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.safeWordAnswer(label) } }.getOrNull()
    }
}

@Composable
private fun SafeWordRow(p: SafeWordPrompt, labelled: Boolean, onUnlock: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locked by rememberUpdatedState(rememberKeyguardLocked())
    // Parley's own app lock, read from memory each time the answer is asked for.
    val reveal = remember(p.label) { Reveal(p.label) { runCatching { TelecomGraph.dependencies.appLockLocked() }.getOrDefault(true) } }
    val confirmTitle = stringResource(R.string.safeword_confirm_title)
    // Android 10 without a fingerprint: the keyguard's own "confirm your PIN" screen.
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) reveal.confirmed = true
    }
    val mayShow = {
        reveal.mayShow(
            locked,
            unlock = { onUnlock { reveal.confirmed = true } },
            ask = { confirmItsYou(context, confirmTitle, onFallback = { i -> credential.launch(i) }) { ok -> if (ok) reveal.confirmed = true } },
        )
    }
    val show = {
        reveal.revealed = true
        scope.launch { reveal.load() }
        Unit
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (labelled) Text(p.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (locked) stringResource(R.string.safeword_ask_locked) else stringResource(R.string.safeword_ask, p.question),
            style = MaterialTheme.typography.bodyLarge,
        )
        AnswerBox(reveal, p.label, mayShow, show)
    }
}

/** The answer, shown only while held (TalkBack: a double tap shows and hides it, since holding is awkward there). */
@Composable
private fun AnswerBox(reveal: Reveal, key: String, mayShow: () -> Boolean, show: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val showLabel = stringResource(R.string.safeword_show_answer)
    val shownState = stringResource(R.string.safeword_answer_shown)
    val shown = reveal.answer?.takeIf { reveal.revealed }
    SecureWhile(reveal.revealed)
    Surface(
        color = if (reveal.revealed) scheme.primaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (reveal.revealed) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        shape = ParleyShapes.card,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            // Letting go hides it again.
            .pointerInput(key) {
                detectTapGestures(onPress = {
                    if (mayShow()) {
                        show()
                        tryAwaitRelease()
                        reveal.revealed = false
                    }
                })
            }
            .semantics {
                onClick(showLabel) {
                    if (reveal.revealed) reveal.revealed = false else if (mayShow()) show()
                    true
                }
                // Never read out by itself (the caller may hear it on speaker): TalkBack says "Answer shown", and
                // reads the answer only when the user moves to it.
                if (shown != null) stateDescription = shownState
            },
    ) {
        Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            if (shown == null) {
                Icon(Icons.Rounded.Visibility, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.m))
                Text(stringResource(R.string.safeword_hold), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(shown, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * While [on], the call screen's window is secure (no screenshots, screen recording or casting of the answer); the
 * window's own setting ("Hide screen content") is put back afterwards.
 */
@Composable
private fun SecureWhile(on: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(on, window) {
        val had = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (on && !had) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (on && !had) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Confirms it's the phone's owner with a strong biometric or the screen lock (the platform prompt, as Parley's app
 * lock does). Without a screen lock there's nothing to confirm. Android 10 without a fingerprint hands [onFallback]
 * the keyguard's own confirmation screen instead.
 */
private fun confirmItsYou(context: Context, title: String, onFallback: (android.content.Intent) -> Unit, onResult: (Boolean) -> Unit) {
    val km = context.getSystemService(KeyguardManager::class.java)
    if (km?.isDeviceSecure != true) {
        onResult(true)
        return
    }
    if (Build.VERSION.SDK_INT < 30) {
        @Suppress("DEPRECATION") // canAuthenticate() without authenticators is the only form on Android 10.
        val bio = context.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        if (!bio) {
            @Suppress("DEPRECATION") // The device-credential screen is Android 10's fallback to a biometric prompt.
            km.createConfirmDeviceCredentialIntent(title, null)?.let(onFallback) ?: onResult(false)
            return
        }
    }
    val prompt = BiometricPrompt.Builder(context).setTitle(title).apply {
        @Suppress("DEPRECATION") // setDeviceCredentialAllowed is the only form on Android 10.
        if (Build.VERSION.SDK_INT >= 30) {
            setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            setDeviceCredentialAllowed(true)
        }
    }.build()
    runCatching {
        prompt.authenticate(
            CancellationSignal(), context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
    }.onFailure { onResult(false) }
}

/**
 * Bringing a helper in. "Calling Sam to join…" (Cancel) while it rings, then "Sam answered" with a big Merge now
 * (or Swap where the network can't merge), "Sam joined the call" once merged, and "Sam didn't answer" if not.
 */
@Composable
internal fun HelperJoinCard(progress: HelperProgress) {
    val scheme = MaterialTheme.colorScheme
    val name = progress.join.name
    LaunchedEffect(progress.call?.id) { if (progress.call != null) HelperCalls.markSeen() }
    // "Joined" and "didn't answer" say so for a moment, then go.
    LaunchedEffect(progress.stage) {
        if (progress.stage == HelperStage.JOINED || progress.stage == HelperStage.GONE) {
            delay(HELPER_NOTICE_MS)
            HelperCalls.dismiss()
        }
    }
    Surface(
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        shape = ParleyShapes.card,
        modifier = Modifier.padding(top = Spacing.l).widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GroupAdd, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.m))
                val text = when (progress.stage) {
                    HelperStage.CALLING -> stringResource(R.string.helper_calling, name)
                    HelperStage.ANSWERED -> stringResource(R.string.helper_answered, name)
                    HelperStage.JOINED -> stringResource(R.string.helper_joined, name)
                    HelperStage.GONE -> stringResource(R.string.helper_gone, name)
                }
                Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            }
            when (progress.stage) {
                HelperStage.CALLING -> {
                    Text(stringResource(R.string.helper_calling_body), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ HelperCalls.cancel(progress) }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.helper_cancel)) }
                }
                HelperStage.ANSWERED -> {
                    val call = progress.call
                    if (call != null && call.canMerge) {
                        Button(
                            onClick = { CallManager.merge(call.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = CallColors.Accept, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.CallMerge, null)
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.helper_merge_now), style = MaterialTheme.typography.titleMedium)
                        }
                    } else if (call != null) {
                        // Some networks can't join calls: each can still be talked to in turn.
                        Text(stringResource(R.string.helper_cant_merge), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton({ CallManager.swap(call.id) }, Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Rounded.SwapCalls, null)
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.incall_swap))
                        }
                    }
                }
                HelperStage.JOINED, HelperStage.GONE -> Unit
            }
        }
    }
}

private const val HELPER_NOTICE_MS = 4000L

/** The helpers to choose from ("Add my helper" with more than one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HelperSheet(helpers: List<HelperUi>, onPick: (HelperUi) -> Unit, onDismiss: () -> Unit) {
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.helper_add)) {
        Text(
            stringResource(R.string.helper_add_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        helpers.forEach { h ->
            ParleyListItem(
                headlineContent = { Text(h.name) },
                supportingContent = { Text(Bidi.ltr(h.number)) },
                leadingContent = { Avatar(h.name, null, 40.dp) },
                colors = rowColors(),
                modifier = Modifier.clickable { onDismiss(); onPick(h) },
            )
        }
        Spacer(Modifier.size(Spacing.xl))
    }
}

/** Starts bringing [helper] into [call], on the same SIM; a call that can't be placed says why. */
internal fun startHelper(context: Context, call: CallUi, helper: HelperUi) {
    HelperCalls.start(call.id, helper, call.accountId) { why -> systemMessage(context, why) }
}

/** Simple mode: one big "Add my helper" above the controls (one helper at once; several open the list). */
@Composable
internal fun SimpleHelperButton(helpers: List<HelperUi>, onAdd: () -> Unit) {
    if (helpers.isEmpty()) return
    FilledTonalButton(onAdd, Modifier.widthIn(max = CallButtonSize.panelMaxWidth).fillMaxWidth().heightIn(min = 64.dp).padding(bottom = Spacing.l)) {
        Icon(if (helpers.size == 1) Icons.Rounded.Person else Icons.Rounded.GroupAdd, null)
        Spacer(Modifier.width(Spacing.s))
        Text(
            if (helpers.size == 1) stringResource(R.string.helper_add_named, helpers.first().name) else stringResource(R.string.helper_add),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
    }
}
