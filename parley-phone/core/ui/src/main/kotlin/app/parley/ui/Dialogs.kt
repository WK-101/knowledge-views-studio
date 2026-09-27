package app.parley.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties

/*
 * Dialogs vs sheets: a yes/no question, a short text entry or a message is a dialog ([ConfirmDialog],
 * [InfoDialog], [ParleyDialog]); picking from a list or choosing between several actions is a sheet ([ParleySheet]).
 */

/**
 * Parley's dialog: Material's AlertDialog with the theme's dialog shape, for dialogs that hold more than a question
 * (a list, a text field, several choices). Buttons: the confirming action on the end, Cancel before it.
 */
@Composable
fun ParleyDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = ParleyShapes.sheet,
        properties = properties,
    )
}

/**
 * A question with one action and Cancel. A [destructive] action (delete, clear, erase) is drawn in the error
 * colour so it can't be mistaken for the safe choice. [onConfirm] runs the action (and closes the dialog, as the
 * caller decides); Cancel and tapping outside call [onDismiss].
 */
@Composable
fun ConfirmDialog(
    title: String?,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = kitStrings().cancel,
    icon: ImageVector? = null,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ParleyDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, null) } },
        title = title?.let { { Text(it) } },
        text = when {
            content != null -> {
                {
                    Column {
                        if (text != null) Text(text)
                        content()
                    }
                }
            }
            text != null -> {
                { Text(text) }
            }
            else -> null
        },
        confirmButton = {
            TextButton(
                onConfirm, enabled = confirmEnabled,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onDismiss) { Text(dismissLabel) } },
    )
}

/** A message with one button that closes it ("OK", "Got it"). */
@Composable
fun InfoDialog(title: String?, text: String, onDismiss: () -> Unit, closeLabel: String = kitStrings().close, icon: ImageVector? = null) {
    ParleyDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, null) } },
        title = title?.let { { Text(it) } },
        text = { Text(text) },
        confirmButton = { TextButton(onDismiss) { Text(closeLabel) } },
    )
}
