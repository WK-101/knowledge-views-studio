package app.parley.common

/** What one messenger row in the contacts database does when opened. */
enum class ReachKind {
    /** Opens the chat. */
    MESSAGE,
    /** Starts a voice call in the app. */
    VOICE,
    /** Starts a video call in the app. */
    VIDEO,
    /** A call to the ordinary phone network that the app bills (Viber Out). Never offered as a free call. */
    PAID_CALL,
    /** Something else (a profile page, a "view in app" row). */
    OTHER,
}

/** One recognised messenger row: which app ([appKey] is its account type), what it does, and for which number. */
data class ReachRow(
    val dataId: Long,
    val mimeType: String,
    /** The account type that owns the row: the key preferences are stored under (see MessengerPrefs). */
    val appKey: String,
    val app: ReachApp?,
    val appLabel: String,
    val kind: ReachKind,
    /** The number this row reaches, as the app wrote it; null when it can't be told. */
    val number: String?,
    /** The app's own wording ("Signal Voice Call +1 555…"), for TalkBack and the full list. */
    val label: String,
)

/**
 * Recognises messenger rows by mimetype instead of guessing from localised labels. Exact mimetypes come from
 * the apps' own manifests and contacts structure files where they are public (Signal, Molly, Telegram, Threema) and
 * from what the apps are known to write (WhatsApp, Viber, Meet); anything else an app namespaces under its own
 * account type is classified by the words in its mimetype.
 */
object MessengerMimes {
    const val ITEM = "vnd.android.cursor.item/"

    /** Known mimetypes and what they do, from [MessengerCatalog]. */
    val KNOWN: Map<String, ReachKind> = MessengerCatalog.entries.flatMap { e -> e.mimes.map { (m, k) -> ITEM + m to k } }.toMap()

    /** The platform's own kinds (names, numbers, e-mail…), which are never messenger rows. */
    private val PLATFORM = setOf(
        "name", "phone_v2", "email_v2", "postal-address_v2", "im", "organization", "nickname", "note", "website",
        "photo", "group_membership", "contact_event", "relation", "sip_address", "identity", "phone", "email",
    )

    /** Account types whose extra rows are never chats (Google, Samsung, Exchange…). */
    private val NOT_MESSENGERS = setOf("com.google", "com.osp.app.signin", "vnd.sec.contact.phone", "com.android.exchange", "com.google.android.gm.exchange")

    /** What [mime] does, from the table or the words in it; null when it isn't an app's own row at all. */
    fun kind(mime: String): ReachKind? {
        KNOWN[mime]?.let { return it }
        if (!mime.startsWith(ITEM)) return null
        val sub = mime.substring(ITEM.length).lowercase()
        if (sub in PLATFORM) return null
        return when {
            "out_call" in sub || "viberout" in sub || "skypeout" in sub -> ReachKind.PAID_CALL
            "video" in sub -> ReachKind.VIDEO
            "call" in sub || "voip" in sub || "audio" in sub -> ReachKind.VOICE
            "profile" in sub || "message" in sub || "chat" in sub || "conversation" in sub || sub.endsWith(".contact") -> ReachKind.MESSAGE
            else -> ReachKind.OTHER
        }
    }

    /**
     * Whether a row of [mime] in account [accountType] is a messenger's row. Known apps by account type or
     * mimetype; any other app only when the mimetype is namespaced with its own account type (as Android asks apps
     * to do), so a sync adapter's unrelated kinds are never mistaken for chats.
     */
    fun isMessengerRow(mime: String, accountType: String?): Boolean {
        if (kind(mime) == null) return false
        if (ReachApp.forAccountType(accountType) != null || ReachApp.forMime(mime) != null) return true
        val t = accountType?.lowercase()?.takeIf { it.isNotBlank() && '.' in it && it !in NOT_MESSENGERS } ?: return false
        val sub = mime.substring(ITEM.length).lowercase()
        return sub.startsWith("$t.") || sub.startsWith("vnd.$t.")
    }

    /**
     * Classifies one data row, or null when it isn't a messenger's row. [data2] is the app's name (Signal) or a
     * summary, [data3] its prompt ("Message +1 555…"), used for the label. [phoneOfRaw] is the number on the same raw
     * contact, when it has exactly one: the most reliable way to tell which number the row is for.
     */
    fun classify(
        dataId: Long, mime: String, accountType: String?, data1: String?, data2: String?, data3: String?, phoneOfRaw: String? = null,
    ): ReachRow? {
        if (!isMessengerRow(mime, accountType)) return null
        val kind = kind(mime) ?: return null
        val app = ReachApp.forAccountType(accountType) ?: ReachApp.forMime(mime)
        val key = accountType?.takeIf { it.isNotBlank() } ?: app?.packageName ?: return null
        val appLabel = app?.label ?: data2?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: key
        val label = data3?.trim()?.takeIf { it.isNotEmpty() } ?: data2?.trim()?.takeIf { it.isNotEmpty() } ?: appLabel
        return ReachRow(dataId, mime, key, app, appLabel, kind, phoneOfRaw ?: numberFrom(app, data1, data3), label)
    }

