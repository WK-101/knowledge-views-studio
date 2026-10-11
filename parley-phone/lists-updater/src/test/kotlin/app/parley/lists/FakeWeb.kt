package app.parley.lists

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** Canned answers by address for [Downloader], so no test reaches the network. Records what was asked. */
internal class FakeWeb {
    data class Answer(val code: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap(), val gzip: Boolean = false)

    val answers = HashMap<String, Answer>()
    val asked = ArrayList<Pair<String, Map<String, String>>>()

    fun install() {
        Downloader.open = { url -> Connection(url) }
    }

    private inner class Connection(url: URL) : HttpURLConnection(url) {
        private val answer get() = answers[url.toString()] ?: Answer(404)
        private val body: ByteArray
            get() = if (!answer.gzip) answer.body else ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(answer.body) } }.toByteArray()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false

        override fun getResponseCode(): Int {
            asked += url.toString() to requestProperties.mapValues { it.value.joinToString() }
            return answer.code
        }

        override fun getHeaderField(name: String): String? = answer.headers[name]
        override fun getContentEncoding(): String? = if (answer.gzip) "gzip" else null
        override fun getContentLengthLong(): Long = answer.headers["Content-Length"]?.toLong() ?: -1L
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }
}
