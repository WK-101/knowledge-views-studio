package app.parley.telecom.ui

import android.app.KeyguardManager
import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.common.NotificationPrivacy
import app.parley.common.Verification
import app.parley.common.circle.GoodTime
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallTiming
import app.parley.telecom.CallUi
import app.parley.telecom.CallerMemory
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Who is calling, in order of importance (docs/CALL_SCREEN_DESIGN.md): the photo, the name, one calm line with the
 * label and number, small tags for the SIM and the number's verification (warnings in the error colour), the status
 * or running time in a pill, and the caller card (who is this, the pinned note, open promises, the last call).
 * [compact] (keypad open) keeps only the name, secondary line and status.
 */
@Composable
internal fun CallerHeader(
    call: CallUi,
    ended: Boolean,
    onOpenContact: (CallUi) -> Unit,
    compact: Boolean,
    timing: CallTiming?,
    avatarSize: Dp,
    onReply: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = ringingActions(call, ended, onReply).fillMaxWidth()) {
        if (!compact) {
            CallerAvatar(call, ended, timing, avatarSize, onOpenContact)
            Spacer(Modifier.height(Spacing.l))
        }
        Text(
            call.displayTitle,
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // The caller's pronouns, right under the name.
        call.pronouns?.let { CallerPronouns(it) }
        SecondaryLine(call)
        call.subtitle?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (!compact) SubjectLine(call)
        CallTags(call, zone = if (ended) null else rememberCallerZone(call))
        Spacer(Modifier.height(Spacing.m))
        StatusPill(call, ended)
        if (!ended && call.state == CallState.RINGING) RangThroughLine(call)
        // I2: "Looks like a sales line (your calls)", with Why?
        ReputationLine(call, ended, compact)
        if (!ended && call.state != CallState.RINGING) RemainingLine(timing)
        if (!compact) CallerCard(call, ended)
    }
}

/** TalkBack: while ringing, the caller's name offers answer, decline, reply, stop ringing and block as actions. */
@Composable
private fun ringingActions(call: CallUi, ended: Boolean, onReply: () -> Unit): Modifier {
    if (call.state != CallState.RINGING || ended) return Modifier
    val res = LocalResources.current
    return Modifier.semantics(mergeDescendants = true) {
        customActions = buildList {
            add(CustomAccessibilityAction(res.getString(R.string.incall_answer)) { CallManager.answer(call.id); true })
            add(CustomAccessibilityAction(res.getString(R.string.incall_decline)) { CallManager.reject(call.id); true })
            if (!call.hidden && !call.number.isNullOrBlank()) add(CustomAccessibilityAction(res.getString(R.string.incall_reply_a11y)) { onReply(); true })
            if (!call.silenced) add(CustomAccessibilityAction(res.getString(R.string.incall_stop_ringing)) { CallManager.ignore(call.id); true })
            if (call.canBlockAndDecline) {
                add(CustomAccessibilityAction(res.getString(R.string.incall_block_decline)) { CallManager.blockAndDecline(call.id); true })
            }
        }
    }
}

/** The photo, inside the remaining-time ring when the call will be ended, with a slow halo while it rings. */
@Composable
private fun CallerAvatar(call: CallUi, ended: Boolean, timing: CallTiming?, size: Dp, onOpenContact: (CallUi) -> Unit) {
    val ringing = call.state == CallState.RINGING && !ended
    val still = ParleyMotion.reducedMotion()
    val halo = MaterialTheme.colorScheme.primary
    // Read only while drawing, so the halo redraws without recomposing the header; a still ring with animations off.
    val breathing = if (ringing && !still) {
        rememberInfiniteTransition(label = "halo").animateFloat(0f, 1f, infiniteRepeatable(tween(HALO_MS), RepeatMode.Restart), label = "t")
    } else {
        null
    }
    val dim by animateFloatAsState(if (!ended && call.state == CallState.HOLDING) HELD_ALPHA else 1f, ParleyMotion.effects(), label = "held")
    CallTimeRing(if (ended) null else timing, size) {
        Avatar(
            call.title, call.photoUri, size = size,
            modifier = Modifier
                .alpha(dim)
                .drawBehind {
                    if (ringing) {
                        val r = this.size.minDimension / 2
                        val breathe = breathing?.value ?: 0f
                        drawCircle(halo.copy(alpha = HALO_ALPHA * (1f - breathe)), radius = r * (1f + HALO_GROW * breathe))
                    }
                }
                .clickable(enabled = !call.hidden, onClickLabel = stringResource(R.string.incall_open_contact)) { onOpenContact(call) },
        )
    }
}

