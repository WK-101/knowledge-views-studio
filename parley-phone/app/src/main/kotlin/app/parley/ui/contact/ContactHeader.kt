package app.parley.ui.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.MessengerApp
import app.parley.common.calls.CallReason
import app.parley.common.people.MessengerPrefs
import app.parley.common.people.RelationshipStatus
import app.parley.common.people.VariantChip
import app.parley.data.people.OriginalPhotos
import app.parley.data.people.RelationMirrors
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.common.Intents
import app.parley.ui.menus.ReasonTarget
import app.parley.ui.shared

/**
 * The page's header: photo, name, the line of facts under it ("Married to Sam"), the at-a-glance line, the variant
 * chips and the labelled Call / Message / Video / Email tiles. It shrinks and fades by [collapse] (0 to 1) as it
 * scrolls under the top bar, where the small avatar and name appear.
 */
@Composable
internal fun ContactHeader(
    ctx: ContactPageContext,
    original: OriginalPhotos.Original?,
    heroSize: Dp,
    glance: String,
    locked: Boolean,
    onUnlock: () -> Unit,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
) {
    val d = ctx.d
    val contactId = ctx.contactId
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(top = Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
        // Shrinks towards the bar and fades as it scrolls under it, where the small avatar and name appear.
        Column(
            Modifier.graphicsLayer {
                val f = collapse()
                val s = 1f - 0.45f * f
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
                alpha = 1f - f
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroPhoto(
                ctx.vm, d.displayName, d.photoUri, original, heroSize, Modifier.shared("avatar-$contactId"),
                isCompany = d.composedName.isBlank() && d.company.isNotBlank(),
            ) { ctx.show(ContactDialog.Photo) }
            // Press and hold the name to copy it (the name only, not the lines under it).
            HeaderName(d.displayName, Modifier.padding(top = Spacing.m).shared("name-$contactId", bounds = true))
            // Their name in their own language and script, right under it; it copies itself like the name.
            if (!d.nativeName.isBlank) NativeNameLine(d.nativeName)
        }
        HeaderFactsLine(ctx)
        Text(
            glance, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xxs),
        )
        // Private and temporary show as small chips; nothing else on the page looks different.
        VariantChips(ctx.ui.variants, onClick = { chip ->
            when (chip) {
                VariantChip.Private -> if (locked) onUnlock() else ctx.show(ContactDialog.ConfirmMakeVisible)
                is VariantChip.Temporary -> ctx.show(ContactDialog.Expiry)
            }
        })
        // Their labels, right under the name, with "Add to label" (private ones too, also while locked).
        ContactLabelChips(ctx)
        Spacer(Modifier.height(Spacing.m))
        ActionTiles(ctx)
    }
}

/** Pronouns first, right under the name, then nickname and work; each part copies itself when tapped. */
@Composable
private fun HeaderFactsLine(ctx: ContactPageContext) {
    val d = ctx.d
    val resources = LocalResources.current
    val work = listOf(d.title, d.department, d.company).filter { it.isNotBlank() }.joinToString(", ")
    // "Married to Sam" / "Partner of Alex" from the relations (a tap opens them; a former spouse only shows on the
    // relation's own row).
    val status = remember(d.relations, ctx.parleyRelations, ctx.relationsFromOthers) {
        val own = (d.relations + ctx.parleyRelations).map { rel -> RelationMirrors.rowOf(rel) to { ctx.openRelation(rel.value) } }
        val others = ctx.relationsFromOthers.map { o -> o.row to { ctx.open(Routes.contact(o.navId)) } }
        RelationshipStatus.header(own + others) { it.first }.map { (kind, item) ->
            val res = if (kind == RelationshipStatus.Kind.MARRIED) R.string.detail_married_to else R.string.detail_partner_of
            HeaderLink(resources.getString(res, item.first.name.trim()), item.second)
        }
    }
    HeaderFacts(listOf(d.pronouns.trim(), d.nickname.trim(), work), stringResource(R.string.main_separator), status)
}

/** The labelled tiles: Call, Message, Video (when an app offers it) and Email. */
@Composable
private fun ActionTiles(ctx: ContactPageContext) {
    val d = ctx.d
    val context = LocalContext.current
    val primary = ctx.primary
    val preferredCall = ctx.preferredCall
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        // Press and hold Call (through the phone) for "Call with a reason…" (I12).
        val reasonNumber = primary?.value?.takeIf { preferredCall == null && CallReason.offered(it) }
        ActionTile(
            Icons.Rounded.Call, preferredCall?.appName ?: stringResource(R.string.main_call), ctx.canCall,
            onLongClick = reasonNumber?.let { n -> { ctx.show(ContactDialog.CallReason(ReasonTarget(n, d.displayName))) } },
            longClickLabel = stringResource(R.string.reason_call_with),
        ) { ctx.call() }
        val prefs = ctx.prefs
        val messageApp = prefs.message?.let { p ->
            if (p == MessengerPrefs.SMS) {
                stringResource(R.string.detail_sms)
            } else {
                ctx.ui.messengers.firstOrNull { it.accountType == p }?.appName ?: MessengerApp.forPackage(p)?.label
            }
        }
        ActionTile(
            Icons.AutoMirrored.Rounded.Message, messageApp ?: stringResource(R.string.main_message), ctx.canMessage,
            onLongClick = { ctx.show(ContactDialog.MessageOn(primary?.value.orEmpty())) }, longClickLabel = stringResource(R.string.detail_choose_message),
        ) { ctx.message() }
        if (ctx.reach.videoRows.isNotEmpty()) {
            ActionTile(
                Icons.Rounded.Videocam, ctx.preferredVideo?.appName ?: stringResource(R.string.detail_video), true,
                onLongClick = { ctx.show(ContactDialog.MessageOn(primary?.value.orEmpty())) }, longClickLabel = stringResource(R.string.detail_choose_video),
            ) { ctx.video() }
        }
        val email = ctx.email
        ActionTile(Icons.Rounded.Email, stringResource(R.string.detail_email), email != null) {
            email?.let { Intents.email(context, it.value) }
        }
    }
}

/**
 * The contact page's header photo: 128 dp for a real photo and 96 dp for a monogram on a phone held upright
 * (photo, name, the at-a-glance line and the tiles then take about a third of the screen), 160 / 120 dp on
 * tablets, and 88 / 72 dp when the screen is short (a phone in landscape), so the name and the action tiles still
 * fit under it. The sizes are in dp, so large fonts don't crowd the photo.
 */
@Composable
internal fun heroPhotoSize(hasPhoto: Boolean): Dp {
    val conf = LocalConfiguration.current
    return when {
        conf.screenHeightDp < 480 -> if (hasPhoto) 88.dp else 72.dp
        conf.screenWidthDp >= 600 && conf.screenHeightDp >= 700 -> if (hasPhoto) 160.dp else 120.dp
        else -> if (hasPhoto) 128.dp else 96.dp
    }
}
