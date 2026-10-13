package app.parley.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** The keyboard request that keeps what is typed out of the keyboard's memory. */
object KeyboardPrivacy {
    /**
     * [imeOptions] with IME_FLAG_NO_PERSONALIZED_LEARNING: the keyboard app must not add what is typed to its
     * dictionary, its suggestions in other apps or its cloud sync (Gboard and the keyboards built on Android's honour it).
     */
    fun noLearning(imeOptions: Int): Int = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}

/**
 * Text fields in [content] ask the keyboard not to learn what is typed ([KeyboardPrivacy.noLearning]) while [enabled]:
 * a safe word, or a private contact's name, number or note, must never come back as a suggestion in another app.
 * Compose has no keyboard option for it, so the request is wrapped. Dialogs opened inside [content] are covered too.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(enabled: Boolean = true, content: @Composable () -> Unit) {
    // Always the same tree, read when a field starts typing: switching [enabled] never rebuilds (and resets) [content].
    val on by rememberUpdatedState(enabled)
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            if (!on) {
                next.startInputMethod(request)
            } else {
                next.startInputMethod(
                    PlatformTextInputMethodRequest { info: EditorInfo ->
                        request.createInputConnection(info).also { info.imeOptions = KeyboardPrivacy.noLearning(info.imeOptions) }
                    },
                )
            }
        },
        content = content,
    )
}
