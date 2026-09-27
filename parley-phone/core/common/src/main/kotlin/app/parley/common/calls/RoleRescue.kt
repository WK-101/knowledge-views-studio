package app.parley.common.calls

/** P4: the default-phone-app role request that Android answered without asking the user. */
object RoleRescue {
    /** A refusal faster than this can't have come from a person pressing Cancel on a dialog. */
    const val SILENT_CANCEL_MS = 300L

    fun silentlyRefused(granted: Boolean, elapsedMs: Long): Boolean = !granted && elapsedMs in 0 until SILENT_CANCEL_MS

    enum class Variant { ANDROID_10_11, ANDROID_12, ANDROID_13_PLUS }

    /** Where "Default apps" lives, and whether "Allow restricted settings" exists (Android 13+ sideloads). */
    fun variant(sdk: Int): Variant = when {
        sdk >= 33 -> Variant.ANDROID_13_PLUS
        sdk >= 31 -> Variant.ANDROID_12
        else -> Variant.ANDROID_10_11
    }
}
