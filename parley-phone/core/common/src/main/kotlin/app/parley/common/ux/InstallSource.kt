package app.parley.common.ux

/**
 * U1: whether Parley was sideloaded. Android 13+ blocks "restricted settings" (the default phone app role among
 * them) for apps installed from a file rather than an app store, and the role request then fails without saying
 * why. Onboarding explains this first when [needsRestrictedSettingsHelp].
 */
object InstallSource {
    /** App stores that install through a session installer, which Android doesn't restrict. */
    val STORES = setOf(
        "com.android.vending", // Google Play
        "org.fdroid.fdroid", "org.fdroid.basic", // F-Droid
        "com.looker.droidify", "com.machiav3lli.fdroid", // Droid-ify, Neo Store (F-Droid clients)
        "com.aurora.store", "dev.imranr.obtainium", "app.accrescent.client",
    )

    /** No installer, an unknown one, or the system package installer (an APK opened from a file): sideloaded. */
    fun isSideloaded(installer: String?): Boolean = installer.isNullOrBlank() || installer !in STORES

    /** Restricted settings exist from Android 13 (API 33). */
    fun needsRestrictedSettingsHelp(installer: String?, sdk: Int): Boolean = sdk >= 33 && isSideloaded(installer)
}
