package app.parley.common.people

/**
 * I2: your own card ("Me"). Parley keeps its own copy (private to Parley, like the "My details" it replaces) and
 * shows it merged with Android's profile contact ("Me" in ContactsContract.Profile, readable with the contacts
 * permission Parley already has) when the phone has one.
 */
data class MeCard(
    val name: String = "",
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val company: String = "",
    val title: String = "",
    val websites: List<String> = emptyList(),
    val address: String = "",
    val note: String = "",
) {
    val isEmpty: Boolean
        get() = name.isBlank() && phones.all { it.isBlank() } && emails.all { it.isBlank() } && company.isBlank() && title.isBlank() &&
            websites.all { it.isBlank() } && address.isBlank() && note.isBlank()

    /** The number "Send my details" uses. */
    val firstNumber: String? get() = phones.firstOrNull { it.isNotBlank() }?.trim()

    fun cleaned(): MeCard = MeCard(
        name.trim(),
        phones.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        emails.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
        company.trim(), title.trim(),
        websites.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        address.trim(), note.trim(),
    )
}

object MeCards {
    /** Parley's card from the old "My details" (name and number), so nothing typed there is lost. */
    fun fromMyDetails(name: String, number: String): MeCard = MeCard(name = name.trim(), phones = listOfNotNull(number.trim().ifEmpty { null }))

    /**
     * What "Me" shows: Parley's own card, completed with the phone's profile. Parley's fields win (they're what you
     * typed in Parley); lists are joined without duplicates (numbers compared by digits, e-mails ignoring case).
     */
    fun merge(own: MeCard?, profile: MeCard?): MeCard {
        val a = own?.cleaned() ?: MeCard()
        val b = profile?.cleaned() ?: return a
        fun pick(x: String, y: String) = x.ifBlank { y }
        return MeCard(
            name = pick(a.name, b.name),
            phones = (a.phones + b.phones).distinctBy { p -> p.filter { it.isDigit() }.ifEmpty { p } },
            emails = (a.emails + b.emails).distinctBy { it.lowercase() },
            company = pick(a.company, b.company),
            title = pick(a.title, b.title),
            websites = (a.websites + b.websites).distinct(),
            address = pick(a.address, b.address),
            note = a.note,
        )
    }

    /** Fields that can be left out when sharing. */
    enum class Part { NAME, PHONES, EMAILS, WORK, WEBSITES, ADDRESS }

    /**
     * A vCard 3.0 for sharing or a QR code (3.0 is what camera apps read most reliably). [parts] chooses what goes
     * in; the private note never does.
     */
    fun vcard(card: MeCard, parts: Set<Part> = Part.entries.toSet()): String = buildString {
        val c = card.cleaned()
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        val name = if (Part.NAME in parts) c.name else ""
        val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val given = words.dropLast(1).joinToString(" ").ifEmpty { words.firstOrNull().orEmpty() }
        val family = if (words.size > 1) words.last() else ""
        append("N:${esc(family)};${esc(given)};;;\r\n")
        append("FN:${esc(name.ifEmpty { c.company.takeIf { Part.WORK in parts }.orEmpty() })}\r\n")
        if (Part.PHONES in parts) c.phones.forEach { append("TEL;TYPE=CELL:${esc(it)}\r\n") }
        if (Part.EMAILS in parts) c.emails.forEach { append("EMAIL:${esc(it)}\r\n") }
        if (Part.WORK in parts) {
            if (c.company.isNotEmpty()) append("ORG:${esc(c.company)}\r\n")
            if (c.title.isNotEmpty()) append("TITLE:${esc(c.title)}\r\n")
        }
        if (Part.WEBSITES in parts) c.websites.forEach { append("URL:${esc(it)}\r\n") }
        if (Part.ADDRESS in parts && c.address.isNotEmpty()) append("ADR:;;${esc(c.address)};;;;\r\n")
        append("END:VCARD\r\n")
    }

    /** vCard 3.0 text escaping (backslash, comma, semicolon, newline). */
    fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r\n", "\\n").replace("\n", "\\n")
}
