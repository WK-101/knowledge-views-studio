package app.parley.common.people

import app.parley.common.people.PasteParser.Label
import java.text.Normalizer
import java.util.Locale

/**
 * The words "Paste details" knows: field labels ("Tel.", "Mobil", "Adresse"), job titles, company forms, sign-offs and
 * the countries, in English and the main European languages (plus a few Arabic and Hebrew ones). Matching is on
 * [fold]ed text, so case and accents don't matter.
 */
internal object PasteWords {
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case, accents removed ("Téléphone" → "telephone", "Straße" → "strasse"). */
    fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD), "").replace("ß", "ss")

    /** The folded words of [s], punctuation dropped. */
    fun words(s: String): List<String> = NON_WORD.split(fold(s)).filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- labels before a value ("Tel.:", "Company:")

    sealed interface Tag {
        data object Name : Tag
        data object Org : Tag
        data object Title : Tag
        data object Address : Tag
        data object Birthday : Tag
        data object Note : Tag
        data object Email : Tag
        data object Web : Tag
        data class Phone(val label: Label) : Tag
        data class Social(val service: ProfileService) : Tag
    }

    /** Phone labels, folded, without their dots: the type they give (NONE: "Tel.", the number's own kind decides). */
    val PHONE_LABELS: Map<String, Label> = buildMap {
        listOf(
            "m", "mob", "mobile", "mobile phone", "cell", "cellphone", "cell phone", "c", "handy", "mobil", "mobilnummer", "mobiel",
            "portable", "port", "movil", "cel", "celular", "cellulare", "gsm", "whatsapp", "wa", "telemovel", "mobilny", "mobiltelefon",
        ).forEach { put(it, Label.MOBILE) }
        listOf(
            "t", "tel", "tel no", "tele", "phone", "phone no", "p", "ph", "pho", "telephone", "telefon", "telefono", "tfno", "telf", "telefoon", "fon",
            "tlf", "tlph", "call", "numero", "number", "no", "voice", "landline",
        ).forEach { put(it, Label.NONE) }
        listOf(
            "o", "off", "office", "w", "work", "direct", "d", "dd", "dir", "direct line", "direct dial", "business", "buro", "bureau", "ufficio",
            "oficina", "kantoor", "durchwahl", "work phone", "office phone",
        ).forEach { put(it, Label.WORK) }
        listOf("h", "home", "privat", "private", "domicile", "casa", "thuis", "home phone").forEach { put(it, Label.HOME) }
        listOf("main", "switchboard", "zentrale", "reception", "standard", "hq", "general", "toll free", "freephone", "hotline").forEach { put(it, Label.MAIN) }
        listOf("f", "fax", "telefax", "facsimile", "fax no").forEach { put(it, Label.FAX) }
    }

    private val TAGS: Map<String, Tag> = buildMap {
        PHONE_LABELS.forEach { (k, v) -> if (k.length > 1) put(k, Tag.Phone(v)) }
        listOf("name", "full name", "contact", "contact person", "from", "sender", "nom", "nombre", "nome", "naam", "ansprechpartner", "ansprechpartnerin")
            .forEach { put(it, Tag.Name) }
        listOf(
            "company", "organisation", "organization", "org", "firm", "employer", "business name", "firma", "unternehmen", "societe", "entreprise",
            "empresa", "azienda", "societa", "bedrijf",
        ).forEach { put(it, Tag.Org) }
        listOf(
            "title", "job title", "position", "role", "job", "designation", "occupation", "funktion", "position/title", "poste", "fonction",
            "cargo", "puesto", "ruolo", "functie",
        ).forEach { put(it, Tag.Title) }
        listOf(
            "address", "addr", "adresse", "anschrift", "direccion", "indirizzo", "adres", "endereco", "location", "office address", "postal address",
            "visiting address", "headquarters", "hq address", "standort", "sede",
        ).forEach { put(it, Tag.Address) }
        listOf(
            "birthday", "date of birth", "dob", "born", "bday", "b day", "geburtstag", "geburtsdatum", "geb", "anniversaire", "date de naissance",
            "cumpleanos", "fecha de nacimiento", "compleanno", "data di nascita", "verjaardag", "geboortedatum", "aniversario",
        ).forEach { put(it, Tag.Birthday) }
        listOf("note", "notes", "comment", "comments", "notiz", "remarque", "nota").forEach { put(it, Tag.Note) }
        listOf("e", "email", "e mail", "mail", "e mail address", "email address", "courriel", "correo", "correo electronico").forEach { put(it, Tag.Email) }
        listOf("web", "website", "web site", "site", "homepage", "url", "www", "internet", "webseite", "sitio web", "sito").forEach { put(it, Tag.Web) }
        ProfileService.entries.forEach { s -> put(fold(s.label), Tag.Social(s)); put(s.key, Tag.Social(s)) }
        mapOf(
            "twitter" to ProfileService.X, "x" to ProfileService.X, "ig" to ProfileService.INSTAGRAM, "insta" to ProfileService.INSTAGRAM,
            "fb" to ProfileService.FACEBOOK, "li" to ProfileService.LINKEDIN, "bsky" to ProfileService.BLUESKY, "twitter x" to ProfileService.X,
            "x twitter" to ProfileService.X, "fediverse" to ProfileService.MASTODON, "tik tok" to ProfileService.TIKTOK, "yt" to ProfileService.YOUTUBE,
        ).forEach { (k, v) -> put(k, Tag.Social(v)) }
    }

    /** What a label before a colon names, or null when it isn't one Parley knows. */
    fun tag(label: String): Tag? = TAGS[words(label).joinToString(" ")]

    /** A phone label in front of a number: the words just before it, when they are all label words. */
    fun phoneLabel(before: String): Pair<Label, Boolean>? {
        val w = words(before)
        if (w.isEmpty() || w.size > 3) return null
        val joined = w.joinToString(" ")
        PHONE_LABELS[joined]?.let { return it to true }
        if (!w.all { it in PHONE_LABELS }) return null
        // "Mobile phone", "Tel. direct": the most specific word wins.
        val labels = w.mapNotNull { PHONE_LABELS[it] }
        return (labels.firstOrNull { it != Label.NONE } ?: Label.NONE) to true
    }

    // ---------------------------------------------------------------- lines that carry nothing

    private val SIGN_OFF = Regex(
        "^(?:best|best wishes|kind regards|warm regards|warmest regards|best regards|regards|with best regards|with kind regards|many thanks|" +
            "thanks|thank you|thanks again|cheers|yours|yours sincerely|yours truly|yours faithfully|sincerely|all the best|talk soon|br|" +
            "mfg|mit freundlichen grussen|freundliche grusse|viele grusse|beste grusse|liebe grusse|herzliche grusse|lg|vg|" +
            "cordialement|bien cordialement|bien a vous|salutations|sinceres salutations|saludos|un saludo|saludos cordiales|atentamente|" +
            "cordiali saluti|distinti saluti|saluti|met vriendelijke groet|met vriendelijke groeten|groeten|vriendelijke groet|atenciosamente|abracos|" +
            "hello|hi|hey|dear all|hello my name is|my name is|hi i am|hi i'm|contact|contact us|contact me|get in touch|contact information|" +
            "contact info|contact details|our details|business card|kontakt|impressum|contacto|contatti|nous contacter|contactez nous|" +
            "follow us|follow me|connect with me|connect with us|find us|visit us|visit our website|reach us|reach me|call us|email us|" +
            "sent from my .*|sent from .*|get outlook for .*|von meinem .* gesendet|envoye de mon .*|enviado desde mi .*|inviato da .*|" +
            "verzonden vanaf .*|this message was sent .*)[\\s,.!:;-]*$",
    )
    private val RULE = Regex("^[\\s\\-_=*~#.·•|]+$")
    private val DISCLAIMER = Regex(
        "confidential|disclaimer|intended recipient|privileged|unauthori[sz]ed|virus|vertraulich|confidentiel|riservat|" +
            "consider the environment|before printing|think before you print|bitte denken sie an die umwelt",
    )

    /** Sign-offs, greetings, headings, rules and disclaimers: dropped, not kept as a note. */
    fun isJunk(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || RULE.matches(t)) return true
        val f = fold(t).replace('’', '\'')
        if (SIGN_OFF.matches(f)) return true
        return t.length > DISCLAIMER_MIN && DISCLAIMER.containsMatchIn(f)
    }

    private const val DISCLAIMER_MIN = 60

    // ---------------------------------------------------------------- job titles and organisations

    private val TITLE_WORDS = setOf(
        "ceo", "cto", "cfo", "coo", "cmo", "cio", "cpo", "cso", "cro", "cdo", "vp", "svp", "evp", "avp", "founder", "cofounder", "co-founder",
        "owner", "president", "chair", "chairman", "chairwoman", "chairperson", "director", "manager", "lead", "engineer", "developer",
        "programmer", "designer", "architect", "consultant", "advisor", "adviser", "analyst", "associate", "partner", "principal", "officer",
        "executive", "specialist", "coordinator", "administrator", "assistant", "secretary", "treasurer", "accountant", "attorney", "lawyer",
        "solicitor", "barrister", "counsel", "paralegal", "notary", "professor", "lecturer", "teacher", "researcher", "scientist",
        "physician", "surgeon", "nurse", "dentist", "pharmacist", "therapist", "psychologist", "editor", "writer", "journalist", "reporter",
        "producer", "photographer", "agent", "broker", "realtor", "recruiter", "representative", "intern", "trainee", "ambassador",
        "evangelist", "strategist", "planner", "supervisor", "technician", "receptionist", "salesperson", "speaker", "panelist", "moderator",
        "organiser", "organizer", "chef", "pilot", "translator", "interpreter", "curator", "coach", "trainer", "mentor", "head",
        "geschaftsfuhrer", "geschaftsfuhrerin", "leiter", "leiterin", "beraterin", "berater", "ingenieur", "ingenieurin", "entwickler",
        "entwicklerin", "referent", "referentin", "inhaber", "inhaberin", "vorstand", "prokurist", "assistentin", "assistent", "sachbearbeiter",
        "sachbearbeiterin", "directeur", "directrice", "gerant", "gerante", "responsable", "conseiller", "conseillere",
        "fondateur", "fondatrice", "presidente", "commercial", "avocat", "avocate", "directora", "gerente", "jefe", "jefa",
        "ingeniero", "ingeniera", "abogado", "abogada", "asesor", "asesora", "fundador", "fundadora", "consultor", "consultora", "direttore",
        "direttrice", "responsabile", "ingegnere", "avvocato", "consulente", "amministratore", "titolare", "fondatore", "medewerker",
        "adviseur", "eigenaar", "oprichter", "beheerder", "diretor", "socio", "engenheiro", "advogado",
        "مدير", "مديرة", "مهندس", "مهندسة", "رئيس", "مسؤول", "مستشار", "مستشارة", "محامي", "מנהל", "מנהלת", "מהנדס", "יועץ", "מנכל",
    )

    /** German compounds ("Vertriebsleiter", "Projektmanagerin") hold their title word at the end. */
    private val TITLE_ENDINGS = listOf("leiter", "leiterin", "manager", "managerin", "berater", "beraterin", "ingenieur", "entwickler", "referent", "direktor")

    fun isTitle(text: String): Boolean {
        val w = words(text)
        if (w.isEmpty() || w.size > 8) return false
        // "Head" is a title in "Head of Sales" (or alone), not in "Head Office".
        if (w.any { it != "head" && it in TITLE_WORDS }) return true
        if ("head" in w) return "of" in w || w.size == 1
        return w.any { word -> TITLE_ENDINGS.any { word.length > it.length + 2 && word.endsWith(it) } }
    }

    /** Legal forms: a line ending in one is an organisation even when it also holds a title word. */
    private val LEGAL_END = setOf(
        "ltd", "limited", "llc", "l l c", "inc", "incorporated", "corp", "corporation", "co", "company", "gmbh", "ag", "kg", "ohg", "ug", "ev",
        "e v", "se", "sa", "s a", "sas", "sarl", "srl", "s r l", "spa", "s p a", "bv", "b v", "nv", "n v", "oy", "oyj", "ab", "as", "a s",
        "asa", "aps", "plc", "llp", "lp", "pty", "pty ltd", "pvt", "pvt ltd", "private limited", "kk", "k k", "sl", "s l", "ltda", "cia",
        "sp z o o", "gmbh co kg", "co kg", "mbh",
    )
    private val ORG_END = LEGAL_END + setOf(
        "group", "holding", "holdings", "partners", "associates", "foundation", "institute", "university", "universitat",
        "universite", "universidad", "universita", "college", "school", "academy", "hospital", "clinic", "klinik", "bank", "agency", "studio",
        "studios", "lab", "labs", "solutions", "services", "systems", "technologies", "technology", "consulting", "ventures", "capital", "media",
        "verein", "stiftung", "network", "industries", "enterprises", "international", "global", "trust",
        "council", "association", "society", "museum", "gallery", "restaurant", "cafe", "hotel", "bakery", "pharmacy", "store", "shop",
    )
    private val ORG_START = setOf(
        "university", "universitat", "universite", "universidad", "bank", "ministry", "embassy", "department", "hochschule", "the",
        "شركة", "مؤسسة", "مجموعة", "חברת",
    )

    fun isOrg(text: String): Boolean = orgEnd(text, ORG_END) || orgOther(text)

    /** Ends in a legal form ("Acme GmbH", "Widgets Ltd."). */
    fun isLegalOrg(text: String): Boolean = orgEnd(text, LEGAL_END)

    private fun orgEnd(text: String, ends: Set<String>): Boolean {
        val w = words(text)
        if (w.isEmpty() || w.size > 10) return false
        for (n in 1..minOf(4, w.size)) if (w.takeLast(n).joinToString(" ") in ends && (w.size > n || n > 1)) return true
        return false
    }

    private fun orgOther(text: String): Boolean {
        val raw = text.trim()
        val w = words(raw)
        if (w.isEmpty() || w.size > 10) return false
        // "The …" only with two more words ("The Acme Group"), not "The Team".
        val opens = w.first() in ORG_START && (w[0] != "the" || w.size >= 3)
        if (w.size >= 2 && opens) return true
        // "Smith & Partners", "Johnson & Johnson".
        return raw.contains(" & ") && raw.split(' ').count { it.firstOrNull()?.isUpperCase() == true } >= 2
    }

    /** "Engineer at Acme", "Head of Sales @ Acme", "Leiter Vertrieb bei Acme": the title and the organisation. */
    fun titleAt(text: String): Pair<String, String>? {
        val m = AT.find(text) ?: return null
        val title = text.substring(0, m.range.first).trim()
        val org = text.substring(m.range.last + 1).trim()
        if (title.isEmpty() || org.isEmpty()) return null
        if (!isTitle(title) || org.any { it.isDigit() }) return null
        return title to org
    }

    private val AT = Regex("\\s+(?:at|@|bei|chez|en|presso|bij|na)\\s+", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- countries

    private val COUNTRIES: Set<String> by lazy {
        val names = HashSet<String>()
        for (iso in Locale.getISOCountries()) {
            val l = Locale.Builder().setRegion(iso).build()
            DISPLAY.forEach { names += countryKey(l.getDisplayCountry(it)) }
        }
        names += listOf(
            "uk", "u k", "usa", "u s a", "us", "u s", "united states of america", "england", "scotland", "wales", "northern ireland", "great britain",
            "the netherlands", "holland", "uae", "u a e", "czech republic", "south korea", "korea", "russia", "turkiye", "turkey", "ksa",
            "الامارات", "الإمارات", "السعودية", "مصر", "الأردن", "الاردن", "لبنان", "المغرب", "قطر", "الكويت", "باكستان", "ישראל",
        ).map(::countryKey)
        names
    }

    private val DISPLAY = listOf("en", "de", "fr", "es", "it", "nl", "pt").map(Locale::forLanguageTag)

    private fun countryKey(s: String): String = words(s).filter { it != "the" }.joinToString(" ")

    fun isCountry(text: String): Boolean {
        val k = countryKey(text)
        return k.isNotEmpty() && k in COUNTRIES
    }

    // ---------------------------------------------------------------- what an email address says

    private val FREE_MAIL = setOf(
        "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "yahoo.fr", "yahoo.de", "ymail.com", "hotmail.com", "hotmail.co.uk",
        "hotmail.fr", "hotmail.de", "outlook.com", "outlook.de", "live.com", "msn.com", "icloud.com", "me.com", "mac.com", "aol.com",
        "gmx.de", "gmx.net", "gmx.at", "gmx.ch", "web.de", "t-online.de", "freenet.de", "posteo.de", "mailbox.org", "proton.me",
        "protonmail.com", "pm.me", "tutanota.com", "tuta.io", "orange.fr", "free.fr", "laposte.net", "sfr.fr", "wanadoo.fr", "libero.it",
        "virgilio.it", "alice.it", "tiscali.it", "seznam.cz", "wp.pl", "o2.pl", "onet.pl", "yandex.ru", "mail.ru", "zoho.com", "fastmail.com",
        "hey.com", "btinternet.com", "sky.com", "virginmedia.com", "comcast.net", "verizon.net", "att.net", "bigpond.com", "rediffmail.com",
    )

    fun isFreeMail(email: String): Boolean = email.substringAfterLast('@').lowercase(Locale.ROOT) in FREE_MAIL

    /** Mailbox names that belong to a role, not a person ("info@", "sales@"). */
    val ROLE_MAILBOXES = setOf(
        "info", "contact", "hello", "hi", "sales", "support", "office", "admin", "team", "mail", "service", "services", "enquiries", "inquiries",
        "kontakt", "hr", "jobs", "careers", "press", "media", "billing", "accounts", "noreply", "no-reply", "help", "booking", "bookings",
        "reservations", "reception", "post", "general", "marketing", "orders", "shop", "webmaster", "privacy", "legal", "ventas", "contacto",
    )

    /** The organisation part of a domain ("mail.acme-widgets.co.uk" → "acmewidgets"), letters and digits only. */
    fun domainKey(host: String): String {
        val labels = host.lowercase(Locale.ROOT).removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return ""
        var end = labels.size - 1
        if (end >= 2 && labels[end - 1] in SECOND_LEVEL) end--
        return labels[end - 1].filter { it.isLetterOrDigit() }
    }

    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "gob", "nic")
}