/** "Mobile · +44 20 …", or for an unknown number while it rings, "Not in your contacts · Leeds". */
@Composable
private fun SecondaryLine(call: CallUi) {
    val sep = stringResource(R.string.tc_separator)
    val label = call.label?.let { l -> if (NotificationPrivacy.isVaultLabel(l)) stringResource(R.string.tc_private_label) else l }
    val parts = buildList {
        label?.let(::add)
        call.number?.takeIf { call.name != null }?.let { add(Bidi.ltr(it)) }
        if (call.unknown && call.state == CallState.RINGING) {
            add(stringResource(R.string.incall_not_in_contacts))
            call.location?.let(::add)
        }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(sep), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xs),
    )
}

/**
 * L10: the subject the caller sent with the call (RCS Call Composer, `EXTRA_CALL_SUBJECT`), in quotes as their own
 * words. It's plain text that [app.parley.common.calls.CallSubject] already cleaned, never a link.
 */
@Composable
private fun SubjectLine(call: CallUi) {
    val subject = call.subject ?: return
    Row(Modifier.padding(top = Spacing.s).widthIn(max = 480.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.AutoMirrored.Rounded.Subject, null, Modifier.padding(top = Spacing.xxs).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.s))
        Text(
            stringResource(R.string.incall_subject, subject), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = subject },
        )
    }
}

/**
 * P1: why a ringing call gets through although screening would otherwise have kept it quiet ("Rang through: called
 * twice in 3 min", "Rang through: expecting a call"), under the status pill: it shows the blocking rules at work.
 */
@Composable
private fun RangThroughLine(call: CallUi) {
    // I7: the note it came from is named only while the phone is unlocked.
    val text = (call.rangThroughUnlocked?.takeIf { !rememberKeyguardLocked() } ?: call.rangThrough)?.takeIf { !call.silenced } ?: return
    Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * SIM, HD voice and Wi-Fi calling (once connected, when the network says so), verification, emergency, the screening
 * verdict and the caller's local time when it differs from yours: quiet tags, warnings in the error colours.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallTags(call: CallUi, zone: ZoneId?) {
    val tags = callTagSpecs(call)
    if (tags.isEmpty() && zone == null) return
    FlowRow(
        Modifier.padding(top = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        tags.forEach { Tag(it) }
        if (zone != null) LocalTimeTag(zone)
    }
}

/**
 * The caller's time zone, when it differs from yours right now (international callers, or across a country's zones),
 * worked out offline from the number after the screen is up, off the main thread (it reads libphonenumber's map). Null for a hidden number.
 */
@Composable
private fun rememberCallerZone(call: CallUi): ZoneId? {
    val number = call.number?.takeIf { !call.hidden && it.isNotBlank() }
    val zone by produceState<ZoneId?>(null, number, call.accountId) {
        value = if (number == null) null else withContext(Dispatchers.IO) {
            runCatching { TelecomGraph.dependencies.callerZone(number, call.accountId)?.let(ZoneId::of) }.getOrNull()
        }
    }
    return zone?.takeIf { GoodTime.differs(it, ZoneId.systemDefault(), System.currentTimeMillis()) }
}

/** "9:40 pm there", refreshed every half minute. */
@Composable
private fun LocalTimeTag(zone: ZoneId) {
    val now by produceState(System.currentTimeMillis(), zone) {
        while (true) {
            value = System.currentTimeMillis()
            delay(LOCAL_TIME_REFRESH_MS)
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "jmm"), locale) }
    Tag(TagSpec(Icons.Rounded.Schedule, stringResource(R.string.incall_time_there, Instant.ofEpochMilli(now).atZone(zone).format(format))))
}

