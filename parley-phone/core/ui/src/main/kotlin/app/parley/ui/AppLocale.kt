package app.parley.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * Parley is English-only. Versions before 4.6 offered a per-app language (stored by Android 13+ itself, and in
 * [PREFS] on Android 10–12); [reset] drops such a choice once, so a phone that picked a language Parley no longer
 * ships doesn't keep that language's layout direction and formats around English text.
 */
object AppLocale {
    private const val PREFS = "parley_app_locale"

    fun reset(context: Context) {
        runCatching { context.deleteSharedPreferences(PREFS) }
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val manager = context.getSystemService(LocaleManager::class.java) ?: return@runCatching
                if (!manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.getEmptyLocaleList()
            }
        }
    }
}
