package app.parley.common.qr

import app.parley.common.vcard.VCardStream

/**
 * Q3: classifies the text of a QR code (or pasted text) into a [QrPayload]. Pure and offline; see the ZXing
 * "Barcode Contents" conventions for the formats. Order matters: Parley's own links, contacts, then schemes, then
 * known messenger links, then any web address, then plain text.
 */
object QrParser {
    /** Longest text read (a QR code holds at most ~7 KB; pasted text can be anything). */
    const val MAX_INPUT = 16_000

    /** Most contacts read from one code. */
    const val MAX_CARDS = 200

    fun parse(input: String): QrPayload {
        val truncated = input.length > MAX_INPUT
        val raw = if (truncated) input.take(MAX_INPUT) else input
        val t = raw.trim().trimStart('\uFEFF')
        if (t.isEmpty() || truncated) return QrPayload.Text(raw, truncated)
        val upper = t.take(16).uppercase()
        val oneLine = '\n' !in t && '\r' !in t
        parley(t)?.let { return it }
        when {
            upper.startsWith("BEGIN:VCARD") -> return vcard(raw, t) ?: QrPayload.Text(raw)
            upper.startsWith("MECARD:") -> return mecard(raw, t) ?: QrPayload.Text(raw)
            upper.startsWith("BIZCARD:") -> return bizcard(raw, t) ?: QrPayload.Text(raw)
            upper.startsWith("WIFI:") -> return wifi(raw, t) ?: QrPayload.Text(raw)
            upper.startsWith("MATMSG:") -> return matmsg(raw, t) ?: QrPayload.Text(raw)
            upper.startsWith("BEGIN:VEVENT") || upper.startsWith("BEGIN:VCALENDAR") -> return event(raw, t) ?: QrPayload.Text(raw)
        }
        if (!oneLine) {
            // A few generators put a URL or tel: on the first line and a caption below; anything else is text.
            return QrPayload.Text(raw)
        }
        val scheme = t.substringBefore(':', "").lowercase()
        when (scheme) {
            "tel" -> return tel(raw, t) ?: QrPayload.Text(raw)
            "sms", "smsto", "mms", "mmsto" -> return sms(raw, t) ?: QrPayload.Text(raw)
            "mailto" -> return mailto(raw, t) ?: QrPayload.Text(raw)
            "geo" -> return geo(raw, t) ?: QrPayload.Text(raw)
        }
        MessengerQr.classify(t)?.let { return it.copy(raw = raw) }
        if (scheme == "http" || scheme == "https") {
            UrlSafety.analyse(t)?.let { return QrPayload.Url(raw, t, it) }
        }
        if (EMAIL.matches(t)) return QrPayload.Email(raw, listOf(t))
        return QrPayload.Text(raw)
    }

    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    // ---------------------------------------------------------------- Parley

    private fun parley(t: String): QrPayload.Parley? {
        if (!t.startsWith("parley://", ignoreCase = true)) return null
        val host = t.substring(9).substringBefore('?').substringBefore('/').lowercase()
        val kind = when (host) {
            "qr" -> ParleyKind.CONTACT
            "simple" -> ParleyKind.SIMPLE
            "template" -> ParleyKind.TEMPLATE
            else -> return null
        }
        // The app opens it with the scheme and host lower-cased, as Android's intent filters expect.
        return QrPayload.Parley("parley://" + host + t.substring(9 + host.length), kind)
    }

    // ---------------------------------------------------------------- contacts

    private fun vcard(raw: String, t: String): QrPayload.Contact? {
        // Some generators end lines with a bare \r, or put the whole card on one line with literal "\n".
        val text = t.replace("\r\n", "\n").replace('\r', '\n')
        val (records, _) = VCardStream.readAll(text)
        if (records.isEmpty()) return null
        return QrPayload.Contact(raw, ContactFormat.VCARD, records.take(MAX_CARDS), text)
    }

