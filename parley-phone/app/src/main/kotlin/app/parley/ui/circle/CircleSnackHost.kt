package app.parley.ui.circle

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.circle.InteractionType
import app.parley.common.circle.Interactions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** A Circle message with an optional Undo (R2 delete, removing someone from the Circle). */
class CircleSnack(val text: String, val undo: (suspend () -> Unit)? = null)

/** Circle messages for the app's snackbar ([CircleSnackHost]). */
object CircleSnacks {
    val events = MutableSharedFlow<CircleSnack>(extraBufferCapacity = 8)

    fun show(s: CircleSnack) {
        events.tryEmit(s)
    }
}

/**
 * R3 and R2's Undo on the app's snackbar. "Log this?" is shown only once Parley is back in front after the launch
 * (the user was in the chat app meanwhile), and not after an hour.
 */
@Composable
fun CircleSnackHost(vm: AppViewModel, snackbar: SnackbarHostState) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumedAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumedAt = System.currentTimeMillis() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        CircleSnacks.events.collect { s ->
            scope.launch {
                val undo = s.undo
                val r = snackbar.showSnackbar(s.text, actionLabel = if (undo != null) res.getString(R.string.dc_undo) else null, duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed && undo != null) undo()
            }
        }
    }
    val prompt by vm.c.circle.prompt.collectAsStateWithLifecycle()
    LaunchedEffect(prompt, resumedAt) {
        val p = prompt ?: return@LaunchedEffect
        // Still the launch itself: wait until the user comes back from the other app.
        if (resumedAt <= p.time) return@LaunchedEffect
        if (System.currentTimeMillis() - p.time > Interactions.PROMPT_TTL_MS) {
            vm.c.circle.clearPrompt(p)
            return@LaunchedEffect
        }
        val video = p.channel.type == InteractionType.VIDEO
        if (p.autoLogged) {
            val text = res.getString(if (video) R.string.circle_logged_video else R.string.circle_logged_message, p.name)
            val r = snackbar.showSnackbar(text, actionLabel = res.getString(R.string.dc_undo), duration = SnackbarDuration.Long)
            vm.c.circle.clearPrompt(p)
            val id = p.loggedId
            if (r == SnackbarResult.ActionPerformed && id != null) vm.c.circle.interactions.delete(id)
        } else {
            val text = res.getString(if (video) R.string.circle_log_question_video else R.string.circle_log_question_message, p.name)
            val r = snackbar.showSnackbar(text, actionLabel = res.getString(R.string.circle_log), withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                // Undo only for an entry this tap added (a second "Log" in the same 10 minutes adds none), and only it.
                val id = vm.c.circle.accept(p)
                CircleSnacks.show(CircleSnack(res.getString(R.string.circle_logged, p.name), if (id != null) ({ vm.c.circle.interactions.delete(id) }) else null))
            } else {
                vm.c.circle.clearPrompt(p)
            }
        }
    }
}
