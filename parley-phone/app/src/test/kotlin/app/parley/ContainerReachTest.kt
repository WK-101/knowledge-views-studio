package app.parley

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A ratchet on reaching through the whole `DataContainer`: screens that go `vm.c.<store>` and classes that take the
 * container in their constructor hide what they depend on and make start-up order fragile. The counts may only fall.
 * When a change lowers one, lower its ceiling here too, so it can't creep back.
 */
class ContainerReachTest {
    private val root = File("..").canonicalFile

    private fun sources(vararg modules: String): List<String> = modules.flatMap { m ->
        File(root, "$m/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
    }

    @Test fun screens_reach_through_vm_c_no_more_than_before() {
        val count = sources("app").sumOf { VM_C.findAll(it).count() }
        assertTrue("the scan found nothing: wrong folder?", count > 0)
        assertTrue("vm.c. is used $count times, more than the ceiling of $VM_C_CEILING: pass what the screen needs instead", count <= VM_C_CEILING)
    }

    @Test fun classes_take_the_whole_container_no_more_than_before() {
        val count = sources("app", "core/data", "telecom").sumOf { text ->
            CONSTRUCTOR.findAll(text).count { CONTAINER_PARAM.containsMatchIn(it.groupValues[1]) }
        }
        assertTrue("the scan found nothing: wrong folder?", count > 0)
        assertTrue(
            "$count classes take the whole DataContainer, more than the ceiling of $CONSTRUCTOR_CEILING: pass the stores they use instead",
            count <= CONSTRUCTOR_CEILING,
        )
    }

    /**
     * No coroutine starts inside an `init` block: a job launched from a constructor can run before the properties
     * declared after it are set (the vault's listing once read its summary cache that way). Classes start their upkeep
     * in an explicit `start()` that their owner calls right after construction.
     */
    @Test fun nothing_launches_from_an_init_block() {
        val found = MODULES.flatMap { m ->
            File(root, "$m/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                val text = f.readText()
                INIT.findAll(text).filter { LAUNCH.containsMatchIn(blockAfter(text, it.range.last + 1)) }.map { "${f.name}:${line(text, it.range.first)}" }
            }.toList()
        }
        assertTrue("coroutines launched from init blocks (move them to start()): $found", found.isEmpty())
    }

    /** The text of the block whose opening brace ends just before [from], up to its matching closing brace. */
    private fun blockAfter(text: String, from: Int): String {
        var depth = 1
        var i = from
        while (depth > 0 && i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return text.substring(from, i)
    }

    private fun line(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1

    private companion object {
        const val VM_C_CEILING = 596
        const val CONSTRUCTOR_CEILING = 35
        val MODULES = listOf("app", "core/data", "core/common", "core/ui", "telecom")
        val INIT = Regex("""(?m)^\s*init\s*\{""")
        val LAUNCH = Regex("""\.launch\b|\blaunch\s*[({]|\.launchIn\(""")
        val VM_C = Regex("""\bvm\.c\.""")
        val CONSTRUCTOR = Regex("""class\s+\w+(?:<[^>]*>)?\s*(?:(?:private|internal)\s+)?(?:constructor\s*)?\(([^)]*)\)""")
        val CONTAINER_PARAM = Regex("""\bc:\s*DataContainer\b""")
    }
}
