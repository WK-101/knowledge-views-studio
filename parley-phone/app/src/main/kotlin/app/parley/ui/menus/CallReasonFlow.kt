package app.parley.ui.menus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.parley.AppViewModel
import app.parley.R
import app.parley.calls.CallReasons
import app.parley.common.calls.CallReason
import app.parley.common.calls.ReasonWay
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import app.parley.ui.common.Format
import kotlinx.coroutines.withTimeoutOrNull

/**
 * I12 "Call with a reason…" for [target]: a reason, then **Call** with it when the SIM's network carries call subjects,
 * or **Text first** (the messaging app opens with "Calling you about …"; back in Parley, "Call Ana now?"). The call
 * takes the usual path (dial guard, SIM choice, confirmation). [onDone] closes the flow.
 */
@Composable
fun CallReasonFlow(vm: AppViewModel, target: ReasonTarget, onDone: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    var stage by rememberSaveable(target) { mutableStateOf(ReasonStage.SHEET) }
    var reason by rememberSaveable(target) { mutableStateOf("") }
    var facts by remember(target) { mutableStateOf<CallReasons.Facts?>(null) }
    LaunchedEffect(target) {
        // M4: an emergency number is called at once, checked before anything else is asked of the phone (SIMs, phone
        // accounts, apps): nothing may stand between the user and the call. Facts that take too long: the usual call.
        val f = reasonFacts(vm, context, target)
        facts = f
        if (f == null || f.reason.emergency) {
            onDone()
            vm.requestCall(target.number, target.name, simId = target.simId)
        }
    }
    val who = target.name?.takeIf { it.isNotBlank() } ?: Bidi.ltr(Format.number(target.number, vm.countryIso))

    // Back from the messaging app: offer the call.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, stage) {
        var left = false
        val observer = LifecycleEventObserver { _, e ->
            if (stage != ReasonStage.TEXTING) return@LifecycleEventObserver
            if (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_STOP) left = true
            if (e == Lifecycle.Event.ON_RESUME && left) stage = ReasonStage.ASK_CALL
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    when (stage) {
        ReasonStage.SHEET -> facts?.takeIf { !it.reason.emergency }?.let { f ->
            ReasonSheet(
                who, f, reason, onReason = { reason = it }, onDismiss = onDone,
                onCall = { subject ->
                    onDone()
                    vm.requestCall(target.number, target.name, simId = target.simId, subject = subject)
                },
                onTextFirst = { clean ->
                    val text = res.getString(R.string.reason_message, clean)
                    if (CallReasons.textFirst(context, target.number, text)) {
                        stage = ReasonStage.TEXTING
                    } else {
                        vm.toast(res.getString(R.string.reason_no_app))
                    }
                },
            )
        }
        ReasonStage.TEXTING -> Unit
        ReasonStage.ASK_CALL -> ConfirmDialog(
            title = stringResource(R.string.reason_call_now_title, who),
            text = stringResource(R.string.reason_call_now_body),
            confirmLabel = stringResource(R.string.reason_call),
            icon = Icons.Rounded.Call,
            // This question is the confirmation: "Confirm before calling" isn't asked again.
            onConfirm = {
                onDone()
                vm.requestCall(target.number, target.name, skipConfirm = true, simId = target.simId)
            },
            onDismiss = onDone,
            dismissLabel = stringResource(R.string.reason_not_now),
        )
    }
}

/** M4: the facts for [target], or null to call at once: an emergency number (checked first), or facts that took too long. */
private suspend fun reasonFacts(vm: AppViewModel, context: android.content.Context, target: ReasonTarget): CallReasons.Facts? {
    if (CallReasons.emergency(vm.c, target.number)) return null
    return withTimeoutOrNull(CallReasons.FACTS_MS) { runCatching { CallReasons.facts(context, vm.c, target.number, target.simId) }.getOrNull() }
}

/** The sheet: the reason, what will happen with it, and the ways it can go (the best one filled). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasonSheet(
    who: String, f: CallReasons.Facts, reason: String, onReason: (String) -> Unit, onDismiss: () -> Unit,
    onCall: (String?) -> Unit, onTextFirst: (String) -> Unit,
) {
    val ways = CallReason.ways(f.reason)
    val clean = CallReason.clean(reason, f.maxLength)
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.reason_title)) {
        Column(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(who, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = reason,
                onValueChange = { onReason(it.take(f.maxLength?.coerceAtMost(MAX_TYPED) ?: MAX_TYPED)) },
                label = { Text(stringResource(R.string.reason_field)) },
                placeholder = { Text(stringResource(R.string.reason_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val explain = when {
                ReasonWay.SUBJECT in ways -> R.string.reason_subject_body
                ReasonWay.TEXT_FIRST in ways -> R.string.reason_text_body
                else -> R.string.reason_no_way
            }
            Text(stringResource(explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
                ReasonButtons(ways, clean, onCall, onTextFirst)
            }
        }
    }
}

@Composable
private fun ReasonButtons(ways: List<ReasonWay>, clean: String?, onCall: (String?) -> Unit, onTextFirst: (String) -> Unit) {
    val text = ReasonWay.TEXT_FIRST in ways
    when {
        ReasonWay.SUBJECT in ways -> {
            if (text) TextFirstButton(filled = false, enabled = clean != null) { clean?.let(onTextFirst) }
            CallButton { onCall(clean) }
        }
        text -> {
            TextButton(onClick = { onCall(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reason_call)) }
            TextFirstButton(filled = true, enabled = clean != null) { clean?.let(onTextFirst) }
        }
        else -> CallButton { onCall(null) }
    }
}

@Composable
private fun CallButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Rounded.Call, null, Modifier.padding(end = Spacing.xs))
        Text(stringResource(R.string.reason_call))
    }
}

@Composable
private fun TextFirstButton(filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Rounded.Message, null, Modifier.padding(end = Spacing.xs))
        Text(stringResource(R.string.reason_text_first))
    }
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { content() }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { content() }
    }
}

/** What can be typed, before a network's own limit cuts it further. */
private const val MAX_TYPED = 80