    /**
     * The number a row is for, from its own columns: DATA1 for apps that store the number there (Signal and Molly
     * the number, WhatsApp its "15551234567@s.whatsapp.net" id, Viber the number), otherwise a "+…" number in the
     * prompt. Telegram's and Threema's DATA1 are user ids, never numbers.
     */
    fun numberFrom(app: ReachApp?, data1: String?, data3: String?): String? {
        val direct = app in setOf(ReachApp.SIGNAL, ReachApp.MOLLY, ReachApp.WHATSAPP, ReachApp.WHATSAPP_BUSINESS, ReachApp.VIBER)
        if (direct && data1 != null) {
            val v = data1.substringBefore('@').trim()
            val digits = v.count { it.isDigit() }
            if (digits in 7..15 && v.all { it.isDigit() || it in "+ -(). " }) {
                return if (v.startsWith("+") || '@' !in data1) v else "+$v"
            }
        }
        return data3?.let { plusNumber(it) }
    }

    /** The first "+" number (7 to 15 digits, spaces and dashes allowed) in [text]. */
    internal fun plusNumber(text: String): String? {
        val i = text.indexOf('+')
        if (i < 0) return null
        val sb = StringBuilder("+")
        var j = i + 1
        while (j < text.length && (text[j].isDigit() || text[j] in " -(). ")) {
            if (text[j].isDigit()) sb.append(text[j])
            j++
        }
        return sb.toString().takeIf { it.length - 1 in 7..15 }
    }
}

/** One app's ways to reach a person (on one number): each is null when the app didn't add that row. */
data class ReachGroup(
    val appKey: String,
    val app: ReachApp?,
    val appLabel: String,
    val message: ReachRow?,
    val voice: ReachRow?,
    val video: ReachRow?,
    /** The number these rows are for; null when the app didn't say. */
    val number: String?,
) {
    val canCall: Boolean get() = voice != null || video != null
}

object ReachGroups {
    /**
     * Groups [rows] by app and number, known apps in their usual order then others by name. [same] tells whether two
     * numbers are the same line (the caller reads them with the phone's country). A paid row (Viber Out) and
     * "other" rows never make it into a group: they're not a free way to talk.
     */
    fun group(rows: List<ReachRow>, same: (String, String) -> Boolean = { a, b -> PhoneNumbers.matchKey(a) == PhoneNumbers.matchKey(b) }): List<ReachGroup> {
        val useful = rows.filter { it.kind == ReachKind.MESSAGE || it.kind == ReachKind.VOICE || it.kind == ReachKind.VIDEO }
        val out = ArrayList<ReachGroup>()
        for ((key, list) in useful.groupBy { it.appKey }) {
            val clusters = ArrayList<Pair<String?, MutableList<ReachRow>>>()
            for (r in list) {
                val n = r.number ?: continue
                val c = clusters.firstOrNull { it.first != null && same(it.first!!, n) }
                if (c != null) c.second += r else clusters += n to mutableListOf(r)
            }
            // Rows that don't say their number belong with every number of that app (numbered rows win).
            val loose = list.filter { it.number == null }
            if (loose.isNotEmpty()) {
                if (clusters.isEmpty()) clusters += null to loose.toMutableList() else clusters.forEach { it.second += loose }
            }
            for ((number, rs) in clusters) {
                val first = rs.first()
                out += ReachGroup(
                    appKey = key, app = first.app, appLabel = first.appLabel,
                    message = rs.firstOrNull { it.kind == ReachKind.MESSAGE },
                    voice = rs.firstOrNull { it.kind == ReachKind.VOICE },
                    video = rs.firstOrNull { it.kind == ReachKind.VIDEO },
                    number = number,
                )
            }
        }
        return out.sortedWith(compareBy({ it.app?.ordinal ?: Int.MAX_VALUE }, { it.appLabel.lowercase() }))
    }

    /** The groups for [number]: those for that line, and those that didn't say which number they're for. */
    fun forNumber(groups: List<ReachGroup>, number: String?, same: (String, String) -> Boolean): List<ReachGroup> =
        if (number == null) groups else groups.filter { it.number == null || same(it.number, number) }
}

/** How "Call on <app>" works for a number. */
sealed interface CallRoute {
    /** The app added a call row for this number (a saved contact it has linked): opening it starts the call. */
    data class Row(val row: ReachRow) : CallRoute

