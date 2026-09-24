package app.parley.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language (L1). Android 13+ stores it itself ([LocaleManager], the system "App languages" screen). On
 * Android 10–12 Parley stores the choice and applies it as a configuration override: every activity calls
 * [override] from `attachBaseContext`, and the application context is wrapped by [wrap], so notifications and
 * toasts follow it too. No AppCompat needed.
 */
object AppLocale {
    /** Language tags Parley is translated into; keep in sync with the values-* folders. */
    val supported = listOf("en", "ar", "de", "es", "fr", "hi", "pt-BR", "ur")

    private const val PREFS = "parley_app_locale"
    private const val KEY_TAG = "tag"

    private val native: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** The chosen language, or null when Parley follows the system language. */
    fun current(context: Context): Locale? =
        if (native) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)
        } else {
            stored(context)?.let(Locale::forLanguageTag)
        }

    /**
     * Sets the app language ([tag] null: follow the system). On Android 13+ the system recreates the activities; on
     * Android 10–12 [activity] is recreated here and the application context is updated in place.
     */
    fun set(activity: Activity, tag: String?) {
        if (native) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag)
        }.commit()
        val locale = tag?.let(Locale::forLanguageTag) ?: systemLocale()
        Locale.setDefault(locale)
        // The application context was wrapped at start: bring its resources to the new language too.
        val app = activity.applicationContext.resources
        val config = Configuration(app.configuration).apply { setLocales(LocaleList(locale)) }
        @Suppress("DEPRECATION")
        app.updateConfiguration(config, app.displayMetrics)
        activity.recreate()
    }

    /** For `Application.attachBaseContext` (Android 10–12 only; unchanged otherwise). */
    fun wrap(base: Context): Context {
        val config = overrideConfig(base) ?: return base
        return base.createConfigurationContext(config)
    }

    /**
     * The configuration override for an activity's `attachBaseContext` (`applyOverrideConfiguration`), or null when
     * the system language applies. Only the locale (and with it the layout direction) is overridden.
     */
    fun overrideConfig(base: Context): Configuration? {
        if (native) return null
        val tag = stored(base) ?: return null
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return Configuration().apply { setLocales(LocaleList(locale)) }
    }

    /** Applies [overrideConfig] to an activity; call right after `super.attachBaseContext(base)`. */
    fun override(activity: android.view.ContextThemeWrapper, base: Context) {
        overrideConfig(base)?.let(activity::applyOverrideConfiguration)
    }

    private fun stored(context: Context): String? =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null) }.getOrNull()

    private fun systemLocale(): Locale = android.content.res.Resources.getSystem().configuration.locales[0]
}
