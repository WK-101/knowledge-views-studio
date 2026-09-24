package app.parley.data

import android.content.Context
import android.os.UserManager

/**
 * I9: whether this user has a work profile (or any other managed profile), so caller lookup can ask the Contacts
 * Provider's enterprise lookup. `getUserProfiles()` needs no permission. Checked at most once a minute: profiles
 * rarely change, and incoming calls must not wait.
 */
object WorkProfile {
    @Volatile private var cached: Boolean? = null
    @Volatile private var checkedAt = 0L

    fun exists(context: Context): Boolean {
        val now = System.currentTimeMillis()
        cached?.let { if (now - checkedAt < 60_000L) return it }
        val has = try {
            (context.getSystemService(UserManager::class.java)?.userProfiles?.size ?: 1) > 1
        } catch (_: Exception) {
            false
        }
        cached = has
        checkedAt = now
        return has
    }
}