    /**
     * No call row: open the chat by number, where the call buttons are. WhatsApp, Signal, Telegram and Viber publish
     * no link that starts a call to a phone number, so Parley never claims to start one.
     */
    data class ViaChat(val app: MessengerApp) : CallRoute
}

object CallRoutes {
    /**
     * The route for a [video] or voice call with the installed chat [app], given the [rows] recognised for the
     * number. WhatsApp and WhatsApp Business are told apart by account type, as are Signal and Molly.
     */
    fun forApp(app: MessengerApp, video: Boolean, rows: List<ReachRow>): CallRoute {
        val kind = if (video) ReachKind.VIDEO else ReachKind.VOICE
        val row = rows.firstOrNull { it.kind == kind && (it.appKey == app.packageName || it.app == app.entry) }
        return if (row != null) CallRoute.Row(row) else CallRoute.ViaChat(app)
    }

    /** Whether [app] can make video calls at all (for the via-chat hint). All four chat apps do. */
    fun hasVideo(app: MessengerApp): Boolean = when (app.messenger) {
        Messenger.WHATSAPP, Messenger.SIGNAL, Messenger.TELEGRAM, Messenger.VIBER -> true
    }
}

/**
 * Which messenger rows belong to a person. Numbers are compared with [PhoneNumbers.sameExact] (never
 * the last-digits fallback), so on a phone without a SIM country another person's messenger rows can't match.
 */
object MessengerRowMatch {
    /** A row from a messenger-only contact: kept only when it carries one of the person's [ownPhones]. */
    fun extraRow(rowNumber: String?, ownPhones: List<String>, region: String?): Boolean =
        rowNumber != null && ownPhones.any { PhoneNumbers.sameExact(it, rowNumber, region) }

    /**
     * A row found for [number]: a row with a number must carry [number]; a row without one is kept only when the
     * contact it came from ([contactPhones]) has [number] itself.
     */
    fun forNumber(rowNumber: String?, contactPhones: List<String>, number: String, region: String?): Boolean =
        if (rowNumber != null) PhoneNumbers.sameExact(rowNumber, number, region)
        else contactPhones.any { PhoneNumbers.sameExact(it, number, region) }
}

/** One row under "Call on" in the "Message or call on…" sheet. */
sealed interface CallOnEntry {
    /** An app that added call rows for this number: its Voice and Video buttons open those rows. */
    data class Direct(val group: ReachGroup) : CallOnEntry

    /** An installed chat app without a call row for this number: its chat opens, where the call button is. */
    data class ViaChat(val app: MessengerApp) : CallOnEntry
}

/**
 * What the "Message or call on…" sheet lists, the same way for a saved person, a private contact and an unsaved
 * number, so the sheet has one layout everywhere.
 */
object ReachPlan {
    /** One installed chat app per catalog entry, the one used last first. */
    fun chatApps(installed: List<MessengerApp>, lastUsed: String?): List<MessengerApp> =
        MessengerApp.onePerApp(installed).sortedByDescending { lastUsed != null && (it.packageName == lastUsed || lastUsed in it.entry.accountTypes) }

    /** Whether [group] (an app's rows) belongs to the installed chat [app]: same account type, or same catalog entry. */
    fun belongsTo(group: ReachGroup, app: MessengerApp): Boolean = group.appKey == app.packageName || group.app == app.entry

    /**
     * "Call on" rows: each chat app in [chatApps] with its own call rows for the number when it added some, otherwise
     * through its chat; then the call rows of other apps (Threema, Meet…). [groups] are already for the chosen
     * number. Groups without a voice or video row are left out.
     */
    fun callOn(chatApps: List<MessengerApp>, groups: List<ReachGroup>): List<CallOnEntry> {
        val callable = groups.filter { it.canCall }
        val out = ArrayList<CallOnEntry>()
        val used = HashSet<ReachGroup>()
        for (app in chatApps) {
            val own = callable.firstOrNull { it !in used && belongsTo(it, app) }
            if (own != null) {
                used += own
                out += CallOnEntry.Direct(own)
            } else {
                out += CallOnEntry.ViaChat(app)
            }
        }
        val chatEntries = chatApps.map { it.entry }.toSet()
        callable.filter { it !in used && it.app !in chatEntries }.distinctBy { it.appKey to it.number }.forEach { out += CallOnEntry.Direct(it) }
        return out
    }

    /**
     * The account type under which [app] has a chat row for this person ([linked]: account types with a message
     * row), or null when it hasn't linked them (the chat then opens by number).
     */
    fun linkedKey(app: MessengerApp, linked: Set<String>): String? =
        app.packageName.takeIf { it in linked } ?: app.entry.accountTypes.firstOrNull { it in linked }
}
