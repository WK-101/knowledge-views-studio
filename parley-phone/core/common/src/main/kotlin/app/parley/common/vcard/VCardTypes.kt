package app.parley.common.vcard

import app.parley.common.record.Mime

/**
 * How one kind of Android data row maps its TYPE column (DATA2, with DATA3 as the custom label) to vCard
 * TYPE parameters and Apple `X-ABLabel` labels.
 *
 * Android type codes are those of `ContactsContract.CommonDataKinds.*.TYPE_*` (0 is always TYPE_CUSTOM).
 */
internal class TypeSpec(
    val mime: String,
    /** Type used when a card says nothing (no TYPE, no label); also what a label-less TYPE_CUSTOM becomes. */
    val default: String?,
    /** Android code -> TYPE tokens written. Codes missing here (and != default) travel as X-PARLEY-DATA2. */
    val out: Map<Int, List<String>>,
    /** Tokens (lowercase, without "x-") -> Android code and optional label; null when nothing matched. */
    val parse: (Set<String>) -> Pair<Int, String?>?,
    /** Tokens that are understood (or deliberately ignored) and so never become a custom label. */
    val vocabulary: Set<String>,
    /** Apple system labels `_$!<Name>!$_` (lowercased inner name) -> Android code. */
    val apple: Map<String, Int> = emptyMap(),
) {
    /** Android code to write, or null to write no TYPE. */
    fun tokensFor(code: Int): List<String>? = out[code]

    /** Maps TYPE tokens plus an optional X-ABLabel to (DATA2, DATA3). */
    fun resolve(rawTokens: Collection<String>, label: String?): Pair<String?, String?> {
        if (label != null) {
            val sys = appleInner(label)
            if (sys == null) return "0" to label
            apple[sys.lowercase()]?.let { return it.toString() to null }
            return "0" to sys
        }
        val tokens = normalizeTokens(rawTokens)
        parse(tokens)?.let { (code, lbl) -> return code.toString() to lbl }
        val unknown = rawTokens.map { stripX(it.trim()) }.firstOrNull { it.isNotEmpty() && it.lowercase() !in vocabulary && it.lowercase() !in IGNORED }
        if (unknown != null) return "0" to unknown
        return default to null
    }

    companion object {
        /** TYPE tokens that carry no kind information. */
        val IGNORED = setOf("pref", "preferred", "internet", "x400", "voice", "dom", "intl", "postal", "parcel", "text", "uri")

        fun stripX(t: String) = if (t.length > 2 && t.startsWith("x-", ignoreCase = true)) t.substring(2) else t

        fun normalizeTokens(tokens: Collection<String>): Set<String> =
            tokens.flatMap { it.split(',') }.map { stripX(it.trim()).lowercase() }.filter { it.isNotEmpty() }.toSet()

        /** `_$!<Mobile>!$_` -> "Mobile"; null for a plain label. */
        fun appleInner(label: String): String? {
            val s = label.trim()
            return if (s.startsWith("_\$!<") && s.endsWith(">!\$_") && s.length > 8) s.substring(4, s.length - 4) else null
        }

        fun appleLabel(name: String) = "_\$!<$name>!\$_"
    }
}

internal object Types {
    private fun Set<String>.has(vararg t: String) = t.any { it in this }

    val PHONE = TypeSpec(
        Mime.PHONE, default = "7",
        out = mapOf(
            1 to listOf("home", "voice"), 2 to listOf("cell"), 3 to listOf("work", "voice"), 4 to listOf("work", "fax"),
            5 to listOf("home", "fax"), 6 to listOf("pager"), 7 to listOf("voice"), 8 to listOf("x-callback"), 9 to listOf("car"),
            10 to listOf("work", "x-company-main"), 11 to listOf("isdn"), 12 to listOf("main"), 13 to listOf("fax"),
            14 to listOf("x-radio"), 15 to listOf("x-telex"), 16 to listOf("textphone"), 17 to listOf("work", "cell"),
            18 to listOf("work", "pager"), 19 to listOf("x-assistant"), 20 to listOf("x-mms"),
        ),
        parse = { s ->
            when {
                s.has("fax") && s.has("work") -> 4
                s.has("fax") && s.has("home") -> 5
                s.has("fax", "homefax", "workfax", "otherfax") -> when {
                    s.has("workfax") -> 4; s.has("homefax") -> 5; else -> 13
                }
                s.has("pager") && s.has("work") -> 18
                s.has("pager") -> 6
                s.has("company-main", "companymain", "company_main") -> 10
                s.has("cell", "mobile", "iphone") && s.has("work") -> 17
                s.has("cell", "mobile", "iphone") -> 2
                s.has("callback") -> 8
                s.has("car") -> 9
                s.has("isdn") -> 11
                s.has("main") -> 12
                s.has("radio") -> 14
                s.has("telex", "tlx") -> 15
                s.has("textphone", "tty-tdd", "tty", "ttytdd", "tty_tdd") -> 16
                s.has("assistant") -> 19
                s.has("mms") -> 20
                s.has("work") -> 3
                s.has("home") -> 1
                s.has("other", "voice") -> 7
                else -> null
            }?.let { it to null }
        },
        vocabulary = setOf(
            "fax", "homefax", "workfax", "otherfax", "pager", "company-main", "companymain", "company_main", "cell", "mobile",
            "iphone", "callback", "car", "isdn", "main", "radio", "telex", "tlx", "textphone", "tty-tdd", "tty", "ttytdd",
            "tty_tdd", "assistant", "mms", "work", "home", "other", "video", "msg", "bbs", "modem", "pcs",
        ),
        apple = mapOf(
            "mobile" to 2, "iphone" to 2, "applewatch" to 2, "home" to 1, "work" to 3, "main" to 12, "homefax" to 5,
            "workfax" to 4, "otherfax" to 13, "pager" to 6, "other" to 7, "assistant" to 19, "callback" to 8, "car" to 9,
            "radio" to 14,
        ),
    )

