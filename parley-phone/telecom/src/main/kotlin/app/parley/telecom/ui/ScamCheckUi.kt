// The sheet, its actions and the name reply helper belong together.
@file:Suppress("MatchingDeclarationName")

package app.parley.telecom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppSettingsAlt
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.parley.common.calls.NameReply
import app.parley.common.calls.ScamCheck
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import app.parley.ui.rowColors

/** What the "Is this a scam?" sheet can do: during the call, or on the post-call card once it has ended. */
internal class ScamCheckActions(
    /** Check it's really them: hang up and call the saved number. */
    val onVerify: (() -> Unit)?,
    /** Ask the family safe word (a safe word is set): shows the safe-word card. */
    val onSafeWord: (() -> Unit)? = null,
    /** Hang up, then open the keypad to call the official number. */
    val onCallOfficial: (() -> Unit)? = null,
    /** Hang up (the post-call card offers Block and Report next). */
    val onHangUp: (() -> Unit)? = null,
    /** After the call: Block and Report the number. */
    val onBlock: (() -> Unit)? = null,
    val onReport: (() -> Unit)? = null,
    /** The post-call card offers Block and Report after hanging up (a visible number). */
    val blockReportNext: Boolean = false,
    /** "It wasn't them": a call that showed "This number never calls you" was a spoofed caller ID. */
    val onNotThem: (() -> Unit)? = null,
)

/**
 * "Is this a scam?" (docs/CALL_SCREEN_DESIGN.md, 5.3): the usual warning signs as a calm checklist, then the safe
 * ways to check. Parley can't hear the call, so it never says whether this one is a scam. Nothing here shows the
 * caller's details or the safe word itself, so it can open over the lock screen like the call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScamCheckSheet(live: Boolean, actions: ScamCheckActions, onDismiss: () -> Unit) {
    val act = { block: () -> Unit -> { onDismiss(); block() } }
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.scam_title)) {
        Text(
            stringResource(if (live) R.string.scam_body else R.string.scam_body_ended),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.s),
        )
        Heading(stringResource(R.string.scam_signs_heading))
        ScamCheck.SIGNS.forEach { sign ->
            val (icon, title, detail) = signTexts(sign)
            ParleyListItem(
                headlineContent = { Text(stringResource(title)) },
                supportingContent = { Text(stringResource(detail)) },
                leadingContent = { Icon(icon, null) },
                colors = rowColors(),
            )
        }
        Heading(stringResource(R.string.scam_ways_out_heading))
        actions.onSafeWord?.let { Way(Icons.Rounded.FamilyRestroom, R.string.scam_safe_word, R.string.scam_safe_word_detail, act(it)) }
        actions.onVerify?.let { Way(Icons.Rounded.VerifiedUser, R.string.verify_title, R.string.verify_explainer, act(it)) }
        actions.onCallOfficial?.let { Way(Icons.Rounded.Phone, R.string.scam_official_number, R.string.scam_official_number_detail, act(it)) }
        actions.onHangUp?.let {
            Way(Icons.Rounded.CallEnd, R.string.notif_hang_up, if (actions.blockReportNext) R.string.scam_hang_up_detail else null, act(it))
        }
        actions.onBlock?.let { Way(Icons.Rounded.Block, R.string.scam_block, null, act(it)) }
        actions.onReport?.let { Way(Icons.Rounded.Flag, R.string.scam_report, null, act(it)) }
        actions.onNotThem?.let { Way(Icons.Rounded.PersonOff, R.string.scam_not_them, R.string.scam_not_them_detail, act(it)) }
        Spacer(Modifier.height(Spacing.xl))
    }
}

/**
 * The same checklist outside a call (Tools › Is this a scam?), to read before one: the warning signs, with nothing
 * to do to a call.
 */
@Composable
fun ScamSignsGuide(onDismiss: () -> Unit) = ScamCheckSheet(live = false, actions = ScamCheckActions(onVerify = null), onDismiss = onDismiss)

private fun signTexts(sign: ScamCheck.Sign): Triple<ImageVector, Int, Int> = when (sign) {
    ScamCheck.Sign.PRESSURE -> Triple(Icons.Rounded.Timer, R.string.scam_sign_pressure, R.string.scam_sign_pressure_detail)
    ScamCheck.Sign.UNUSUAL_PAYMENT -> Triple(Icons.Rounded.CardGiftcard, R.string.scam_sign_payment, R.string.scam_sign_payment_detail)
    ScamCheck.Sign.CODES -> Triple(Icons.Rounded.Password, R.string.scam_sign_codes, R.string.scam_sign_codes_detail)
    ScamCheck.Sign.REMOTE_ACCESS -> Triple(Icons.Rounded.AppSettingsAlt, R.string.scam_sign_remote, R.string.scam_sign_remote_detail)
    ScamCheck.Sign.SAFE_ACCOUNT -> Triple(Icons.Rounded.Savings, R.string.scam_sign_safe_account, R.string.scam_sign_safe_account_detail)
    ScamCheck.Sign.FAMILY_EMERGENCY -> Triple(Icons.Rounded.FamilyRestroom, R.string.scam_sign_family, R.string.scam_sign_family_detail)
}

@Composable
private fun Heading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
    )
}

@Composable
private fun Way(icon: ImageVector, title: Int, detail: Int?, onClick: () -> Unit) {
    ParleyListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = detail?.let { d -> { Text(stringResource(d)) } },
        leadingContent = { Icon(icon, null) },
        colors = rowColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The "Text me your name" reply for [call], or null: a saved caller, no number to text, or turned off. */
internal fun nameReplyFor(call: CallUi): String? = NameReply.offered(
    runCatching { TelecomGraph.dependencies.appearance.value.nameReply }.getOrDefault(""),
    savedCaller = !(call.unknown || call.noContact),
    hasNumber = !call.hidden && !call.number.isNullOrBlank(),
    emergency = call.isEmergency,
)
