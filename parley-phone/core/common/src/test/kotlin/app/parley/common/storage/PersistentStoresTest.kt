package app.parley.common.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every store the code uses is registered with a backup policy, so a new preferences file, DataStore, table or files
 * entry can't silently miss the backup or "Delete all Parley data".
 */
class PersistentStoresTest {
    /** parley-phone/, from this module's folder (Gradle runs tests in core/common). */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "core").isDirectory }

    /** Parley's own sources (the companion app is a separate app with its own storage). */
    private val sources: List<Pair<File, String>> by lazy {
        listOf("app", "core", "telecom").map { File(root, it) }.flatMap { dir ->
            dir.walkTopDown().filter { f ->
                f.isFile && f.extension == "kt" && "/build/" !in f.path && "/src/test/" !in f.path && "/src/androidTest/" !in f.path
            }.toList()
        }.map { it to it.readText() }
    }

    private fun names(regex: Regex, filter: (String) -> Boolean = { true }): Map<String, File> =
        sources.filter { (_, text) -> filter(text) }.flatMap { (f, text) -> regex.findAll(text).map { it.groupValues[1] to f }.toList() }.toMap()

    @Test fun every_store_has_a_policy_and_a_section_when_backed_up() {
        val ids = HashSet<String>()
        for (s in PersistentStores.all) {
            assertTrue("duplicate ${s.id}", ids.add(s.id))
            when (val p = s.policy) {
                StorePolicy.BackedUp, StorePolicy.BackedUpWithVault -> assertNotNull("${s.id} has no backup section", s.section)
                is StorePolicy.DeviceLocal -> assertTrue("${s.id} needs a reason", p.reason.isNotBlank())
                is StorePolicy.Secret -> assertTrue("${s.id} needs a reason", p.reason.isNotBlank())
            }
            if (s.kind == StoreKind.ROOM_TABLE || s.kind == StoreKind.FILES) assertNotNull("${s.id} needs a location", s.location)
        }
        assertTrue(PersistentStores.Sections.CONTACT_NOTES in PersistentStores.requiredSections)
        assertTrue(PersistentStores.Sections.CALL_TIME in PersistentStores.requiredSections)
    }

    @Test fun every_shared_preferences_file_is_registered() {
        val literal = names(Regex("""getSharedPreferences\(\s*"([^"]+)""""))
        // Files named by a constant: PREFS / FILE in a file that opens preferences.
        val constants = names(Regex("""(?:const\s+)?val\s+(?:PREFS|FILE)\s*=\s*"([^"]+)"""")) { "getSharedPreferences(" in it }
        val used = literal + constants
        assertTrue("the scan found nothing: wrong folder?", used.size >= 20)
        val registered = PersistentStores.of(StoreKind.PREFS).map { it.name }.toSet()
        val missing = used.keys - registered
        assertTrue("SharedPreferences not in PersistentStores: ${missing.associateWith { used[it]?.name }}", missing.isEmpty())
        val stale = registered - used.keys
        assertTrue("PersistentStores lists preferences no code uses: $stale", stale.isEmpty())
    }

    @Test fun every_datastore_is_registered() {
        val used = names(Regex("""preferencesDataStore\(\s*name\s*=\s*"([^"]+)""""))
        assertEquals(PersistentStores.of(StoreKind.DATASTORE).map { it.name }.toSet(), used.keys)
    }

    @Test fun every_database_is_registered() {
        val literal = names(Regex("""databaseBuilder\([^)]*"([^"]+)"\)"""))
        val constants = names(Regex("""const\s+val\s+NAME\s*=\s*"([^"]+)"""")) { "databaseBuilder(" in it }
        val used = (literal + constants).keys
        val registered = PersistentStores.of(StoreKind.ROOM_TABLE).mapNotNull { it.location }.toSet()
        assertEquals(registered, used)
    }

    @Test fun every_room_table_is_registered() {
        // The newest exported schema of each database lists its tables.
        val schemas = File(root, "core/data/schemas")
        val dbs = mapOf("app.parley.data.db.AppDatabase" to PersistentStores.MAIN_DB, "app.parley.data.history.HistoryDatabase" to PersistentStores.HISTORY_DB)
        for ((cls, db) in dbs) {
            val latest = File(schemas, cls).listFiles().orEmpty().filter { it.extension == "json" }.maxBy { it.nameWithoutExtension.toInt() }
            val tables = Regex(""""tableName"\s*:\s*"([^"]+)"""").findAll(latest.readText()).map { it.groupValues[1] }.toSet()
            assertEquals("tables of $db (${latest.name})", tables, PersistentStores.tables(db))
        }
    }

    @Test fun every_files_entry_is_registered() {
        val regex = Regex("""(noBackupFilesDir|createDeviceProtectedStorageContext\(\)\.filesDir|filesDir)\s*,\s*"([^"]+)"""")
        val used = sources.flatMap { (f, text) ->
            // Names built at run time ("datastore/${'$'}{name}…") are the registry's own stores being walked.
            regex.findAll(text).filter { '$' !in it.groupValues[2] }.map { m ->
                val where = when {
                    m.groupValues[1] == "noBackupFilesDir" -> PersistentStore.NO_BACKUP_FILES
                    m.groupValues[1].startsWith("create") -> PersistentStore.DEVICE_PROTECTED_FILES
                    else -> PersistentStore.FILES
                }
                (where to m.groupValues[2]) to f
            }.toList()
        }.toMap()
        val registered = PersistentStores.of(StoreKind.FILES).map { it.location to it.name }.toSet()
        val missing = used.keys - registered
        assertTrue("files not in PersistentStores: ${missing.associateWith { used[it]?.name }}", missing.isEmpty())
        val stale = registered - used.keys
        assertTrue("PersistentStores lists files no code uses: $stale", stale.isEmpty())
    }
}
