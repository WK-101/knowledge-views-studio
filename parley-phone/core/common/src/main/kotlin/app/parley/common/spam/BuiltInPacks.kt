package app.parley.common.spam

/**
 * Regulator range packs shipped inside the app (B5). They carry no personal data: only number ranges that a
 * regulator reserved for a purpose. Suggested when the SIM country matches.
 */
object BuiltInPacks {
    data class BuiltIn(
        val id: String,
        val country: String,
        val name: String,
        val publisher: String,
        val source: String,
        val description: String,
        val ranges: List<String>,
        val category: String,
        val score: Int,
    )

    /**
     * France, ARCEP decision 2022-1583: since 1 January 2023 telemarketing calls from platforms must come
     * from these geographic ranges.
     */
    val FRANCE_ARCEP = BuiltIn(
        id = "builtin.fr.arcep-telemarketing",
        country = "FR",
        name = "France: telemarketing ranges (ARCEP)",
        publisher = "ARCEP (built in)",
        source = "https://www.arcep.fr",
        description = "Numbers ARCEP reserved for telemarketing platforms: 01 62, 01 63, 02 70, 02 71, 03 77, 03 78, 04 24, 04 25, 05 68, 05 69, 09 48, 09 49.",
        ranges = listOf("+33162", "+33163", "+33270", "+33271", "+33377", "+33378", "+33424", "+33425", "+33568", "+33569", "+33948", "+33949"),
        category = "Telemarketing",
        score = 90,
    )

    val all: List<BuiltIn> = listOf(FRANCE_ARCEP)

    fun suggestedFor(countryIso: String?): List<BuiltIn> = all.filter { it.country.equals(countryIso, ignoreCase = true) }

    /** The built-in pack as a regular parsed pack (unsigned: it ships inside the APK, so it is trusted). */
    fun toPack(b: BuiltIn, version: Long = 20230101): ByteArray {
        val builder = PackBuilder(
            PackManifest(
                id = b.id, name = b.name, publisher = b.publisher, source = b.source, licence = "Public regulatory data",
                version = version, created = 1_672_531_200_000L, ttlDays = 0, regions = listOf(b.country), categories = mapOf("1" to b.category),
            ),
        )
        b.ranges.forEach { builder.addRange(it, 1, b.score) }
        return builder.build(now = 1_672_531_200_000L)
    }
}
