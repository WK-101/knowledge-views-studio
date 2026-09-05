package com.cairn.reader.data.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Strips tracking / analytics query parameters from a URL, on-device and for free. Cairn is
 * privacy-first, so links it stores, opens, and shares shouldn't carry the campaign and
 * fingerprinting cruft that sites append (utm_*, fbclid, gclid, …). Pure string work — no network.
 */
object UrlCleaner {

    /** Exact parameter names to drop (case-insensitive). */
    private val DROP = setOf(
        // Google / generic UTM
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "utm_name", "utm_cid", "utm_reader", "utm_referrer", "utm_social", "utm_brand",
        "utm_pubreferrer", "utm_swu", "utm_viz_id",
        // Click IDs
        "gclid", "gclsrc", "dclid", "fbclid", "yclid", "msclkid", "twclid", "igshid",
        "mc_eid", "mc_cid", "vero_id", "vero_conv", "oly_anon_id", "oly_enc_id",
        "_hsenc", "_hsmi", "hsctatracking", "wickedid", "s_cid", "ns_campaign", "ns_mchannel",
        // Referrers / sources
        "ref", "ref_src", "ref_url", "referrer", "source", "src", "cmpid", "campaign_id",
        "spm", "scm", "traffic_source", "trk", "trkcampaign",
        // Misc analytics
        "gi", "ceid", "recruiter", "guccounter", "at_medium", "at_campaign",
    )

    /** Parameter prefixes to drop (case-insensitive) — catches families like utm_*, pk_*, ga_*. */
    private val DROP_PREFIX = listOf("utm_", "pk_", "piwik_", "ga_", "hsa_", "mtm_", "matomo_", "_bta_", "vero_")

    /**
     * Returns [raw] with tracking parameters removed. Preserves order and all functional
     * parameters. Non-http(s) or unparseable input is returned unchanged.
     */
    fun strip(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        if (url.querySize == 0) return raw
        val kept = ArrayList<Pair<String, String?>>(url.querySize)
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            val lower = name.lowercase()
            val drop = lower in DROP || DROP_PREFIX.any { lower.startsWith(it) }
            if (!drop) kept += name to url.queryParameterValue(i)
        }
        if (kept.size == url.querySize) return raw // nothing removed — keep the original string intact
        val b = url.newBuilder().query(null)
        for ((n, v) in kept) b.addQueryParameter(n, v)
        return b.build().toString()
    }
}
