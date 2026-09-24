package app.parley.lists

import android.content.Context
import app.parley.common.spam.BuiltInPacks
import app.parley.common.spam.CsvPackConverter
import app.parley.common.spam.FtcDncSource
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackBuilder
import app.parley.common.spam.PackException
import app.parley.common.spam.PackManifest
import app.parley.common.spam.ReportTally
import app.parley.common.spam.SignatureStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * One update run: fetches every enabled source and (re)builds the packs Parley reads.
 * - FTC: one public CSV per weekday; each day is counted once and cached as a small tally, so a daily run
 *   downloads about one file (~1 MB) and rebuilds the rolling window on the phone.
 * - ARCEP: built in (no download).
 * - Community: `.parleylist` files kept byte for byte, so their publisher's signature still verifies in Parley.
 */
object Updater {
    private val lock = Mutex()
    private val FTC_ZONE: ZoneId = ZoneId.of("America/New_York")
    private const val FTC_MAX = 30L * 1024 * 1024
    private const val COMMUNITY_MAX = 100L * 1024 * 1024
    const val ARCEP_KEY = "arcep"
    const val FTC_KEY = "ftc"

    suspend fun run(context: Context): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val repo = ListsRepo.get(context)
            repo.update { it.copy(running = true) }
            var ok = true
            try {
                val cfg = repo.state.value.config
                ok = ftc(repo, cfg) and ok
                ok = arcep(repo, cfg) and ok
                ok = community(repo, cfg) and ok
                prune(repo, cfg)
                repo.update { it.copy(lastRun = System.currentTimeMillis(), lastRunError = if (ok) null else "Some lists couldn't be updated") }
            } catch (e: Exception) {
                ok = false
                repo.update { it.copy(lastRun = System.currentTimeMillis(), lastRunError = e.message ?: "Update failed") }
            } finally {
                repo.update { it.copy(running = false) }
            }
            ok
        }
    }

    private fun status(repo: ListsRepo, key: String, f: (SourceStatus) -> SourceStatus) =
        repo.update { s -> s.copy(status = s.status + (key to f(s.status[key] ?: SourceStatus()))) }

    private fun writePack(repo: ListsRepo, id: String, bytes: ByteArray): File {
        val target = repo.packFile(id) ?: throw PackException("Invalid list id")
        val tmp = File(repo.packsDir, "$id.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
        return target
    }

    private fun record(repo: ListsRepo, bytes: ByteArray, origin: String, sourceUrl: String? = null): PackInfo {
        val p = ListPack.parse(bytes)
        val m = p.manifest
        writePack(repo, m.id, bytes)
        val info = PackInfo(
            id = m.id, name = m.name, version = m.version, entries = p.numbers.size / ListPack.RECORD, ranges = p.ranges.size,
            sizeBytes = bytes.size.toLong(), updatedAt = System.currentTimeMillis(),
            fingerprint = p.fingerprint.takeIf { p.signature == SignatureStatus.SIGNED },
            source = m.source, licence = m.licence, origin = origin, sourceUrl = sourceUrl,
        )
        repo.update { s -> s.copy(packs = s.packs.filter { it.id != m.id } + info) }
        return info
    }

    // ---------- FTC ----------

    private fun ftcDays(cfg: UpdaterConfig, today: LocalDate): List<LocalDate> =
        (0 until cfg.ftcDays.coerceIn(1, 365)).map { today.minusDays(it.toLong()) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

    private fun ftc(repo: ListsRepo, cfg: UpdaterConfig): Boolean {
        if (!cfg.ftcEnabled) return true
        val now = System.currentTimeMillis()
        status(repo, FTC_KEY) { it.copy(lastAttempt = now) }
        val today = LocalDate.now(FTC_ZONE)
        val days = ftcDays(cfg, today)
        var downloaded = 0L
        var fetched = 0
        var error: String? = null
        for (d in days) {
            val tally = File(repo.ftcDir, "$d.tally")
            val missing = File(repo.ftcDir, "$d.missing")
            if (tally.exists()) continue
            // Holidays have no file. Recent days are retried: the file appears around noon Eastern time.
            if (missing.exists() && d.isBefore(today.minusDays(3))) continue
            when (val r = Downloader.get(FtcDncSource.dailyUrl(d), FTC_MAX)) {
                is Downloader.Result.Ok -> {
                    downloaded += r.bytes.size
                    val csv = r.bytes.decodeToString()
                    if (!FtcDncSource.looksValid(csv)) {
                        error = "The FTC file for $d has an unexpected format"
                        continue
                    }
                    val t = CsvPackConverter.tally(csv, FtcDncSource.SPEC)
                    val tmp = File(repo.ftcDir, "$d.tmp")
                    tmp.writeText(t.encode())
                    tmp.renameTo(tally)
                    missing.delete()
                    fetched++
                }
                Downloader.Result.NotFound -> missing.writeText("")
                Downloader.Result.NotModified -> Unit
                is Downloader.Result.Failed -> {
                    error = r.reason
                    break
                }
            }
        }
        // Forget days that left the window.
        val keep = days.map { it.toString() }.toSet()
        repo.ftcDir.listFiles()?.forEach { f -> if (f.name.substringBefore('.') !in keep) f.delete() }

        val existing = repo.state.value.packs.firstOrNull { it.id == FtcDncSource.PACK_ID }
        val windowChanged = existing == null || existing.name != FtcDncSource.manifest(cfg.ftcDays, 0, 0).name
        if (fetched > 0 || windowChanged) {
            val window = ReportTally()
            days.forEach { d -> File(repo.ftcDir, "$d.tally").takeIf { it.exists() }?.let { window.merge(ReportTally.decode(it.readText())) } }
            if (window.size > 0) {
                val version = now / 60_000L
                val bytes = CsvPackConverter.build(window, FtcDncSource.SPEC, FtcDncSource.manifest(cfg.ftcDays, version, now), repo.signingKey(), now)
                record(repo, bytes, FTC_KEY, FtcDncSource.PAGE)
            }
        }
        status(repo, FTC_KEY) { it.copy(lastSuccess = if (error == null) now else it.lastSuccess, error = error, downloaded = downloaded) }
        return error == null
    }

    // ---------- ARCEP (built in) ----------

    private fun arcep(repo: ListsRepo, cfg: UpdaterConfig): Boolean {
        if (!cfg.arcepEnabled) return true
        val b = BuiltInPacks.FRANCE_ARCEP
        val version = 20230101L
        val existing = repo.state.value.packs.firstOrNull { it.id == b.id }
        if (existing != null && existing.version == version && repo.packFile(b.id)?.exists() == true) return true
        val builder = PackBuilder(
            PackManifest(
                id = b.id, name = b.name, publisher = "Parley Lists (ARCEP ranges, built in)", source = b.source,
                licence = "Public regulatory data", version = version, created = 1_672_531_200_000L, ttlDays = 0,
                regions = listOf(b.country), categories = mapOf("1" to b.category),
            ),
        )
        b.ranges.forEach { builder.addRange(it, 1, b.score) }
        record(repo, builder.build(repo.signingKey(), 1_672_531_200_000L), ARCEP_KEY, b.source)
        status(repo, ARCEP_KEY) { SourceStatus(lastAttempt = System.currentTimeMillis(), lastSuccess = System.currentTimeMillis()) }
        return true
    }

    // ---------- Community packs ----------

    private fun community(repo: ListsRepo, cfg: UpdaterConfig): Boolean {
        var ok = true
        val reserved = setOf(FtcDncSource.PACK_ID, BuiltInPacks.FRANCE_ARCEP.id)
        for (src in cfg.community) {
            val prev = repo.state.value.status[src.url] ?: SourceStatus()
            val known = repo.state.value.packs.firstOrNull { it.sourceUrl == src.url && it.origin == "community" }
            val now = System.currentTimeMillis()
            val useCache = known != null && repo.packFile(known.id)?.exists() == true
            val r = Downloader.get(src.url, COMMUNITY_MAX, prev.etag.takeIf { useCache }, prev.lastModified.takeIf { useCache })
            val error: String? = when (r) {
                is Downloader.Result.Ok -> try {
                    val p = ListPack.parse(r.bytes)
                    when {
                        p.manifest.id in reserved -> "This list uses a reserved id (${p.manifest.id})"
                        repo.state.value.packs.any { it.id == p.manifest.id && it.sourceUrl != src.url } -> "Another link already provides the list ${p.manifest.id}"
                        known?.fingerprint != null && p.fingerprint != known.fingerprint -> "The list is now signed by a different key; remove and add the link again to accept it"
                        else -> {
                            if (known != null && known.id != p.manifest.id) {
                                repo.packFile(known.id)?.delete()
                                repo.update { s -> s.copy(packs = s.packs.filter { it.id != known.id }) }
                            }
                            record(repo, r.bytes, "community", src.url)
                            status(repo, src.url) { it.copy(etag = r.etag, lastModified = r.lastModified, downloaded = r.bytes.size.toLong()) }
                            null
                        }
                    }
                } catch (e: PackException) {
                    e.message ?: "Not a valid list"
                }
                Downloader.Result.NotModified -> null
                Downloader.Result.NotFound -> "The link no longer exists (404)"
                is Downloader.Result.Failed -> r.reason
            }
            status(repo, src.url) { it.copy(lastAttempt = now, lastSuccess = if (error == null) now else it.lastSuccess, error = error) }
            if (error != null) ok = false
        }
        return ok
    }

    /** Removes packs whose source was turned off or removed, so Parley stops offering them. */
    fun prune(repo: ListsRepo, cfg: UpdaterConfig = repo.state.value.config) {
        val urls = cfg.community.map { it.url }.toSet()
        val drop = repo.state.value.packs.filter { p ->
            when (p.origin) {
                FTC_KEY -> !cfg.ftcEnabled
                ARCEP_KEY -> !cfg.arcepEnabled
                else -> p.sourceUrl !in urls
            }
        }
        if (drop.isEmpty()) return
        drop.forEach { p -> repo.packFile(p.id)?.delete() }
        if (!cfg.ftcEnabled) repo.ftcDir.listFiles()?.forEach { it.delete() }
        val ids = drop.map { it.id }.toSet()
        repo.update { s -> s.copy(packs = s.packs.filter { it.id !in ids }, status = s.status.filterKeys { k -> k == FTC_KEY || k == ARCEP_KEY || k in urls }) }
    }
}
