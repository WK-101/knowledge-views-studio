package app.parley.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.storage.PersistentStores
import app.parley.common.storage.StoreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Bookkeeping stores share two preferences files: each keeps its own keys (two stores with the same key never meet),
 * and what it kept in a file of its own is copied in once, crash-safe, the old file then gone.
 */
@RunWith(RobolectricTestRunner::class)
class StatePrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun old(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun oldFile(name: String) = File(context.dataDir, "shared_prefs/$name.xml")
    private val local get() = context.getSharedPreferences("local_state", Context.MODE_PRIVATE)

    @Test fun old_files_are_copied_once_with_every_type_and_then_deleted() {
        old("ux").edit().putBoolean("seen_tip", true).putString("last", "a").putInt("count", 3).putLong("at", 7L)
            .putFloat("f", 1.5f).putStringSet("set", setOf("x", "y")).commit()
        assertTrue(oldFile("ux").exists())

        val ux = StatePrefs.open(context, "ux")
        assertTrue(ux.getBoolean("seen_tip", false))
        assertEquals("a", ux.getString("last", null))
        assertEquals(3, ux.getInt("count", 0))
        assertEquals(7L, ux.getLong("at", 0))
        assertEquals(1.5f, ux.getFloat("f", 0f))
        assertEquals(setOf("x", "y"), ux.getStringSet("set", null))
        assertEquals(setOf("seen_tip", "last", "count", "at", "f", "set"), ux.all.keys)
        assertFalse(oldFile("ux").exists())
        // Stored under the store's name in the shared file.
        assertEquals("a", local.getString("ux.last", null))
    }

    @Test fun a_copy_already_made_is_not_made_again_after_a_crash_before_the_old_file_went() {
        old("diagnostics").edit().putString("k", "first").commit()
        StatePrefs.open(context, "diagnostics").edit().putString("k", "changed since").commit()
        // A crash after the shared file was written, before the old one was deleted: it is still there.
        old("diagnostics").edit().putString("k", "first").commit()
        assertEquals("changed since", StatePrefs.open(context, "diagnostics").getString("k", null))
        assertFalse(oldFile("diagnostics").exists())
    }

    @Test fun stores_with_the_same_keys_never_meet_and_clear_only_their_own() {
        val a = StatePrefs.open(context, "dial_widgets")
        val b = StatePrefs.open(context, "favorites_widgets")
        a.edit().putString("w1", "dial").commit()
        b.edit().putString("w1", "favourites").commit()
        assertEquals("dial", a.getString("w1", null))
        assertEquals("favourites", b.getString("w1", null))
        a.edit().clear().commit()
        assertNull(a.getString("w1", null))
        assertTrue(a.all.isEmpty())
        assertEquals("favourites", b.getString("w1", null))
    }

    @Test fun the_ring_path_file_is_filled_before_a_call_reads_it_directly() {
        old("parley_screening_guard").edit().putLong("last_emergency_wall_ms", 42L).commit()
        old("parley_ring_boost").edit().putInt("saved_ring_volume", 3).commit()
        StatePrefs.moveAll(context, StatePrefs.CALL_PATH)
        val shared = context.getSharedPreferences("call_path_state", Context.MODE_PRIVATE)
        // The keys the telecom module reads without StatePrefs.
        assertEquals(42L, shared.getLong("parley_screening_guard.last_emergency_wall_ms", 0))
        assertEquals(3, shared.getInt("parley_ring_boost.saved_ring_volume", 0))
        assertFalse(oldFile("parley_screening_guard").exists())
    }

    @Test fun every_store_of_a_shared_file_opens() {
        val grouped = PersistentStores.of(StoreKind.PREFS).filter { it.location != null }
        assertEquals(22, grouped.size)
        grouped.forEach { StatePrefs.open(context, it.name).edit().putBoolean("x", true).commit() }
        assertEquals(grouped.count { it.location == StatePrefs.LOCAL }, local.all.keys.count { it.endsWith(".x") })
    }
}
