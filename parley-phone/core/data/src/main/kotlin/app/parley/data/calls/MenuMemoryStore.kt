package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuState
import app.parley.common.storage.PersistentStores
import app.parley.data.backup.BackupExtras
import app.parley.data.security.RecordSealing
import app.parley.data.security.SealedDocument
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Menu memory ([MenuMemory]): the keys sent in the last call to each number, the saved menu shortcuts and the
 * numbers it must not remember, in one small [SealedDocument] (`docs/menu_memory` in no-backup storage). Sealed with
 * the small-records key (it holds numbers, a private contact's among them), so the call path can write it while the
 * phone is locked.
 *
 * Like the To call list: a stored document that can't be opened right now is never taken for an empty one (changes are
 * refused until it can be read), and it is never stored as plain text (a change waits in memory until it can be sealed).
 * Remembered paths stay on this phone (only shortcuts and opt-outs go into the backup), and private contacts' numbers
 * stay out of it ([isPrivate]). Paths kept by an older, looser guard are cut to today's on load ([MenuMemory.sanitize]).
 */
class MenuMemoryStore internal constructor(
    context: Context,
    private val isPrivate: suspend (String) -> Boolean,
    /** Seals the encoded state; null when sealing isn't possible right now (tests replace it). */
    sealOverride: ((String) -> String?)?,
) : RecordSealing.Resealable {
    constructor(context: Context, isPrivate: suspend (String) -> Boolean) : this(context, isPrivate, null)

    private val appContext = context.applicationContext

    private val doc = SealedDocument(
        appContext, File(appContext.noBackupFilesDir, "docs/menu_memory"), SealedDocument.Whole(MenuMemory::encode, MenuMemory::decode),
        // Where earlier versions kept it: copied into the document once.
        legacy = SealedDocument.Legacy({ appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }, KEY, MenuMemory::decode),
        clean = MenuMemory::sanitize, sealOverride = sealOverride,
    )

    /** The state as stored; empty until [load] ran. */
    val state: StateFlow<MenuState> = doc.state

    /** Whether the stored state could be read. */
    val available: Boolean get() = doc.available

    suspend fun load(): MenuState = doc.load()

    /**
     * Applies [f] and writes the result; returns the state, or null when the stored state can't be read right now
     * (nothing is applied then, so nothing stored is overwritten).
     */
    suspend fun update(f: (MenuState) -> MenuState): MenuState? = doc.write(f).takeIf { doc.available }?.state

    override suspend fun resealPlain(): Boolean = doc.resealPlain()

    /** Inside the encrypted backup's settings section: shortcuts and opt-outs, never paths; private contacts' numbers stay out. */
    val backupExtras: BackupExtras = object : BackupExtras {
        override val section = "menu memory"
        override val sections = setOf(PersistentStores.Sections.MENUS)

        override suspend fun export(): Map<String, String> {
            val s = load()
            check(available) { "Menu memory can't be read right now" }
            val numbers = s.paths.values.map { it.number } + s.shortcuts.map { it.number }
            val leaveOut = numbers.filter { it.isNotEmpty() && runCatching { isPrivate(it) }.getOrDefault(true) }.toSet()
            return mapOf(X_STATE to MenuMemory.encode(MenuMemory.forBackup(s) { it in leaveOut }))
        }

        override suspend fun import(values: Map<String, String>) {
            // A backup made before paths stayed on the phone may hold some: they aren't restored.
            val restored = values[X_STATE]?.let { MenuMemory.decode(it) }?.copy(paths = emptyMap()) ?: return
            update { MenuMemory.merge(it, restored) }
        }
    }

    private companion object {
        const val FILE = "menu_memory"
        const val KEY = "state_v1"
        const val X_STATE = "${BackupExtras.PREFIX}menus.state"
    }
}
