package app.parley.common.qr

import java.net.IDN

/**
 * Q4 anti-phishing: the parts of a web address worth showing before anyone opens it. The domain is shown big
 * (in its readable form, with the punycode form beside it when they differ), and a few offline heuristics raise a
 * warning: look-alike letters from other scripts, a well-known name in front of someone else's domain, link
 * shorteners that hide the destination, `user@host` tricks, bare IP addresses and plain http. Nothing is looked up.
 */
object UrlSafety {
    enum class Warning { NOT_HTTPS, IDN, MIXED_SCRIPT, LOOKALIKE, SHORTENER, USERINFO, IP_ADDRESS }

    data class Info(
        val scheme: String,
        /** The host as the browser sends it (ASCII, punycode for IDNs), lower-case. */
        val asciiHost: String,
        /** The host in readable form (Unicode), lower-case. */
        val displayHost: String,
        /** The part of the host that decides who owns it ("example.co.uk"), readable form. */
        val domain: String,
        val warnings: Set<Warning>,
        /** The address to show in full and to open: [asciiHost], no user name, `\` read as `/`. */
        val url: String,
    ) {
        val isRisky: Boolean get() = warnings.any { it != Warning.NOT_HTTPS && it != Warning.IDN }
    }

    /** Common link shorteners (the real destination is hidden until the browser opens it). */
    val SHORTENERS = setOf(
        "bit.ly", "bitly.com", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly", "rebrand.ly",
        "shorturl.at", "tiny.cc", "rb.gy", "s.id", "qrco.de", "bit.do", "lnkd.in", "v.gd", "t.ly", "shorturl.com",
        "tr.ee", "short.io", "bl.ink", "rebrandly.com", "qr.net", "qrcodes.pro", "u.to", "clck.ru", "urlz.fr", "surl.li",
    )

    /** Names phishing pages like to put in front of their own domain ("paypal.com.secure-login.io"). */
    private val BRANDS = setOf(
        "paypal", "google", "apple", "icloud", "microsoft", "outlook", "amazon", "whatsapp", "facebook",
        "instagram", "netflix", "telegram", "linkedin", "dhl", "fedex", "usps", "royalmail",
        "chase", "wellsfargo", "hsbc", "barclays", "santander", "revolut", "binance", "coinbase", "ebay",
    )

    /** Second-level labels used under country codes ("co.uk", "com.au", "ne.jp"…). */
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "nic", "mil", "ltd", "plc", "sch")

    /**
     * Null when [url] isn't an http(s) address with a clear host. The host is read by [WebUrl] exactly as a browser
     * reads it, and [Info.url] is the address to open (and show): the same host, without any `user@` part.
     */
    fun analyse(url: String): Info? {
        val w = WebUrl.parse(url) ?: return null
        val scheme = w.scheme
        val host = w.host
        val warnings = LinkedHashSet<Warning>()
        if (scheme == "http") warnings += Warning.NOT_HTTPS
        if (w.hadUserInfo) warnings += Warning.USERINFO
        if (w.isIp) {
            warnings += Warning.IP_ADDRESS
            return Info(scheme, host, host, host, warnings, w.href)
        }
        val ascii = host
        val unicode = runCatching { IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host).lowercase()
        if (ascii != unicode || ascii.split('.').any { it.startsWith("xn--") }) warnings += Warning.IDN
        if (unicode.split('.').any { mixedScripts(it) }) warnings += Warning.MIXED_SCRIPT
        val domain = registrable(unicode)
        val asciiDomain = registrable(ascii)
        if (asciiDomain in SHORTENERS || ascii in SHORTENERS) warnings += Warning.SHORTENER
        // A brand in the subdomain part, but the domain that owns the page is something else.
        val sub = unicode.removeSuffix(domain).trimEnd('.')
        val owner = domain.substringBefore('.')
        if (sub.isNotEmpty() && sub.split('.', '-').any { it in BRANDS } && owner !in BRANDS) warnings += Warning.LOOKALIKE
        // Letters swapped for digits or look-alikes of a well-known name in the owning label ("paypa1", "g00gle").
        if (owner !in BRANDS && deconfuse(owner) in BRANDS) warnings += Warning.LOOKALIKE
        return Info(scheme, ascii, unicode, domain, warnings, w.href)
    }

    /** The registrable domain of [host]: the last two labels, or three under a country's second level ("co.uk"). */
    fun registrable(host: String): String {
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val tld = labels.last()
        val second = labels[labels.size - 2]
        val take = if (tld.length == 2 && second in SECOND_LEVEL) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

    /** Whether one label mixes Latin letters with Cyrillic or Greek ones (the classic look-alike trick). */
    fun mixedScripts(label: String): Boolean {
        val scripts = HashSet<Character.UnicodeScript>()
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            scripts += Character.UnicodeScript.of(cp)
        }
        val confusable = setOf(Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK)
        return Character.UnicodeScript.LATIN in scripts && scripts.any { it in confusable } ||
            // A whole label in Cyrillic/Greek letters that all look Latin ("аррӏе") is just as risky.
            scripts.size == 1 && scripts.first() in confusable && label.all { it in LATIN_LOOKALIKES || !Character.isLetter(it) }
    }

    /** Cyrillic and Greek letters that look like Latin ones. */
    private const val LATIN_LOOKALIKES = "аеорсухіјѕԁӏԛԝаоАВЕКМНОРСТХІЈЅοαερτυνκιΑΒΕΖΗΙΚΜΝΟΡΤΥΧ"

    private fun deconfuse(s: String): String = s.map {
        when (it) { '0' -> 'o'; '1' -> 'l'; '3' -> 'e'; '5' -> 's'; '4' -> 'a'; '7' -> 't'; else -> it }
    }.joinToString("").replace("rn", "m").replace("vv", "w")
}