    /** Splits `KEY:value;KEY:value;;` on unescaped `;` (backslash escapes `\ ; , : "`). */
    internal fun fields(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val cur = StringBuilder()
        var i = 0
        fun flush() {
            val f = cur.toString()
            cur.setLength(0)
            if (f.isEmpty()) return
            val k = f.substringBefore(':', "")
            if (k.isEmpty()) return
            out += k.trim().uppercase() to f.substringAfter(':')
        }
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { cur.append(c).append(body[i + 1]); i += 2; continue }
                c == ';' -> flush()
                else -> cur.append(c)
            }
            i++
        }
        flush()
        return out
    }

    /** Removes backslash escapes. */
    internal fun unescape(v: String): String {
        if ('\\' !in v) return v
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                val n = v[i + 1]
                sb.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Splits on unescaped [sep], then unescapes each part. */
    private fun split(v: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) { cur.append(c).append(v[i + 1]); i += 2; continue }
            if (c == sep) { out += unescape(cur.toString()); cur.setLength(0) } else cur.append(c)
            i++
        }
        out += unescape(cur.toString())
        return out
    }

    /** vCard 3.0 text escaping. */
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r\n", "\\n").replace("\n", "\\n")

    /** A vCard 3.0 made from simple fields, then read through the vCard engine like any other card. */
    private class CardBuilder {
        val lines = ArrayList<String>()
        fun add(line: String) { lines += line }
        fun text(): String = (listOf("BEGIN:VCARD", "VERSION:3.0") + lines + "END:VCARD").joinToString("\r\n") + "\r\n"
    }

    private fun mecard(raw: String, t: String): QrPayload.Contact? {
        val f = fields(t.substring(t.indexOf(':') + 1))
        val b = CardBuilder()
        var named = false
        f.forEach { (k, v) ->
            when (k) {
                "N" -> {
                    // "Last,First" (either may be missing).
                    val p = split(v, ',')
                    val family = p.getOrElse(0) { "" }.trim()
                    val given = p.getOrElse(1) { "" }.trim()
                    b.add("N:${esc(family)};${esc(given)};;;")
                    b.add("FN:" + esc(listOf(given, family).filter { it.isNotEmpty() }.joinToString(" ")))
                    named = true
                }
                "SOUND" -> {
                    val p = split(v, ',')
                    p.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { b.add("X-PHONETIC-LAST-NAME:" + esc(it)) }
                    p.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { b.add("X-PHONETIC-FIRST-NAME:" + esc(it)) }
                }
                "TEL" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { b.add("TEL;TYPE=CELL:" + esc(it)) }
                "TEL-AV" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { b.add("TEL;TYPE=VIDEO:" + esc(it)) }
                "EMAIL" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { b.add("EMAIL;TYPE=INTERNET:" + esc(it)) }
                "NOTE", "MEMO" -> unescape(v).takeIf { it.isNotBlank() }?.let { b.add("NOTE:" + esc(it)) }
                "BDAY" -> bday(unescape(v))?.let { b.add("BDAY:$it") }
                "ADR" -> {
                    // docomo order: PO box, room, street, city, region, postcode, country.
                    val p = split(v, ',').map { it.trim() }
                    if (p.size >= 7) b.add("ADR:" + p.take(7).joinToString(";") { esc(it) })
                    else b.add("ADR:;;" + esc(unescape(v).trim()) + ";;;;")
                }
                "URL" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { b.add("URL:" + esc(it)) }
                "NICKNAME" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { b.add("NICKNAME:" + esc(it)) }
                "ORG" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { b.add("ORG:" + esc(it)) }
                "TITLE" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { b.add("TITLE:" + esc(it)) }
            }
        }
        if (!named && b.lines.isEmpty()) return null
        return contactFrom(raw, ContactFormat.MECARD, b)
    }

    private fun bizcard(raw: String, t: String): QrPayload.Contact? {
        val f = fields(t.substring(t.indexOf(':') + 1)).associate { (k, v) -> k to unescape(v).trim() }
        val b = CardBuilder()
        val given = f["N"].orEmpty()
        val family = f["X"].orEmpty()
        if (given.isNotEmpty() || family.isNotEmpty()) {
            b.add("N:${esc(family)};${esc(given)};;;")
            b.add("FN:" + esc(listOf(given, family).filter { it.isNotEmpty() }.joinToString(" ")))
        }
        f["T"]?.takeIf { it.isNotEmpty() }?.let { b.add("TITLE:" + esc(it)) }
        f["C"]?.takeIf { it.isNotEmpty() }?.let { b.add("ORG:" + esc(it)) }
        f["A"]?.takeIf { it.isNotEmpty() }?.let { b.add("ADR:;;" + esc(it) + ";;;;") }
        f["B"]?.takeIf { it.isNotEmpty() }?.let { b.add("TEL;TYPE=WORK:" + esc(it)) }
        f["M"]?.takeIf { it.isNotEmpty() }?.let { b.add("TEL;TYPE=CELL:" + esc(it)) }
        f["F"]?.takeIf { it.isNotEmpty() }?.let { b.add("TEL;TYPE=FAX,WORK:" + esc(it)) }
        f["E"]?.takeIf { it.isNotEmpty() }?.let { b.add("EMAIL;TYPE=INTERNET:" + esc(it)) }
        if (b.lines.isEmpty()) return null
        return contactFrom(raw, ContactFormat.BIZCARD, b)
    }

    private fun contactFrom(raw: String, format: ContactFormat, b: CardBuilder): QrPayload.Contact? {
        val text = b.text()
        val (records, _) = VCardStream.readAll(text)
        if (records.isEmpty()) return null
        return QrPayload.Contact(raw, format, records, text)
    }

    /** `yyyyMMdd` or `yyyy-MM-dd` as a vCard date. */
    private fun bday(v: String): String? {
        val d = v.filter { it.isDigit() }
        if (d.length != 8) return null
        val m = d.substring(4, 6).toInt()
        val day = d.substring(6, 8).toInt()
        if (m !in 1..12 || day !in 1..31) return null
        return "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}"
    }

    // ---------------------------------------------------------------- tel / sms / mail / geo

    private fun tel(raw: String, t: String): QrPayload.Phone? {
        // "tel:" and the RFC 3966 parameters (";ext=", ";phone-context=") after the number.
        val body = QrText.percentDecode(t.substring(4).removePrefix("//"))
        val main = body.substringBefore(';').trim()
        val ext = Regex(";ext=([0-9]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        val number = QrText.clean(main, keepLines = false).filter { it.isDigit() || it in "+*#,;() -./pwPW" }.trim()
        if (number.none { it.isDigit() || it == '*' || it == '#' }) return null
        val full = if (ext != null) "$number,$ext" else number
        return QrPayload.Phone(raw, full, isMmi = '*' in full || '#' in full)
    }

    private fun sms(raw: String, t: String): QrPayload.Sms? {
        val scheme = t.substringBefore(':').lowercase()
        val rest = t.substringAfter(':').removePrefix("//")
        val numbersPart: String
        var body: String? = null
        if (scheme == "smsto" || scheme == "mmsto") {
            // SMSTO:number:body (ZXing): the body may itself contain colons.
            numbersPart = rest.substringBefore(':')
            if (':' in rest) body = rest.substringAfter(':')
        } else if ('?' in rest) {
            numbersPart = rest.substringBefore('?')
            val q = QrText.query(rest.substringAfter('?'))
            body = q["body"]
        } else {
            numbersPart = rest.substringBefore(':')
            if (':' in rest) body = rest.substringAfter(':')
        }
        val numbers = QrText.percentDecode(numbersPart).split(',').map { n -> QrText.clean(n, false).filter { it.isDigit() || it in "+*#" } }.filter { it.isNotEmpty() }
        if (numbers.isEmpty()) return null
        if (scheme == "smsto" || scheme == "mmsto") body = body?.let { QrText.clean(it) }
        return QrPayload.Sms(raw, numbers, body?.takeIf { it.isNotEmpty() })
    }

    private fun mailto(raw: String, t: String): QrPayload.Email? {
        val rest = t.substring(7)
        val to = QrText.percentDecode(rest.substringBefore('?')).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val q = QrText.query(rest.substringAfter('?', ""))
        fun list(k: String) = q[k]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val allTo = to + list("to")
        if (allTo.isEmpty() && q.isEmpty()) return null
        return QrPayload.Email(raw, allTo, list("cc"), list("bcc"), q["subject"], q["body"])
    }

    private fun matmsg(raw: String, t: String): QrPayload.Email? {
        val f = fields(t.substring(t.indexOf(':') + 1)).associate { (k, v) -> k to unescape(v) }
        val to = f["TO"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (to.isEmpty() && f["SUB"].isNullOrEmpty() && f["BODY"].isNullOrEmpty()) return null
        return QrPayload.Email(raw, to, subject = f["SUB"]?.takeIf { it.isNotEmpty() }, body = f["BODY"]?.takeIf { it.isNotEmpty() })
    }

    private fun geo(raw: String, t: String): QrPayload.Geo? {
        val rest = t.substring(4)
        val coords = rest.substringBefore('?').substringBefore(';').split(',')
        val lat = coords.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lon = coords.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val alt = coords.getOrNull(2)?.trim()?.toDoubleOrNull()
        val q = QrText.query(rest.substringAfter('?', ""), plusIsSpace = true)["q"]?.takeIf { it.isNotBlank() }
        return QrPayload.Geo(raw, lat, lon, alt, q)
    }

    // ---------------------------------------------------------------- Wi-Fi

    /**
     * `WIFI:T:WPA;S:name;P:secret;H:true;;`. Values use backslash escapes (ZXing, Android) or, per the WPA3
     * spec, percent-encoding: percent escapes are decoded only when the code uses no backslash escapes and every `%`
     * starts a valid escape. `T:` is WPA/WPA2/WPA3, SAE, WEP, nopass (or missing) or an EAP method.
     */
    private fun wifi(raw: String, t: String): QrPayload.Wifi? {
        val body = t.substring(5)
        val f = fields(body).associate { it }
        val backslashes = '\\' in body
        fun value(k: String): String? {
            val v = f[k] ?: return null
            var u = unescape(v)
            if (!backslashes && '%' in u && Regex("%(?![0-9A-Fa-f]{2})").find(u) == null) u = QrText.percentDecode(u)
            return u
        }
        var ssid = value("S") ?: return null
        // Old generators quote names that look like hex.
        if (ssid.length >= 2 && ssid.startsWith('"') && ssid.endsWith('"')) ssid = ssid.substring(1, ssid.length - 1)
        if (ssid.isEmpty()) return null
        val type = value("T")?.trim()?.uppercase().orEmpty()
        val security = when {
            type.isEmpty() || type == "NOPASS" || type == "NONE" || type == "OPEN" -> WifiSecurity.OPEN
            type == "WEP" -> WifiSecurity.WEP
            type == "SAE" || type == "WPA3" -> WifiSecurity.SAE
            type.startsWith("WPA2-EAP") || type == "EAP" || type == "WPA-EAP" || type == "WPA3-EAP" -> WifiSecurity.EAP
            type.startsWith("WPA") -> WifiSecurity.WPA
            else -> WifiSecurity.WPA
        }
        var password = value("P")
        if (password != null && password.length >= 2 && password.startsWith('"') && password.endsWith('"') && security != WifiSecurity.OPEN) {
            password = password.substring(1, password.length - 1)
        }
        val hidden = value("H")?.trim()?.lowercase() in setOf("true", "1", "yes")
        val eap = value("E")?.takeIf { it.isNotBlank() }
        return QrPayload.Wifi(
            raw, ssid, if (eap != null && security == WifiSecurity.WPA) WifiSecurity.EAP else security,
            password?.takeIf { it.isNotEmpty() && security != WifiSecurity.OPEN }, hidden,
            eapMethod = eap, identity = value("I")?.takeIf { eap != null && it.isNotBlank() },
            anonymousIdentity = value("A")?.takeIf { it.isNotBlank() }, phase2 = value("PH2")?.takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------------- calendar

    private fun event(raw: String, t: String): QrPayload.Event? {
        // Unfold continuation lines, then read the first VEVENT.
        val lines = t.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("\n[ \t]"), "").split('\n')
        var inEvent = false
        val props = HashMap<String, Pair<Map<String, String>, String>>()
        for (line in lines) {
            val u = line.trim().uppercase()
            if (u == "BEGIN:VEVENT") { inEvent = true; continue }
            if (u == "END:VEVENT") break
            if (!inEvent) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val head = line.substring(0, colon).split(';')
            val name = head[0].trim().uppercase()
            val params = head.drop(1).associate { p -> p.substringBefore('=').uppercase() to p.substringAfter('=').trim('"') }
            props.putIfAbsent(name, params to line.substring(colon + 1))
        }
        if (!inEvent) return null
        fun text(k: String) = props[k]?.second?.let(::icsUnescape)?.takeIf { it.isNotBlank() }
        val start = props["DTSTART"]?.let { (p, v) -> icsTime(v, p["TZID"]) }
        var end = props["DTEND"]?.let { (p, v) -> icsTime(v, p["TZID"]) }
        if (end == null && start != null) end = props["DURATION"]?.second?.let { duration(start, it) }
        val summary = text("SUMMARY").orEmpty()
        if (summary.isEmpty() && start == null) return null
        return QrPayload.Event(raw, summary, start, end, text("LOCATION"), text("DESCRIPTION"), text("URL"))
    }

    private fun icsUnescape(v: String): String = v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** `20180601`, `20180601T070000`, `20180601T070000Z`. */
    internal fun icsTime(v: String, tzid: String? = null): IcsTime? {
        val m = Regex("""^(\d{4})-?(\d{2})-?(\d{2})(?:T(\d{2}):?(\d{2}):?(\d{2})?(Z)?)?$""").find(v.trim()) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        if (g[4].isEmpty()) return IcsTime(y, mo, d)
        val h = g[4].toInt(); val mi = g[5].toInt(); val s = g[6].ifEmpty { "0" }.toInt()
        if (h > 23 || mi > 59 || s > 60) return null
        return IcsTime(y, mo, d, h, mi, s.coerceAtMost(59), utc = g[7] == "Z", tzid = tzid?.takeIf { g[7] != "Z" })
    }

    /** DTSTART plus a DURATION such as `PT1H30M` or `P1D`. */
    private fun duration(start: IcsTime, v: String): IcsTime? = runCatching {
        val d = java.time.Duration.parse(v.trim().replace(Regex("^P(\\d+)D$"), "P$1DT0S"))
        val base = java.time.LocalDateTime.of(start.year, start.month, start.day, start.hour ?: 0, start.minute, start.second)
        val e = base.plus(d)
        if (start.allDay) IcsTime(e.year, e.monthValue, e.dayOfMonth)
        else start.copy(year = e.year, month = e.monthValue, day = e.dayOfMonth, hour = e.hour, minute = e.minute, second = e.second)
    }.getOrNull()
}
