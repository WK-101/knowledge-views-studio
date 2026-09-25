package app.parley.common

/**
 * One row of keypad results. A contact with several numbers gives one main row (the number to dial: the one that
 * matched, otherwise the default number) followed by [secondary] rows for its other numbers.
 */
data class DialHit(
    val contact: ContactSummary?,
    val number: String,
    val match: T9.Match,
    /** Another number of the contact in the row above. */
    val secondary: Boolean = false,
    /** This is the contact's default ("primary") number and the contact has several. */
    val primary: Boolean = false,
)

/**
 * Keypad search over contacts and recent numbers.
 *
 * Keeps the candidates of the previous query: when the next query only adds digits (the usual case while typing) and
 * the contact list is unchanged, only those candidates are tested again. This keeps typing smooth with thousands of
 * contacts on slow phones. Not thread-safe: use one instance from one coroutine at a time.
 */
class DialSearch {
    class Entry(val contact: ContactSummary, val encoded: T9.Encoded)

    private var lastEntries: List<Entry>? = null
    private var lastQuery = ""
    private var lastCandidates: IntArray? = null

    private var recencyCalls: List<CallEntry>? = null
    private var recency: Map<String, Long> = emptyMap()

    /** For tests: how many entries the last search tested. */
    var lastScanned = 0
        private set

    /**
     * @param input what's typed: keypad digits, or letters typed on a hardware keyboard (searched as text).
     * @param calls newest first, for ranking people you call and for recent unknown numbers.
     */
    fun search(input: String, entries: List<Entry>, calls: List<CallEntry>?, now: Long = System.currentTimeMillis(), limit: Int = 50): List<DialHit> {
        val text = input.trim()
        if (text.any { it.isLetter() }) {
            reset()
            return textSearch(text, entries, calls, now, limit)
        }
        val q = PhoneNumbers.clean(input).removePrefix("+")
        if (q.isEmpty() || q.any { it == '*' || it == '#' }) {
            reset()
            return emptyList()
        }
        val narrowFrom = lastCandidates?.takeIf {
            entries === lastEntries && lastQuery.length >= NARROW_FROM && q.length > lastQuery.length && q.startsWith(lastQuery)
        }
        val lastCalled = recencyMap(calls)
        val scored = ArrayList<Pair<Int, T9.Match>>()
        fun test(i: Int) {
            val e = entries[i]
            val m = T9.match(q, e.encoded, e.contact.phones.map { it.number }) ?: return
            scored += i to m.copy(score = m.score + recencyBonus(e.contact, lastCalled, now) + if (e.contact.starred) 15 else 0)
        }
        if (narrowFrom != null) {
            narrowFrom.forEach(::test)
            lastScanned = narrowFrom.size
        } else {
            entries.indices.forEach(::test)
            lastScanned = entries.size
        }
        lastEntries = entries
        lastQuery = q
        lastCandidates = scored.map { it.first }.toIntArray()

        scored.sortByDescending { it.second.score }
        val out = ArrayList<DialHit>()
        for ((i, m) in scored) {
            if (out.size >= limit) break
            expand(entries[i].contact, m, out)
        }
        addRecentNumbers(q, out, calls)
        return out.take(limit)
    }

    private fun reset() {
        lastEntries = null
        lastQuery = ""
        lastCandidates = null
    }

    /** Letters typed on a QWERTY keyboard: plain accent- and case-insensitive name search. */
    private fun textSearch(text: String, entries: List<Entry>, calls: List<CallEntry>?, now: Long, limit: Int): List<DialHit> {
        val q = TextSearch.normalize(text)
        val lastCalled = recencyMap(calls)
        val scored = ArrayList<Pair<Entry, T9.Match>>()
        for (e in entries) {
            val name = e.contact.displayName
            val norm = TextSearch.normalize(name)
            // Normalising can drop combining marks; only highlight when the lengths still line up.
            val at = norm.indexOf(q)
            if (at < 0) {
                if (!TextSearch.matches(text, name)) continue
                scored += e to T9.Match(600 + recencyBonus(e.contact, lastCalled, now), emptyList(), null)
                continue
            }
            val wordStart = at == 0 || !norm[at - 1].isLetterOrDigit()
            val ranges = if (norm.length == name.length) listOf(at until at + q.length) else emptyList()
            scored += e to T9.Match((if (wordStart) 1000 else 700) - (if (at == 0) 0 else 10) + recencyBonus(e.contact, lastCalled, now), ranges, null)
        }
        scored.sortByDescending { it.second.score }
        val out = ArrayList<DialHit>()
        for ((e, m) in scored) {
            if (out.size >= limit) break
            expand(e.contact, m, out)
        }
        return out.take(limit)
    }

