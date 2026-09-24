package app.parley.telecom

import android.content.Context
import android.content.Intent
import app.parley.common.AnswerGesture
import app.parley.common.Decision
import app.parley.common.ListDensity
import app.parley.common.ThemeMode
import app.parley.common.Verification
import kotlinx.coroutines.flow.StateFlow

data class CallerDisplay(
    val name: String,
    val photoUri: String?,
    val label: String?,
    val contactId: Long?,
    val lookupKey: String?,
)

data class InCallAppearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = true,
    val density: ListDensity = ListDensity.COMFORTABLE,
    val answerGesture: AnswerGesture = AnswerGesture.SWIPE,
    val quickReplies: List<String> = emptyList(),
)

/**
 * What the call path needs from the rest of the app. Implemented by the app module so that this
 * module never depends on data or feature code.
 */
interface TelecomDependencies {
    val appearance: StateFlow<InCallAppearance>
    suspend fun callerInfo(number: String): CallerDisplay?
    fun screeningActive(): Boolean
    suspend fun screen(number: String?, hidden: Boolean, verification: Verification): Decision
    suspend fun preferredAccountId(number: String): String?
    /** Intent for the main app: [dialpad] opens the keypad (used by "Add call"). */
    fun mainIntent(context: Context, dialpad: Boolean): Intent
    fun contactIntent(context: Context, contactId: Long?, number: String?): Intent
}

object TelecomGraph {
    @Volatile
    private var deps: TelecomDependencies? = null

    fun install(d: TelecomDependencies) {
        deps = d
    }

    val dependencies: TelecomDependencies
        get() = deps ?: error("TelecomGraph not installed")
}
