package app.parley

import android.content.Context
import android.content.Intent
import app.parley.common.Decision
import app.parley.common.Verification
import app.parley.data.DataContainer
import app.parley.telecom.CallerDisplay
import app.parley.telecom.InCallAppearance
import app.parley.telecom.TelecomDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class AppTelecomDependencies(private val app: Context, private val c: DataContainer) : TelecomDependencies {

    override val appearance: StateFlow<InCallAppearance> = c.settings.settings
        .map { s -> InCallAppearance(s.themeMode, s.amoledBlack, s.dynamicColor, s.density, s.answerGesture, s.quickReplies) }
        .stateIn(c.scope, SharingStarted.Eagerly, InCallAppearance())

    override suspend fun callerInfo(number: String): CallerDisplay? = withContext(Dispatchers.IO) {
        c.contacts.lookup(number)?.let { CallerDisplay(it.name, it.photoUri, it.numberLabel, it.contactId, it.lookupKey) }
    }

    override fun screeningActive(): Boolean = c.screener.isActive()

    override suspend fun screen(number: String?, hidden: Boolean, verification: Verification): Decision =
        withContext(Dispatchers.IO) { c.screener.screen(number, hidden, verification) }

    override suspend fun preferredAccountId(number: String): String? = withContext(Dispatchers.IO) { c.prefs.simFor(number) }

    override fun mainIntent(context: Context, dialpad: Boolean): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(if (dialpad) MainActivity.ACTION_ADD_CALL else Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun contactIntent(context: Context, contactId: Long?, number: String?): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_CALLER)
            .putExtra(MainActivity.EXTRA_CONTACT_ID, contactId ?: -1L)
            .putExtra(MainActivity.EXTRA_NUMBER, number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
