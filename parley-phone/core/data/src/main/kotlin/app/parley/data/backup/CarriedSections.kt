package app.parley.data.backup

import android.content.Context
import android.util.Log
import app.parley.common.calls.AssistedDialConfig
import app.parley.common.catching
import app.parley.common.calls.CallerTune
import app.parley.common.calls.DriveProfileConfig
import app.parley.common.calls.FamilySafetyState
import app.parley.common.situations.Situations
import app.parley.common.storage.PersistentStores.Sections
import app.parley.data.calls.DriveProfileRepository
import app.parley.data.calls.FamilySafetyStore
import app.parley.data.calls.RoamingRepository
import app.parley.data.situations.SituationsController
import java.io.File

/**
 * Family safety (safe words, helpers, expected-call windows), restored with the contacts: safe words follow labels and
 * helpers are people. Inside the backup's encryption only; this phone's own wins wherever both have something.
 */
class FamilySafetyBackup(private val store: () -> FamilySafetyStore, private val region: () -> String?) : BackupExtras {
    override val section = "family safety"
    override val sections = setOf(Sections.FAMILY_SAFETY)
    override val restoreWith = RestorePart.CONTACTS

    override suspend fun export(): Map<String, String> {
        val state = store().backupState()
        return if (state == FamilySafetyState()) emptyMap() else mapOf(K to FamilySafetyState.encode(state))
    }

    override suspend fun import(values: Map<String, String>) {
        val backup = values[K]?.let { catching { FamilySafetyState.decode(it) }.getOrNull() } ?: return
        check(store().restore(backup, region())) { "Family safety couldn't be stored" }
    }

    private companion object {
        const val K = "${BackupExtras.PREFIX}family.safety"
    }
}

/**
 * The drive profile's and assisted dialling's switches. The cars stay out (Bluetooth addresses of this phone's pairings:
 * a new phone pairs again and keeps its own), and so does the trip the local-SIM hint was last shown for.
 */
class CallSwitchesBackup(private val drive: () -> DriveProfileRepository, private val roaming: () -> RoamingRepository) : BackupExtras {
    override val section = "drive and abroad"
    override val sections = setOf(Sections.CALL_SWITCHES)

    override suspend fun export(): Map<String, String> = mapOf(
        K_DRIVE to DriveProfileConfig.encode(drive().config.value.copy(cars = emptyList())),
        K_ROAMING to AssistedDialConfig.encode(roaming().config.value),
    )

    override suspend fun import(values: Map<String, String>) {
        values[K_DRIVE]?.let { DriveProfileConfig.decode(it) }?.let { b -> drive().update { here -> b.copy(cars = here.cars) } }
        values[K_ROAMING]?.let { AssistedDialConfig.decode(it) }?.let { b -> roaming().update { b } }
    }

    private companion object {
        const val K_DRIVE = "${BackupExtras.PREFIX}calls.drive"
        const val K_ROAMING = "${BackupExtras.PREFIX}calls.roaming"
    }
}

/**
 * Situations: what each one sets and when it switches on, the built-ins as changed and the ones made. Which one is on
 * now, and what it would put back, stay on this phone (a moment, not a preference). On restore, one only in the backup
 * is added and this phone's own wins where both have one, unless this phone's is a built-in as it came.
 */
class SituationsBackup(private val situations: () -> SituationsController) : BackupExtras {
    override val section = "situations"
    override val sections = setOf(Sections.SITUATIONS)

    override suspend fun export(): Map<String, String> = mapOf(K to Situations.encodeList(situations().forBackup()))

    override suspend fun import(values: Map<String, String>) {
        val backup = Situations.decodeList(values[K]) ?: return
        situations().restore(backup)
    }

    private companion object {
        const val K = "${BackupExtras.PREFIX}situations"
    }
}

/**
 * Ringtones made from a name ([CallerTune]): small WAV files in `files/tunes`. Contacts and labels keep each tune's
 * URI, which is the same on the next phone (same app, same name), so the backup carries the audio files themselves as
 * optional archive files ([app.parley.common.backup.BackupArchiveWriter.writeFiles]); a restore puts back the ones
 * missing. Tunes no ringtone uses are cleared out at the next start, as usual.
 */
class CallerTuneFiles(context: Context) {
    private val dir = File(context.filesDir, "tunes")

    /** Every kept tune by file name (each one checked, within [MAX_TOTAL] in all). */
    fun forBackup(): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        var total = 0L
        dir.listFiles().orEmpty().filter { it.isFile && CallerTune.isTuneFile(it.name) }.sortedBy { it.name }.forEach { f ->
            val size = f.length()
            if (size !in 1..MAX_FILE || total + size > MAX_TOTAL) {
                Log.w(TAG, "A tune file was left out of the backup ($size bytes)")
                return@forEach
            }
            runCatching { f.readBytes() }.getOrNull()?.takeIf(::isWav)?.let { out[f.name] = it; total += it.size }
        }
        return out
    }

    /** Puts back [bytes] as tune [name] unless this phone has it; false for anything that isn't a tune. */
    fun restore(name: String, bytes: ByteArray): Boolean {
        if (!CallerTune.isTuneFile(name) || bytes.size > MAX_FILE || !isWav(bytes)) return false
        dir.mkdirs()
        val file = File(dir, name)
        if (file.exists()) return true
        val tmp = File(dir, "$name.tmp")
        return runCatching {
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }.getOrDefault(false).also { if (!it) tmp.delete() }
    }

    private fun isWav(b: ByteArray): Boolean =
        b.size > WAV_HEADER && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WAVE"

    companion object {
        /** The archive folder: `x-tunes/<file>`. */
        const val FOLDER = "tunes"
        private const val TAG = "CallerTuneFiles"
        private const val WAV_HEADER = 44

        /** A tune is about 200 KB; anything far larger isn't one of Parley's. */
        private const val MAX_FILE = 2L shl 20

        /** About 150 tunes: a backup never grows without bound because of them. */
        private const val MAX_TOTAL = 32L shl 20
    }
}