@Composable
private fun callTagSpecs(call: CallUi): List<TagSpec> {
    val connected = call.state == CallState.ACTIVE || call.state == CallState.HOLDING
    return buildList {
        // While dialling, the SIM already shows in the status.
        call.accountLabel?.takeIf { !call.state.dialling }?.let { add(TagSpec(Icons.Rounded.SimCard, it)) }
        if (connected && call.hdAudio) add(TagSpec(Icons.Rounded.Hd, stringResource(R.string.incall_hd_voice)))
        if (connected && call.wifi) add(TagSpec(Icons.Rounded.Wifi, stringResource(R.string.incall_wifi_calling)))
        verificationTag(call.verification)?.let(::add)
        if (call.isEmergency) add(TagSpec(Icons.Rounded.Warning, stringResource(R.string.incall_emergency_call), warn = true))
        // The caller marked it urgent (Call Composer): their claim, shown as information, not as a warning.
        if (call.urgent && call.state == CallState.RINGING) add(TagSpec(Icons.Rounded.PriorityHigh, stringResource(R.string.incall_urgent)))
        // Screening verdict: "Likely spam · FTC list", "Allowed by 'Plumber'".
        call.verdict?.takeIf { call.state == CallState.RINGING }?.let {
            add(TagSpec(if (call.verdictWarn) Icons.Rounded.Warning else Icons.Rounded.Shield, it, warn = call.verdictWarn))
        }
    }
}

/** "Verified number", or the "Possibly spoofed" warning. */
@Composable
private fun verificationTag(v: Verification): TagSpec? = when (v) {
    Verification.PASSED -> TagSpec(Icons.Rounded.Verified, stringResource(R.string.incall_verified_number))
    Verification.FAILED -> TagSpec(Icons.Rounded.Warning, stringResource(R.string.incall_possibly_spoofed), warn = true)
    Verification.NOT_VERIFIED -> null
}

private class TagSpec(val icon: ImageVector, val text: String, val warn: Boolean = false)

private val CallState.dialling: Boolean get() = this == CallState.DIALING || this == CallState.CONNECTING || this == CallState.NEW

@Composable
private fun Tag(spec: TagSpec) {
    val icon = spec.icon
    val text = spec.text
    val warn = spec.warn
    val scheme = MaterialTheme.colorScheme
    val ink = if (warn) scheme.onErrorContainer else scheme.onSurfaceVariant
    Row(
        Modifier
            .clip(ParleyShapes.pill)
            .then(if (warn) Modifier.background(scheme.errorContainer) else Modifier)
            .padding(horizontal = if (warn) Spacing.m else Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = ink)
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = if (warn) FontWeight.SemiBold else null)
    }
}

/**
 * The status ("Incoming call", "Calling via Work…", "On hold") or the running time, in a pill. On hold is unmistakable:
 * the pill turns to the tertiary container with a pause icon (and the photo dims), not only the words.
 */
@Composable
private fun StatusPill(call: CallUi, ended: Boolean) {
    val text = statusText(call, ended)
    if (text == "") return
    val held = !ended && call.state == CallState.HOLDING
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (held) scheme.tertiaryContainer else scheme.surfaceContainerHighest, ParleyMotion.effects(), label = "pill")
    val ink = if (held) scheme.onTertiaryContainer else scheme.onSurface
    Surface(color = container, contentColor = ink, shape = ParleyShapes.pill) {
        Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs + Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
            if (held) {
                Icon(Icons.Rounded.Pause, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.s))
            }
            if (text != null) {
                Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            } else {
                CallTimer(call.connectTimeMillis)
            }
        }
    }
}

