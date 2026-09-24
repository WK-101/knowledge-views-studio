package app.parley.blocking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import app.parley.common.spam.PackOrigin
import app.parley.data.SpamListStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads packs from the optional "Parley Lists" companion app (B4c, module :lists-updater). That app has
 * INTERNET and no contacts or phone permissions; Parley has the reverse. Packs cross over through its
 * ContentProvider, guarded by a signature permission, and are verified here exactly like a file the user
 * picked (checksums, Ed25519 signature, same-key updates) before [SpamListStore] installs them.
 */
object ListsUpdaterClient {
    private const val PREFS = "lists_updater"
    private const val SUBS = "subscriptions"

    /** Where users get the companion (no network call from Parley: this is only shown as text). */
    const val WHERE_TO_GET = "F-Droid: search for \"Parley Lists\" (app.parley.lists), from the same developer as Parley"

    private fun debug(ctx: Context) = ctx.packageName.endsWith(".debug")

    fun packageName(ctx: Context) = if (debug(ctx)) "app.parley.lists.debug" else "app.parley.lists"

    fun permission(ctx: Context) = if (debug(ctx)) "app.parley.permission.READ_LISTS_DEBUG" else "app.parley.permission.READ_LISTS"

    private fun authority(ctx: Context) = packageName(ctx) + ".packs"

    private fun listUri(ctx: Context): Uri = "content://${authority(ctx)}/packs".toUri()

    fun packUri(ctx: Context, id: String): Uri = listUri(ctx).buildUpon().appendPath(id).build()

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(packageName(ctx), 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** False when the companion is signed with a different key (the signature permission isn't granted). */
    fun canRead(ctx: Context): Boolean = ctx.checkSelfPermission(permission(ctx)) == PackageManager.PERMISSION_GRANTED

    fun launchIntent(ctx: Context): Intent? = ctx.packageManager.getLaunchIntentForPackage(packageName(ctx))

    data class RemotePack(
        val id: String,
        val name: String,
        val version: Long,
        val entries: Int,
        val ranges: Int,
        val size: Long,
        val updated: Long,
        val fingerprint: String?,
        val source: String,
        val licence: String,
    )

    /** The packs the companion offers, or null when it can't be read. */
    suspend fun available(ctx: Context): List<RemotePack>? = withContext(Dispatchers.IO) {
        if (!isInstalled(ctx) || !canRead(ctx)) return@withContext null
        try {
            ctx.contentResolver.query(listUri(ctx), null, null, null, null)?.use { c ->
                fun s(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }?.let { c.getString(it) }
                fun l(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                val out = ArrayList<RemotePack>()
                while (c.moveToNext()) {
                    val id = s("id") ?: continue
                    out += RemotePack(id, s("name") ?: id, l("version"), l("entries").toInt(), l("ranges").toInt(), l("size"), l("updated"), s("fingerprint"), s("source").orEmpty(), s("licence").orEmpty())
                }
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun subscriptions(ctx: Context): Set<String> = prefs(ctx).getStringSet(SUBS, emptySet()).orEmpty().toSet()

    fun lastError(ctx: Context, id: String): String? = prefs(ctx).getString("error.$id", null)

    private fun setError(ctx: Context, id: String, error: String?) = prefs(ctx).edit { if (error == null) remove("error.$id") else putString("error.$id", error) }

    private fun setSubscribed(ctx: Context, id: String, on: Boolean) {
        val s = subscriptions(ctx).toMutableSet()
        if (on) s += id else s -= id
        prefs(ctx).edit { putStringSet(SUBS, s) }
    }

    /** Copies one pack through the provider, verifies it and installs it. */
    suspend fun copy(ctx: Context, lists: SpamListStore, id: String, force: Boolean = false): SpamListStore.InstallResult {
        val result = try {
            val parsed = lists.parse(packUri(ctx, id))
            if (parsed.manifest.id != id) {
                SpamListStore.InstallResult.Failed("The list's id doesn't match what the updater announced")
            } else {
                lists.install(parsed, PackOrigin.UPDATER, force)
            }
        } catch (e: SecurityException) {
            SpamListStore.InstallResult.Failed("Parley isn't allowed to read the updater (it's signed by a different developer)")
        } catch (e: Exception) {
            SpamListStore.InstallResult.Failed(e.message ?: "Couldn't read the list")
        }
        setError(ctx, id, (result as? SpamListStore.InstallResult.Failed)?.reason)
        return result
    }

    suspend fun subscribe(ctx: Context, lists: SpamListStore, id: String): SpamListStore.InstallResult {
        setSubscribed(ctx, id, true)
        return copy(ctx, lists, id)
    }

    /** Stops updates; the installed copy is removed too, so nothing stale lingers. */
    suspend fun unsubscribe(ctx: Context, lists: SpamListStore, id: String) {
        setSubscribed(ctx, id, false)
        setError(ctx, id, null)
        if (lists.state.value.packs.any { it.id == id && it.origin == PackOrigin.UPDATER }) lists.remove(id)
    }

    /** Daily (from [SpamListWorker]): copies subscribed packs the updater has a newer version of. */
    suspend fun refresh(ctx: Context, lists: SpamListStore): Int {
        val subs = subscriptions(ctx)
        if (subs.isEmpty()) return 0
        val remote = available(ctx) ?: return 0
        var updated = 0
        for (r in remote) {
            if (r.id !in subs) continue
            val installed = lists.state.value.packs.firstOrNull { it.id == r.id }
            if (installed != null && installed.origin == PackOrigin.UPDATER && installed.version >= r.version) continue
            if (copy(ctx, lists, r.id) is SpamListStore.InstallResult.Installed) updated++
        }
        return updated
    }
}
