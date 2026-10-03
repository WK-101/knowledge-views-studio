package app.parley

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WorkManager stores the class name of every queued job and builds the worker by reflection when it runs, possibly
 * after an update. R8 removes a class nothing in the code names (a worker kept only for jobs an earlier version
 * queued), and WorkManager's own rule keeps only the names of workers that survive anyway. So each module's rules must
 * keep every [ListenableWorker] with its constructor, and every worker in the sources must be one that rule covers.
 */
class WorkerKeepRulesTest {
    private val moduleDir = File(".").absoluteFile

    private val keepRule = Regex(
        """-keep class \* extends androidx\.work\.ListenableWorker\s*\{\s*""" +
            """public <init>\(android\.content\.Context,\s*androidx\.work\.WorkerParameters\);\s*}""",
    )

    @Test fun everyModuleWithWorkersKeepsThem() {
        for (rules in listOf(File(moduleDir, "proguard-rules.pro"), File(moduleDir, "../lists-updater/proguard-rules.pro"))) {
            assertTrue("${rules.path} must keep every worker", keepRule.containsMatchIn(rules.readText()))
        }
    }

    @Test fun everyWorkerInTheSourcesIsCoveredByTheRule() {
        val declared = Regex("""class (\w+)\(\s*context: Context,\s*params: WorkerParameters\s*\)\s*:\s*(?:Coroutine)?Worker\(""")
        val sources = File(moduleDir, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
        val workers = sources.flatMap { f ->
            val pkg = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE).find(f.readText())?.groupValues?.get(1) ?: return@flatMap emptySequence()
            declared.findAll(f.readText()).map { "$pkg.${it.groupValues[1]}" }
        }.toList()
        // The one no code names any more: it runs follow-ups queued by earlier versions.
        assertTrue(workers.toString(), "app.parley.work.FollowUpWorker" in workers)
        for (name in workers) {
            val cls = Class.forName(name)
            assertTrue("$name must be a ListenableWorker", ListenableWorker::class.java.isAssignableFrom(cls))
            // The constructor the rule keeps and WorkManager's default factory calls.
            cls.getConstructor(Context::class.java, WorkerParameters::class.java)
        }
    }
}