/** The status in words, null for the running time, or "" for nothing to say. */
@Composable
private fun statusText(call: CallUi, ended: Boolean): String? = when {
    ended -> call.disconnectReason ?: stringResource(R.string.incall_call_ended)
    call.silenced -> call.silenceReason ?: stringResource(R.string.call_silenced_rules)
    call.state == CallState.RINGING -> stringResource(R.string.notif_incoming_call)
    // The SIM the call goes out on, even before Telecom has settled on it.
    call.state.dialling ->
        call.accountLabel?.let { stringResource(R.string.incall_status_calling_via, it) } ?: stringResource(R.string.incall_status_calling)
    call.state == CallState.HOLDING -> stringResource(R.string.incall_status_on_hold)
    call.state == CallState.SELECT_ACCOUNT -> stringResource(R.string.incall_status_choose_sim)
    call.state == CallState.DISCONNECTING -> stringResource(R.string.incall_status_ending)
    call.state == CallState.ACTIVE -> null
    else -> ""
}

@Composable
private fun CallTimer(connectTime: Long) {
    val elapsed by rememberCallSeconds(connectTime)
    val spoken = stringResource(R.string.incall_duration_description, spokenDuration(LocalResources.current, elapsed))
    // Tabular digits, so the pill doesn't wobble as the seconds tick.
    Text(
        clockText(elapsed), style = MaterialTheme.typography.titleMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

/** Who is this, the pinned note, the last note and open promises, and the last call: one quiet card. */
@Composable
private fun CallerCard(call: CallUi, ended: Boolean) {
    // The last note and open promises, only while unlocked unless allowed on the lock screen.
    val memory = call.memory?.takeIf { !ended && !it.isEmpty && (it.onLockScreen || !rememberKeyguardLocked()) }
    if (listOfNotNull(call.context, call.note, call.lastCall, memory).isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = ParleyShapes.card,
        modifier = Modifier.padding(top = Spacing.l).widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            call.context?.let {
                CardLine(Icons.Rounded.Info) { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            call.note?.let { CardLine(Icons.Rounded.PushPin) { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) } }
            memory?.let { m -> CardLine(Icons.AutoMirrored.Rounded.Notes) { Column { MemoryLines(m) } } }
            call.lastCall?.let {
                CardLine(Icons.Rounded.History) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CardLine(icon: ImageVector, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = Spacing.xxs).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.m))
        content()
    }
}

/** Whether the keyguard is showing, re-checked every second (the user may unlock with the call screen up). */
@Composable
internal fun rememberKeyguardLocked(): Boolean {
    val context = LocalContext.current
    val km = remember { context.getSystemService(KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(km?.isKeyguardLocked ?: true) }
    LaunchedEffect(km) {
        while (true) {
            locked = km?.isKeyguardLocked ?: true
            delay(1000)
        }
    }
    return locked
}

/** "Last note: …" and up to three open promises. */
@Composable
private fun MemoryLines(m: CallerMemory) {
    m.lastNote?.takeIf { it.isNotBlank() }?.let {
        Text(stringResource(R.string.memory_last_note, it), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    m.promises.take(3).forEach { p ->
        Text(stringResource(R.string.memory_open_promise, p), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (m.promises.size > 3) {
        val more = m.promises.size - 3
        Text(pluralStringResource(R.plurals.memory_more_promises, more, more), style = MaterialTheme.typography.bodySmall)
    }
}

private const val HALO_MS = 1800
private const val HELD_ALPHA = 0.55f
private const val LOCAL_TIME_REFRESH_MS = 30_000L
private const val HALO_ALPHA = 0.35f
private const val HALO_GROW = 0.22f
