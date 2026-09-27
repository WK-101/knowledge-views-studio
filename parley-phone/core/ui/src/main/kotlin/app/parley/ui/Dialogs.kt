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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
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
        confirmButton = {
            if (SensitiveDialogs.active) IgnoreObscuredTouches()
            confirmButton()
        },
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
 * Held while a sensitive screen shows (unlocking, restoring a backup, deleting everything, taking back the phone
 * app): every dialog opened meanwhile ignores touches that pass through another app's overlay. A dialog is a window of
 * its own, so the guard on the activity's window doesn't reach it, and before Android 12 nothing else hides overlays.
 */
object SensitiveDialogs {
    private val holds = mutableIntStateOf(0)

    val active: Boolean get() = holds.intValue > 0

    fun acquire() {
        holds.intValue++
    }

    fun release() {
        holds.intValue = maxOf(0, holds.intValue - 1)
    }
}

/** Makes the window this is composed in drop touches made through an overlay, while it is shown. */
@Composable
private fun IgnoreObscuredTouches() {
    val view = LocalView.current
    DisposableEffect(view) {
        val root = view.rootView
        val before = root.filterTouchesWhenObscured
        root.filterTouchesWhenObscured = true
        onDispose { root.filterTouchesWhenObscured = before }
    }
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
