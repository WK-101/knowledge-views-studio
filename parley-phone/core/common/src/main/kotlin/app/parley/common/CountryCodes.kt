package app.parley.common

/** ISO-3166 alpha-2 → ITU calling code. Offline table, no external data. */
object CountryCodes {
    /** Countries whose numbers keep the leading 0 after the country code. */
    val KEEPS_TRUNK_ZERO = setOf("IT", "SM", "VA")

    /** Countries without a national trunk prefix. */
    val NO_TRUNK_PREFIX = setOf(
        "ES", "PT", "GR", "DK", "NO", "IS", "LU", "MT", "CY", "EE", "LV", "PL", "CZ", "SK",
        "SG", "HK", "MO", "QA", "BH", "KW", "OM", "CR", "GT", "HN", "SV", "NI", "PA", "UY", "BO",
        "MC", "AD", "LI", "FO", "GL", "SC", "MU", "CV", "BZ",
    )

    fun callingCode(iso: String): String? = MAP[iso.uppercase()]

    /** Prefixes used to dial abroad, longest first. Default: 00. */
    fun internationalPrefixes(iso: String): List<String> {
        val i = iso.uppercase()
        return when {
            callingCode(i) == "1" -> listOf("011")
            i == "AU" -> listOf("0011")
            i == "JP" -> listOf("010")
            i in setOf("RU", "KZ", "BY") -> listOf("810", "00")
            i == "KR" -> listOf("001", "002", "00700")
            i == "IL" -> listOf("00", "012", "013", "014")
            i == "HK" || i == "SG" -> listOf("001", "00")
            i == "TH" -> listOf("001", "00")
            else -> listOf("00")
        }.sortedByDescending { it.length }
    }

    /** National trunk prefix, or null where there is none. */
    fun trunkPrefix(iso: String): String? {
        val i = iso.uppercase()
        return when {
            i in NO_TRUNK_PREFIX || i in KEEPS_TRUNK_ZERO -> null
            callingCode(i) == "1" -> "1"
            i in setOf("RU", "KZ", "BY", "TM", "TJ", "UZ") -> "8"
            i == "HU" -> "06"
            i == "MN" -> "01"
            else -> "0"
        }
    }

    private val MAP: Map<String, String> = """
        AD376 AE971 AF93 AG1 AI1 AL355 AM374 AO244 AR54 AS1 AT43 AU61 AW297 AZ994 BA387 BB1 BD880
        BE32 BF226 BG359 BH973 BI257 BJ229 BM1 BN673 BO591 BR55 BS1 BT975 BW267 BY375 BZ501 CA1
        CD243 CF236 CG242 CH41 CI225 CK682 CL56 CM237 CN86 CO57 CR506 CU53 CV238 CW599 CY357 CZ420
        DE49 DJ253 DK45 DM1 DO1 DZ213 EC593 EE372 EG20 ER291 ES34 ET251 FI358 FJ679 FM691 FO298
        FR33 GA241 GB44 GD1 GE995 GF594 GG44 GH233 GI350 GL299 GM220 GN224 GP590 GQ240 GR30 GT502
        GU1 GW245 GY592 HK852 HN504 HR385 HT509 HU36 ID62 IE353 IL972 IM44 IN91 IQ964 IR98 IS354
        IT39 JE44 JM1 JO962 JP81 KE254 KG996 KH855 KI686 KM269 KN1 KP850 KR82 KW965 KY1 KZ7 LA856
        LB961 LC1 LI423 LK94 LR231 LS266 LT370 LU352 LV371 LY218 MA212 MC377 MD373 ME382 MG261
        MH692 MK389 ML223 MM95 MN976 MO853 MP1 MQ596 MR222 MS1 MT356 MU230 MV960 MW265 MX52 MY60
        MZ258 NA264 NC687 NE227 NG234 NI505 NL31 NO47 NP977 NR674 NZ64 OM968 PA507 PE51 PF689
        PG675 PH63 PK92 PL48 PR1 PS970 PT351 PW680 PY595 QA974 RE262 RO40 RS381 RU7 RW250 SA966
        SB677 SC248 SD249 SE46 SG65 SI386 SK421 SL232 SM378 SN221 SO252 SR597 SS211 ST239 SV503
        SY963 SZ268 TC1 TD235 TG228 TH66 TJ992 TL670 TM993 TN216 TO676 TR90 TT1 TV688 TW886 TZ255
        UA380 UG256 US1 UY598 UZ998 VA39 VC1 VE58 VG1 VI1 VN84 VU678 WS685 XK383 YE967 YT262 ZA27
        ZM260 ZW263
    """.trimIndent().split(Regex("\\s+")).filter { it.length > 2 }.associate { it.substring(0, 2) to it.substring(2) }
}