    private fun recencyMap(calls: List<CallEntry>?): Map<String, Long> {
        if (calls !== recencyCalls) {
            val m = HashMap<String, Long>()
            calls.orEmpty().take(1500).forEach { e -> if (e.number.isNotBlank()) m.putIfAbsent(PhoneNumbers.matchKey(e.number), e.date) }
            recency = m
            recencyCalls = calls
        }
        return recency
    }

    /** People you called recently rank higher among equally good matches. */
    private fun recencyBonus(contact: ContactSummary, lastCalled: Map<String, Long>, now: Long): Int {
        val newest = contact.phones.mapNotNull { lastCalled[PhoneNumbers.matchKey(it.number)] }.maxOrNull() ?: return 0
        val days = (now - newest) / 86_400_000L
        return (60 - days * 2).coerceIn(0, 60).toInt()
    }

    private fun addRecentNumbers(q: String, out: MutableList<DialHit>, calls: List<CallEntry>?) {
        val seen = out.flatMap { r -> r.contact?.phones.orEmpty().map { PhoneNumbers.matchKey(it.number) } }.toHashSet()
        calls.orEmpty().asSequence()
            .filter { it.number.isNotBlank() && !it.presentationHidden }
            .distinctBy { PhoneNumbers.matchKey(it.number) }
            .filter { PhoneNumbers.matchKey(it.number) !in seen && PhoneNumbers.digits(it.number).contains(q) }
            .take(5)
            .forEach { out += DialHit(null, it.number, T9.Match(400, emptyList(), it.number)) }
    }

    companion object {
        /** Below this length a longer query can match entries the shorter one didn't (substring rules start at 3). */
        const val NARROW_FROM = 3

        /**
         * A contact's numbers in the order they are offered: the matched number first, otherwise the default
         * (super-primary or primary) number, then the rest in their stored order.
         */
        fun orderedPhones(contact: ContactSummary, matched: String?): List<PhoneEntry> {
            val phones = contact.phones
            if (phones.size < 2) return phones
            val first = phones.firstOrNull { matched != null && it.number == matched } ?: phones.firstOrNull { it.isPrimary } ?: phones.first()
            return listOf(first) + phones.filter { it !== first }
        }

        /** Rows for one matching contact: the number to dial, then its other numbers. */
        fun expand(contact: ContactSummary, m: T9.Match, out: MutableList<DialHit>) {
            val phones = orderedPhones(contact, m.matchedNumber)
            if (phones.isEmpty()) return
            val several = phones.size > 1
            phones.forEachIndexed { i, p ->
                out += DialHit(
                    contact, p.number,
                    if (i == 0) m else m.copy(nameRanges = emptyList(), matchedNumber = null),
                    secondary = i > 0,
                    primary = several && p.isPrimary,
                )
            }
        }

        /** Number to put back on the keypad when Call is pressed with nothing typed: the last one you called. */
        fun lastOutgoing(calls: List<CallEntry>?): String? =
            calls?.firstOrNull { it.type == CallType.OUTGOING && it.number.isNotBlank() && !it.presentationHidden }?.number
    }
}

/** Text handling for the keypad's number field. */
object DialText {
    /**
     * Keeps what can be dialled from pasted text: digits (any script, as 0–9), a leading "+" (or one after `*`), `*`, `#`, and the
     * pause/wait characters `,` and `;`. "Tel: +1 (555) 123-4567" becomes "+15551234567".
     */
    fun sanitize(text: String): String = buildString {
        for (c in text) {
            val d = T9.asciiDigit(c)
            when {
                d != null -> append(d)
                // P9: also right after a '*', for the number in a forwarding code ("**21*+4915112345678#").
                c == '+' -> if (isEmpty() || last() == '*') append(c)
                c == '*' || c == '#' || c == ',' || c == ';' -> append(c)
            }
        }
    }

    /**
     * Where the characters that [formatted] adds to [raw] go, as (index in raw, character) pairs, so a formatted
     * number ("06 12 34") can be shown while the cursor still moves over the typed digits. Null when [formatted]
     * isn't [raw] plus formatting characters.
     */
    fun formattingInserts(raw: String, formatted: String): List<Pair<Int, Char>>? {
        if (formatted == raw) return emptyList()
        val out = ArrayList<Pair<Int, Char>>()
        var i = 0
        for (ch in formatted) {
            if (i < raw.length && ch == raw[i]) {
                i++
            } else if (ch.isDigit() || ch == '+' || ch == '*' || ch == '#') {
                return null
            } else {
                out += i to ch
            }
        }
        return if (i == raw.length) out else null
    }
}
