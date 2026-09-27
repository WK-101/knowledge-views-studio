package app.parley.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Parley's bottom sheet (for choosers and several actions; see Dialogs.kt for when to use a dialog instead).
 * [title], when given, is drawn the same way on every sheet and announced as a heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParleySheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState, shape = ParleyShapes.sheet.topOnly()) {
        if (title != null) SheetTitle(title)
        content()
    }
}

/** The title line of a [ParleySheet]. */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
    )
}

enum class BannerTone { INFO, WARNING, ERROR }

/**
 * A notice at the top of a screen or list ("Parley isn't your phone app", "Backup overdue"): an icon, a text,
 * an optional action and an optional close button. Warnings and errors use the error container colours.
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: BannerTone = BannerTone.INFO,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = kitStrings().close,
    extra: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (tone) {
        BannerTone.INFO -> cs.secondaryContainer to cs.onSecondaryContainer
        BannerTone.WARNING, BannerTone.ERROR -> cs.errorContainer to cs.onErrorContainer
    }
    Surface(
        color = container, contentColor = content, shape = ParleyShapes.card,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.listInset, vertical = Spacing.xs),
    ) {
        Row(Modifier.padding(start = Spacing.l, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon ?: if (tone == BannerTone.INFO) Icons.Rounded.Info else Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = Spacing.s))
            extra?.invoke(this)
            if (action != null && onAction != null) TextButton(onAction) { Text(action) }
            if (onDismiss != null) IconButton(onDismiss) { Icon(Icons.Rounded.Close, dismissLabel) }
        }
    }
}
