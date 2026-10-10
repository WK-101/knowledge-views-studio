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

    private companion object {
        const val VM_C_CEILING = 634
        const val CONSTRUCTOR_CEILING = 35
        val VM_C = Regex("""\bvm\.c\.""")
        val CONSTRUCTOR = Regex("""class\s+\w+(?:<[^>]*>)?\s*(?:(?:private|internal)\s+)?(?:constructor\s*)?\(([^)]*)\)""")
        val CONTAINER_PARAM = Regex("""\bc:\s*DataContainer\b""")
    }
}
