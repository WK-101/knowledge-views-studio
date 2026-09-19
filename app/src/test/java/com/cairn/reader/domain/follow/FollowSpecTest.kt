package com.cairn.reader.domain.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowSpecTest {

    @Test fun `author round-trips through encode-decode`() {
        val spec = FollowSpec.author("Ta-Nehisi Coates")
        assertEquals(spec, FollowSpec.decode(spec.encode()))
        assertEquals(FollowSpec.Kind.AUTHOR, FollowSpec.decode(spec.encode())?.kind)
    }

    @Test fun `topic round-trips through encode-decode`() {
        val spec = FollowSpec.topic("climate policy")
        assertEquals(spec, FollowSpec.decode(spec.encode()))
        assertEquals(FollowSpec.Kind.TOPIC, FollowSpec.decode(spec.encode())?.kind)
    }

    @Test fun `values containing separators or pipes survive`() {
        // A byline with a colon / pipe / dash must round-trip unharmed.
        val spec = FollowSpec.author("Smith, John | Analysis: Markets")
        assertEquals(spec.value, FollowSpec.decode(spec.encode())?.value)
    }

    @Test fun `factory methods trim`() {
        assertEquals("Jane Roe", FollowSpec.author("  Jane Roe  ").value)
        assertEquals("ai", FollowSpec.topic("  ai  ").value)
    }

    @Test fun `malformed encodings decode to null`() {
        assertNull(FollowSpec.decode(""))
        assertNull(FollowSpec.decode("no separator here"))
        assertNull(FollowSpec.decode("A\u001F"))       // blank value
        assertNull(FollowSpec.decode("X\u001Fvalue"))  // unknown kind tag
    }

    @Test fun `decodeAll drops junk and sorts case-insensitively`() {
        val out = FollowSpec.decodeAll(
            setOf(
                FollowSpec.topic("zebra").encode(),
                "garbage",
                FollowSpec.author("alpha").encode(),
                FollowSpec.topic("Mango").encode(),
            ),
        )
        assertEquals(listOf("alpha", "Mango", "zebra"), out.map { it.value })
        assertTrue(out.none { it.value == "garbage" })
    }
}
