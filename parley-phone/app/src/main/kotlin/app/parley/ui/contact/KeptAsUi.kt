package app.parley.ui.contact

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PeopleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.common.people.KeptAs
import app.parley.common.people.KeptAsStep
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.people.archive.ArchiveActions
import kotlinx.coroutines.launch

/**
 * How this contact is kept now. A private contact archived inside the vault still has its page (from its calls); a
 * device contact archived has the read-only archived page instead, so it never reaches here.
 */
@Composable
internal fun keptAs(ctx: ContactPageContext): KeptAs {
    val privateList by ctx.vm.c.vault.contacts.collectAsStateWithLifecycle()
    val archived = ctx.isPrivate && privateList.firstOrNull { it.id == -ctx.contactId }?.archived == true
    return KeptAs.of(ctx.isPrivate, archived)
}

internal fun keptAsIcon(k: KeptAs): ImageVector = when (k) {
    KeptAs.VISIBLE -> Icons.Rounded.PeopleOutline
    KeptAs.PRIVATE -> Icons.Rounded.Lock
    KeptAs.ARCHIVED -> Icons.Rounded.Archive
}

internal fun keptAsName(res: Resources, k: KeptAs): String = res.getString(
    when (k) {
        KeptAs.VISIBLE -> R.string.kept_as_visible
        KeptAs.PRIVATE -> R.string.kept_as_private
        KeptAs.ARCHIVED -> R.string.kept_as_archived
    },
)

private fun keptAsSub(k: KeptAs): Int = when (k) {
    KeptAs.VISIBLE -> R.string.kept_as_visible_sub
    KeptAs.PRIVATE -> R.string.kept_as_private_sub
    KeptAs.ARCHIVED -> R.string.kept_as_archived_sub
}

/**
 * "Kept as": the three ways as radio rows, the current one picked. Choosing another asks first, with the same
 * question the menu used to ask (Make private, Make visible, Archive); an archived private contact goes back among
 * the private contacts with Unarchive, and is made visible from there.
 */
@Composable
internal fun KeptAsDialog(ctx: ContactPageContext) {
    val res = LocalResources.current
    val now = keptAs(ctx)
    val close = { ctx.show(ContactDialog.None) }
    fun pick(to: KeptAs) {
        when (now.stepTo(to)) {
            null -> close()
            KeptAsStep.MAKE_PRIVATE -> ctx.show(ContactDialog.ConfirmMakePrivate)
            KeptAsStep.MAKE_VISIBLE -> ctx.show(ContactDialog.ConfirmMakeVisible)
            KeptAsStep.ARCHIVE -> ctx.show(ContactDialog.ConfirmArchive)
            KeptAsStep.UNARCHIVE -> {
                close()
                val name = ctx.d.given.ifBlank { ctx.d.displayName }
                ctx.scope.launch {
                    val ok = ArchiveActions.unarchivePrivate(ctx.vm, -ctx.contactId)
                    ctx.vm.toast(res.getString(if (ok) R.string.archive_private_unarchived else R.string.archive_private_unarchive_failed, name))
                }
            }
        }
    }
    ParleyDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.kept_as_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                now.choices().forEach { (k, enabled) ->
                    ParleyListItem(
                        modifier = Modifier.selectable(k == now, enabled = enabled, role = Role.RadioButton) { pick(k) }
                            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)),
                        leadingContent = { RadioButton(k == now, onClick = null, enabled = enabled) },
                        headlineContent = { Text(keptAsName(res, k)) },
                        supportingContent = { Text(stringResource(if (enabled) keptAsSub(k) else R.string.kept_as_unarchive_first)) },
                        trailingContent = { Icon(keptAsIcon(k), null) },
                    )
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.main_cancel)) } },
    )
}

private const val DISABLED_ALPHA = 0.6f
