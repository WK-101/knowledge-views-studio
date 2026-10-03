package app.parley.common.fuzz

import java.io.File
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import org.junit.Assert.fail

/**
 * A small deterministic fuzzer that runs as a plain JUnit test (no coverage guidance, no extra dependency). Each
 * target starts from its corpus in `src/test/resources/fuzz/<target>/`, then mutates it with a fixed seed: flips,
 * inserts, deletes, splices, repeats and parser-specific tokens from a dictionary. Every input must finish within
 * [Target.perInputMs]; an exception that isn't in [Target.allowed] fails the test with the input (base64) so it can
 * be added to the corpus as a regression.
 */
object Fuzz {
    /**
     * Seeds used by every target: fixed so a failure reproduces. `PARLEY_FUZZ_SEEDS=50 ./gradlew :core:common:test
     * --tests 'app.parley.common.fuzz.*'` widens the search for a longer local run.
     */
    val SEEDS: LongArray = (0 until (System.getenv("PARLEY_FUZZ_SEEDS")?.toIntOrNull()?.coerceAtLeast(1) ?: 2))
        .map { 0x5EED_0001L + it }.toLongArray()

    class Target(
        val name: String,
        val iterations: Int,
        val dictionary: List<String> = emptyList(),
        val perInputMs: Long = 2_000,
        val maxInputBytes: Int = 64 * 1024,
        val allowed: Set<Class<out Throwable>> = emptySet(),
        /** Extra starting inputs built in code (e.g. signed packs); the corpus files come first. */
        val extraSeeds: List<ByteArray> = emptyList(),
    )

    /** The corpus files of [target], sorted by name (so the run is the same on every machine). */
    fun corpus(target: String): List<ByteArray> {
        val url = Fuzz::class.java.getResource("/fuzz/$target") ?: return emptyList()
        val dir = File(url.toURI())
        return dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.map { it.readBytes() }
    }

    /** Runs [check] on the corpus and on [Target.iterations] mutations per seed. */
    fun run(target: Target, check: (ByteArray) -> Unit) {
        val seeds = corpus(target.name) + target.extraSeeds
        check(seeds.isNotEmpty()) { "No corpus for ${target.name}" }
        val exec = Executors.newSingleThreadExecutor(DAEMON)
        try {
            seeds.forEach { attempt(target, exec, it, check) }
            for (seed in SEEDS) {
                val mutator = Mutator(Random(seed xor target.name.hashCode().toLong()), target.dictionary.map { it.toByteArray() }, target.maxInputBytes)
                repeat(target.iterations) {
                    attempt(target, exec, mutator.next(seeds), check)
                }
            }
        } finally {
            exec.shutdownNow()
        }
    }

    private fun attempt(target: Target, exec: java.util.concurrent.ExecutorService, input: ByteArray, check: (ByteArray) -> Unit) {
        val future = exec.submit { check(input) }
        try {
            future.get(target.perInputMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            fail("${target.name}: input took longer than ${target.perInputMs} ms: ${b64(input)}")
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is AssertionError) throw AssertionError("${target.name}: ${cause.message}\ninput: ${b64(input)}", cause)
            if (target.allowed.none { it.isInstance(cause) }) {
                throw AssertionError("${target.name}: ${cause.javaClass.name}: ${cause.message}\ninput: ${b64(input)}", cause)
            }
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b.copyOf(minOf(b.size, 4096)))

    private val DAEMON = ThreadFactory { r -> Thread(r, "fuzz").apply { isDaemon = true } }

    /** Byte-level mutations of a corpus entry; a few at a time, so inputs stay close to well formed. */
    class Mutator(private val random: Random, private val dictionary: List<ByteArray>, private val maxBytes: Int) {
        fun next(corpus: List<ByteArray>): ByteArray {
            var b = corpus[random.nextInt(corpus.size)]
            repeat(1 + random.nextInt(4)) { b = mutate(b, corpus) }
            return if (b.size > maxBytes) b.copyOf(maxBytes) else b
        }

        private fun mutate(b: ByteArray, corpus: List<ByteArray>): ByteArray {
            if (b.isEmpty()) return dictionary.randomOrNull(random) ?: byteArrayOf(random.nextInt(BYTE_VALUES).toByte())
            return operations[random.nextInt(operations.size)](b, random.nextInt(b.size), corpus)
        }

        /** Each mutation: the input, a position in it and the corpus (for splicing). */
        private val operations: List<(ByteArray, Int, List<ByteArray>) -> ByteArray> = listOf(
            { b, at, _ -> b.copyOf().also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(BITS))).toByte() } },
            { b, at, _ -> b.copyOf().also { it[at] = INTERESTING[random.nextInt(INTERESTING.size)] } },
            { b, at, _ -> b.copyOfRange(0, at) + b.copyOfRange(minOf(b.size, at + 1 + random.nextInt(MAX_DELETE)), b.size) },
            { b, at, _ -> insert(b, at, random.nextBytes(1 + random.nextInt(MAX_INSERT))) },
            { b, at, _ -> insert(b, at, dictionary.randomOrNull(random) ?: random.nextBytes(MAX_INSERT)) },
            { b, at, _ -> b.copyOf(at) },
            { b, at, _ -> repeatChunk(b, at) },
            { b, at, corpus -> splice(b, at, corpus[random.nextInt(corpus.size)]) },
            { b, at, _ -> b.copyOfRange(0, at) + b.copyOfRange(at, b.size).reversedArray() },
        )

        /** Repeats a chunk: long lines, deep nesting, many cards. */
        private fun repeatChunk(b: ByteArray, at: Int): ByteArray {
            val end = minOf(b.size, at + 1 + random.nextInt(MAX_CHUNK))
            val chunk = b.copyOfRange(at, end)
            var out = b.copyOfRange(0, end)
            repeat(1 + random.nextInt(MAX_REPEATS)) { out += chunk }
            return out + b.copyOfRange(end, b.size)
        }

        /** The start of [b] followed by the end of [other]. */
        private fun splice(b: ByteArray, at: Int, other: ByteArray): ByteArray =
            b.copyOfRange(0, at) + other.copyOfRange(if (other.isEmpty()) 0 else random.nextInt(other.size), other.size)

        private fun insert(b: ByteArray, at: Int, piece: ByteArray) = b.copyOfRange(0, at) + piece + b.copyOfRange(at, b.size)

        private companion object {
            const val BYTE_VALUES = 256
            const val BITS = 8
            const val MAX_DELETE = 16
            const val MAX_INSERT = 8
            const val MAX_CHUNK = 64
            const val MAX_REPEATS = 32
            val INTERESTING = byteArrayOf(0, 0x7F, -1, -128) + "\n\r;:=\",\\".toByteArray()
        }
    }
}
