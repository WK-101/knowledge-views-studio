package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

class CollationTest {
    private val names = listOf("Émile", "emma", "Zoë", "zoe", "Ångström", "anna", "Bob", "bob", "Øyvind", "oscar", "Ça va", "Cyd")

    @Test fun keysSortLikeTheCollator() {
        val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
        val expected = names.sortedWith { a, b -> collator.compare(a, b) }
        assertEquals(expected, Collation.sortedBy(names, Collation.Order(collator)) { it })
    }

    @Test fun tiesKeepTheirOrder() {
        // "Bob" and "bob" are equal at primary strength: the first stays first, either way round.
        assertEquals(listOf("Bob", "bob"), Collation.sortedBy(listOf("Bob", "bob"), Collation.Order()) { it })
        assertEquals(listOf("bob", "Bob"), Collation.sortedBy(listOf("bob", "Bob"), Collation.Order()) { it })
    }

    @Test fun twentyThousandNamesSortTheSameWithKeys() {
        val r = Random(7)
        val big = List(20_000) { names[r.nextInt(names.size)] + r.nextInt(1_000) }
        val order = Collation.Order()
        val plain = big.sortedWith { a, b -> order.compare(a, b) }
        assertEquals(plain, Collation.sortedBy(big, order) { it })
        // Any other comparator is used as it is.
        assertEquals(big.sorted(), Collation.sortedBy(big, naturalOrder()) { it })
    }
}
