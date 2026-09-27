package app.parley.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
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

/** Material's Scaffold with the app's snackbar in it; use it for every screen. */
@Composable
fun ParleyScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = { ScreenSnackbarHost() },
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier, topBar = topBar, bottomBar = bottomBar, snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton, floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor, contentColor = contentColor, contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

/**
 * A settings screen: large title that collapses as you scroll (with the scroll-linked tint), grouped content
 * with [Spacing.groupGap] between groups, and room for the navigation bar at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    back: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ParleyScaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { ParleyTopBar(title, onBack = back, actions = { actions() }, scrollBehavior = scroll, large = true) },
    ) { p ->
        val dir = LocalLayoutDirection.current
        Column(
            Modifier.fillMaxSize()
                .padding(top = p.calculateTopPadding(), start = p.calculateStartPadding(dir), end = p.calculateEndPadding(dir))
                .verticalScroll(rememberScrollState())
                // Scrolls behind the navigation bar, and the last row can still scroll above it.
                .padding(bottom = p.calculateBottomPadding() + Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.groupGap),
        ) {
            Spacer(Modifier.height(0.dp))
            content()
        }
    }
}
