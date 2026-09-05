package com.cairn.reader.domain.privacy

import com.cairn.reader.data.net.UrlCleaner
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strips trackers, beacons and privacy-hostile cruft from article HTML before it is stored and
 * read — entirely on-device. Readability already drops most scripts, but feed bodies and
 * WebView-rendered pages arrive raw, and even clean articles smuggle in tracking pixels, beacon
 * `<img>`s, third-party iframes, `on*` handlers and campaign parameters on every in-body link.
 *
 * Cairn is privacy-first: an article you save should not phone home when you open it offline.
 * This is deliberately conservative — it removes things that only exist to track or execute, never
 * real content — so it is safe to run on every body unconditionally when the user opts in.
 */
@Singleton
class ContentSanitizer @Inject constructor() {

    data class Result(val html: String, val removed: Int)

    /** Sanitize [html] resolved against [baseUrl]. Returns the cleaned HTML and how many
     *  tracking elements/attributes were stripped (for a "N trackers blocked" readout). */
    fun sanitize(html: String, baseUrl: String): Result {
        if (html.isBlank()) return Result(html, 0)
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return Result(html, 0)
        var removed = 0

        // 1) Executable / navigational hazards that have no place in a reader body.
        removed += doc.select("script, noscript, iframe, embed, object, form, input, button, base, link[rel=preconnect], link[rel=dns-prefetch], meta[http-equiv=refresh]")
            .also { it.forEach { el -> el.remove() } }.size

        // 2) Tracking pixels & beacons: zero/one-pixel images, or images from known analytics hosts.
        for (img in doc.select("img")) {
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            val src = img.absUrl("src").ifBlank { img.attr("src") }
            val tiny = (w != null && w <= 1) || (h != null && h <= 1)
            val tracker = isTrackerUrl(src)
            if (tiny || tracker) { img.remove(); removed++ }
        }

        // 3) Inline event handlers and legacy tracking attributes on anything that survived.
        for (el in doc.allElements) {
            val toDrop = el.attributes().asList()
                .map { it.key }
                .filter { it.startsWith("on", ignoreCase = true) || it.equals("ping", true) }
            toDrop.forEach { el.removeAttr(it); removed++ }
        }

        // 4) Strip campaign/analytics query params from in-body links and image sources.
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.startsWith("http", ignoreCase = true)) {
                val clean = UrlCleaner.strip(href)
                if (clean != href) a.attr("href", clean)
            }
            // Referrer & window-opener hygiene on outbound links.
            a.attr("rel", listOf(a.attr("rel"), "noopener", "noreferrer").filter { it.isNotBlank() }.joinToString(" ").split(" ").distinct().joinToString(" "))
        }
        for (img in doc.select("img[src]")) {
            val src = img.attr("src")
            if (src.startsWith("http", ignoreCase = true)) {
                val clean = UrlCleaner.strip(src)
                if (clean != src) img.attr("src", clean)
            }
        }

        return Result(bodyHtml(doc), removed)
    }

    /** Readability/feeds hand us a body fragment, so return the body's inner HTML, not a full doc. */
    private fun bodyHtml(doc: Document): String =
        doc.body().html().ifBlank { doc.html() }

    private fun isTrackerUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return TRACKER_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private companion object {
        /** Well-known analytics / beacon hosts whose images/iframes are pure tracking. */
        val TRACKER_HOSTS = setOf(
            "google-analytics.com", "googletagmanager.com", "doubleclick.net", "google-analytics.l.google.com",
            "scorecardresearch.com", "quantserve.com", "quantcount.com",
            "facebook.com/tr", "connect.facebook.net", "pixel.facebook.com",
            "hotjar.com", "mouseflow.com", "fullstory.com", "mixpanel.com", "segment.com", "segment.io",
            "amplitude.com", "chartbeat.com", "parsely.com", "parse.ly", "newrelic.com", "nr-data.net",
            "adobedtm.com", "omtrdc.net", "2o7.net", "demdex.net", "krxd.net", "moatads.com",
            "adsrvr.org", "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "sail-track.com", "sail-horizon.com", "list-manage.com", "mailchimp.com",
            "pardot.com", "marketo.net", "hubspot.com", "hs-analytics.net", "hs-scripts.com",
            "bat.bing.com", "clarity.ms", "yandex.ru", "mc.yandex.ru", "vk.com/rtrg",
            "pinterest.com/ct", "snapchat.com", "tiktok.com/i18n/pixel",
        )
    }
}