    val EMAIL = TypeSpec(
        Mime.EMAIL, default = "3",
        out = mapOf(1 to listOf("home"), 2 to listOf("work"), 4 to listOf("x-mobile")),
        parse = { s ->
            when {
                s.has("mobile", "cell") -> 4
                s.has("work") -> 2
                s.has("home") -> 1
                s.has("other") -> 3
                else -> null
            }?.let { it to null }
        },
        vocabulary = setOf("mobile", "cell", "work", "home", "other", "aol", "applelink", "attmail", "cis", "eworld", "ibmmail", "mcimail", "powershare", "prodigy", "tlx"),
        apple = mapOf("home" to 1, "work" to 2, "other" to 3, "mobile" to 4),
    )

    val POSTAL = TypeSpec(
        Mime.POSTAL, default = "3",
        out = mapOf(1 to listOf("home"), 2 to listOf("work")),
        parse = { s ->
            when {
                s.has("work") -> 2
                s.has("home") -> 1
                s.has("other") -> 3
                else -> null
            }?.let { it to null }
        },
        vocabulary = setOf("work", "home", "other"),
        apple = mapOf("home" to 1, "work" to 2, "other" to 3),
    )

    val WEBSITE = TypeSpec(
        Mime.WEBSITE, default = "7",
        out = mapOf(
            1 to listOf("x-homepage"), 2 to listOf("x-blog"), 3 to listOf("x-profile"), 4 to listOf("home"),
            5 to listOf("work"), 6 to listOf("x-ftp"),
        ),
        parse = { s ->
            when {
                s.has("homepage", "home-page") -> 1
                s.has("blog") -> 2
                s.has("profile") -> 3
                s.has("ftp") -> 6
                s.has("work") -> 5
                s.has("home") -> 4
                s.has("other") -> 7
                else -> null
            }?.let { it to null }
        },
        vocabulary = setOf("homepage", "home-page", "blog", "profile", "ftp", "work", "home", "other"),
        apple = mapOf("homepage" to 1, "home" to 4, "work" to 5, "other" to 7),
    )

    private val IM_LIKE_OUT = mapOf(1 to listOf("home"), 2 to listOf("work"))
    private val IM_LIKE_PARSE: (Set<String>) -> Pair<Int, String?>? = { s ->
        when {
            s.has("work", "business") -> 2
            s.has("home", "personal") -> 1
            s.has("other") -> 3
            else -> null
        }?.let { it to null }
    }
    private val IM_LIKE_VOCAB = setOf("work", "business", "home", "personal", "other", "mobile")
    private val IM_LIKE_APPLE = mapOf("home" to 1, "work" to 2, "other" to 3)

    val IM = TypeSpec(Mime.IM, "3", IM_LIKE_OUT, IM_LIKE_PARSE, IM_LIKE_VOCAB, IM_LIKE_APPLE)
    val SIP = TypeSpec(Mime.SIP, "3", IM_LIKE_OUT, IM_LIKE_PARSE, IM_LIKE_VOCAB, IM_LIKE_APPLE)

    val NICKNAME = TypeSpec(
        Mime.NICKNAME, default = null,
        out = mapOf(2 to listOf("x-other-name"), 3 to listOf("x-maiden-name"), 4 to listOf("x-short-name"), 5 to listOf("x-initials")),
        parse = { s ->
            when {
                s.has("other-name", "othername") -> 2
                s.has("maiden-name", "maidenname", "maiden") -> 3
                s.has("short-name", "shortname", "short") -> 4
                s.has("initials") -> 5
                else -> null
            }?.let { it to null }
        },
        vocabulary = setOf("other-name", "othername", "maiden-name", "maidenname", "maiden", "short-name", "shortname", "short", "initials", "home", "work"),
    )

