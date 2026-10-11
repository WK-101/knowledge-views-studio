package app.parley.lists

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The companion's only network code: HTTPS only, sizes capped, caching headers honoured, nothing about the phone sent. */
@RunWith(RobolectricTestRunner::class)
class DownloaderTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val web = FakeWeb()
    private val url = "https://lists.example.org/pack.parleylist"

    @Before fun setUp() = web.install()

    @Test fun a_file_comes_back_with_its_caching_headers() {
        web.answers[url] = FakeWeb.Answer(200, "pack".toByteArray(), mapOf("ETag" to "\"v1\"", "Last-Modified" to "Tue, 06 Oct 2026 10:00:00 GMT"))
        val r = Downloader.get(res, url, 1_000) as Downloader.Result.Ok
        assertArrayEquals("pack".toByteArray(), r.bytes)
        assertEquals("\"v1\"", r.etag)
        assertEquals("Tue, 06 Oct 2026 10:00:00 GMT", r.lastModified)
        // The same request for everyone: only the app's name and the encoding it reads.
        assertEquals(setOf("User-Agent", "Accept-Encoding"), web.asked.single().second.keys)
    }

    @Test fun a_compressed_answer_is_unpacked() {
        web.answers[url] = FakeWeb.Answer(200, "x".repeat(500).toByteArray(), gzip = true)
        assertEquals(500, (Downloader.get(res, url, 1_000) as Downloader.Result.Ok).bytes.size)
    }

    @Test fun an_unchanged_file_is_not_downloaded_again() {
        web.answers[url] = FakeWeb.Answer(304)
        assertEquals(Downloader.Result.NotModified, Downloader.get(res, url, 1_000, etag = "\"v1\""))
        assertEquals("\"v1\"", web.asked.single().second["If-None-Match"])
    }

    @Test fun plain_http_is_refused_even_after_a_redirect() {
        assertTrue(Downloader.get(res, "http://lists.example.org/pack", 1_000) is Downloader.Result.Failed)
        web.answers[url] = FakeWeb.Answer(302, headers = mapOf("Location" to "http://lists.example.org/pack"))
        assertTrue(Downloader.get(res, url, 1_000) is Downloader.Result.Failed)
        assertEquals(1, web.asked.size)
    }

    @Test fun an_https_redirect_is_followed() {
        web.answers[url] = FakeWeb.Answer(301, headers = mapOf("Location" to "/moved.parleylist"))
        web.answers["https://lists.example.org/moved.parleylist"] = FakeWeb.Answer(200, "moved".toByteArray())
        assertArrayEquals("moved".toByteArray(), (Downloader.get(res, url, 1_000) as Downloader.Result.Ok).bytes)
    }

    @Test fun too_large_files_and_errors_are_reported_not_kept() {
        web.answers[url] = FakeWeb.Answer(200, ByteArray(2_000), mapOf("Content-Length" to "2000"))
        assertTrue(Downloader.get(res, url, 1_000) is Downloader.Result.Failed)
        // A server that doesn't say the size is stopped at the cap too.
        web.answers[url] = FakeWeb.Answer(200, ByteArray(2_000))
        assertTrue(Downloader.get(res, url, 1_000) is Downloader.Result.Failed)
        web.answers[url] = FakeWeb.Answer(410)
        assertEquals(Downloader.Result.NotFound, Downloader.get(res, url, 1_000))
        web.answers[url] = FakeWeb.Answer(503)
        assertTrue(Downloader.get(res, url, 1_000) is Downloader.Result.Failed)
    }
}
