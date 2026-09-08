package com.cairn.reader.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards charset handling for fetched feeds/pages. Before this, everything was decoded as UTF-8,
 * so a legacy-encoded feed (Windows-1252 / Latin-1 / etc.) that declared its charset only in the
 * XML prolog or a <meta> tag came out as mojibake.
 */
class BodyDecoderTest {

    @Test fun `uses the charset declared in the XML prolog`() {
        // 0xE9 is 'é' in Windows-1252; as UTF-8 it is an invalid lone byte.
        val bytes = "<?xml version=\"1.0\" encoding=\"windows-1252\"?><t>caf".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0xE9.toByte()) + "</t>".toByteArray(Charsets.US_ASCII)
        val decoded = BodyDecoder.decode(bytes, contentTypeHeader = null)
        assertTrue("prolog charset should yield 'café', got: $decoded", decoded.contains("café"))
    }

    @Test fun `HTTP Content-Type charset takes precedence over the document`() {
        val bytes = "<t>caf".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xE9.toByte()) +
            "</t>".toByteArray(Charsets.US_ASCII)
        val decoded = BodyDecoder.decode(bytes, contentTypeHeader = "text/xml; charset=ISO-8859-1")
        assertTrue(decoded.contains("café"))
    }

    @Test fun `honours an HTML meta charset`() {
        val bytes = "<html><head><meta charset=\"windows-1252\"></head><body>caf".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0xE9.toByte()) + "</body></html>".toByteArray(Charsets.US_ASCII)
        val decoded = BodyDecoder.decode(bytes, contentTypeHeader = null)
        assertTrue(decoded.contains("café"))
    }

    @Test fun `strips a UTF-8 byte-order mark`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray(Charsets.UTF_8)
        assertEquals("hi", BodyDecoder.decode(bytes, null))
    }

    @Test fun `defaults to UTF-8 when nothing is declared`() {
        val decoded = BodyDecoder.decode("café".toByteArray(Charsets.UTF_8), null)
        assertEquals("café", decoded)
    }

    @Test fun `empty body decodes to empty string`() {
        assertEquals("", BodyDecoder.decode(ByteArray(0), "text/xml; charset=utf-8"))
    }
}