    val ORG = TypeSpec(
        Mime.ORG, default = null,
        out = mapOf(1 to listOf("work"), 2 to listOf("x-other")),
        parse = { s ->
            when {
                s.has("work") -> 1
                s.has("other") -> 2
                else -> null
            }?.let { it to null }
        },
        vocabulary = setOf("work", "other", "home"),
    )

    val RELATION = TypeSpec(
        Mime.RELATION, default = "0",
        out = mapOf(
            1 to listOf("agent"), 2 to listOf("sibling", "x-brother"), 3 to listOf("child"), 4 to listOf("x-domestic-partner"),
            5 to listOf("parent", "x-father"), 6 to listOf("friend"), 7 to listOf("x-manager"), 8 to listOf("parent", "x-mother"),
            9 to listOf("parent"), 10 to listOf("x-partner"), 11 to listOf("x-referred-by"), 12 to listOf("kin"),
            13 to listOf("sibling", "x-sister"), 14 to listOf("spouse"),
        ),
        parse = { s ->
            when {
                s.has("brother") -> 2 to null
                s.has("sister") -> 13 to null
                s.has("father") -> 5 to null
                s.has("mother") -> 8 to null
                s.has("assistant", "agent") -> 1 to null
                s.has("manager") -> 7 to null
                s.has("domestic-partner", "domesticpartner", "domestic_partner") -> 4 to null
                s.has("referred-by", "referredby", "referred_by") -> 11 to null
                s.has("partner") -> 10 to null
                s.has("spouse") -> 14 to null
                s.has("child") -> 3 to null
                s.has("parent") -> 9 to null
                s.has("friend") -> 6 to null
                s.has("kin", "relative") -> 12 to null
                s.has("sibling") -> 0 to "Sibling"
                else -> null
            }
        },
        vocabulary = setOf(
            "brother", "sister", "father", "mother", "assistant", "agent", "manager", "domestic-partner", "domesticpartner",
            "domestic_partner", "referred-by", "referredby", "referred_by", "partner", "spouse", "child", "parent", "friend",
            "kin", "relative", "sibling",
        ),
        apple = mapOf(
            "father" to 5, "mother" to 8, "parent" to 9, "brother" to 2, "sister" to 13, "child" to 3, "friend" to 6,
            "spouse" to 14, "partner" to 10, "assistant" to 1, "manager" to 7,
        ),
    )

    /** Event types: 0 custom, 1 anniversary, 2 other, 3 birthday. */
    val EVENT_APPLE = mapOf("anniversary" to 1, "other" to 2, "birthday" to 3)

    val BY_MIME: Map<String, TypeSpec> = listOf(PHONE, EMAIL, POSTAL, WEBSITE, IM, SIP, NICKNAME, ORG, RELATION).associateBy { it.mime }

    /** Default event type for dates that carry no type. */
    const val EVENT_DEFAULT = "2"

    // Instant-messaging protocols: Android Im.PROTOCOL_* -> (URI scheme, Apple X-SERVICE-TYPE name).
    val IM_PROTOCOLS: Map<Int, Pair<String, String>> = mapOf(
        0 to ("aim" to "AIM"), 1 to ("msnim" to "MSN"), 2 to ("ymsgr" to "Yahoo"), 3 to ("skype" to "Skype"),
        4 to ("x-qq" to "QQ"), 5 to ("xmpp" to "GoogleTalk"), 6 to ("icq" to "ICQ"), 7 to ("xmpp" to "Jabber"),
        8 to ("x-netmeeting" to "NetMeeting"),
    )

    fun imProtocolForService(name: String): Int? = when (name.lowercase().replace(" ", "").replace("-", "").replace("_", "")) {
        "aim" -> 0
        "msn", "msnim", "windowslive" -> 1
        "yahoo", "ymsgr" -> 2
        "skype" -> 3
        "qq" -> 4
        "googletalk", "gtalk", "hangouts", "google" -> 5
        "icq" -> 6
        "jabber", "xmpp" -> 7
        "netmeeting" -> 8
        else -> null
    }

    fun imProtocolForScheme(scheme: String): Int? = when (scheme.lowercase()) {
        "aim" -> 0
        "msn", "msnim" -> 1
        "ymsgr", "yahoo" -> 2
        "skype", "callto" -> 3
        "qq", "x-qq" -> 4
        "gtalk", "googletalk", "x-gtalk" -> 5
        "icq" -> 6
        "xmpp", "jabber" -> 7
        "x-netmeeting", "netmeeting" -> 8
        else -> null
    }

    /** Legacy vCard 2.1/3.0 IM properties (Android's own composer, Evolution, KDE). */
    val LEGACY_IM: Map<String, Int> = mapOf(
        "X-AIM" to 0, "X-MSN" to 1, "X-YAHOO" to 2, "X-SKYPE" to 3, "X-SKYPE-USERNAME" to 3, "X-QQ" to 4,
        "X-GOOGLE-TALK" to 5, "X-GTALK" to 5, "X-ICQ" to 6, "X-JABBER" to 7, "X-NETMEETING" to 8,
    )
}
