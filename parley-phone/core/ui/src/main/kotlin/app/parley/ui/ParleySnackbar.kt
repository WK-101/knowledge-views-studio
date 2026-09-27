package app.parley.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import java.util.WeakHashMap
import kotlinx.coroutines.launch

/**
 * The app's one snackbar. The screen on show draws it inside its own Scaffold ([ScreenSnackbarHost], which every
 * [ParleyScaffold] has), so it sits above that screen's navigation bar and floating button, and the button moves
 * up for it. Messages outlive the composable that sent them ([scope] belongs to the app's root).
 */
@Stable
class ParleySnackbar(val state: SnackbarHostState, private val scope: CoroutineScope) {
    /** How many screens show the snackbar right now; with none, the root shows it itself. */
    var hosts by mutableIntStateOf(0)
        internal set

    fun show(text: String) {
        scope.launch { state.showSnackbar(text) }
    }

    /** A message with an action (Undo…); [onAction] runs when it is tapped. */
    fun show(text: String, action: String, onAction: () -> Unit) {
        scope.launch {
            if (state.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

val LocalSnackbar = staticCompositionLocalOf<ParleySnackbar?> { null }

/** Draws the app's snackbar in this screen (put it in the Scaffold's snackbarHost slot). */
@Composable
fun ScreenSnackbarHost(modifier: Modifier = Modifier) {
    val snackbar = LocalSnackbar.current ?: return
    DisposableEffect(snackbar) {
        snackbar.hosts++
        onDispose { snackbar.hosts-- }
    }
    SnackbarHost(snackbar.state, modifier)
}

/**
 * Creates the app's snackbar for this window, provides it to the screens ([LocalSnackbar]) and lets code without
 * a composition reach it ([showMessage]).
 */
@Composable
fun ProvideSnackbar(content: @Composable (ParleySnackbar) -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { ParleySnackbar(SnackbarHostState(), scope) }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, snackbar) {
        if (activity != null) synchronized(windows) { windows[activity] = snackbar }
        onDispose { if (activity != null) synchronized(windows) { windows.remove(activity) } }
    }
    CompositionLocalProvider(LocalSnackbar provides snackbar) { content(snackbar) }
}

private val windows = WeakHashMap<Activity, ParleySnackbar>()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * A short message for the user from code that has a [context] but no composition (a copy, an app that isn't
 * there): the snackbar of the Parley window [context] belongs to, or a system toast when that window has none
 * (the sheets shown over other apps, the call screen, a service).
 */
fun showMessage(context: Context, text: CharSequence, long: Boolean = false) {
    val snackbar = context.findActivity()?.let { synchronized(windows) { windows[it] } }
    if (snackbar != null) snackbar.show(text.toString()) else systemMessage(context, text, long)
}

/** [showMessage] for composables. */
@Composable
fun rememberShowMessage(): (String) -> Unit {
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    return remember(snackbar, context) { { text -> if (snackbar != null) snackbar.show(text) else showMessage(context, text) } }
}

/**
 * A system toast, for surfaces where no Parley screen is showing (a service, an activity finishing, a sheet over
 * another app). Inside Parley's own screens use [showMessage] or [rememberShowMessage].
 */
fun systemMessage(context: Context, text: CharSequence, long: Boolean = false) {
    Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
