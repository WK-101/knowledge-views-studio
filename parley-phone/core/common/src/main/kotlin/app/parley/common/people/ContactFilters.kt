package app.parley.common.people

import java.util.Locale

/** What the Contacts filters can narrow by, beyond labels and accounts (which [LabelFilter] keeps). */
enum class Facet {
    COUNTRY,

    /** A city or a region ("Lisbon", "Ontario"). */
    PLACE,
    COMPANY,
    RELATION,
    LANGUAGE,
    CUSTOM_LABEL,

    /** "1".."12". */
    BIRTHDAY_MONTH,

    /** Has an email, an address, a photo, a birthday ([ContactFacets.HAS_EMAIL]…); each value is its own filter. */
    HAS,

    /** Has no number, no name ([ContactFacets.NO_NUMBER]…); each value is its own filter. */
    MISSING,

    /** Temporary ([ContactFacets.TEMPORARY]). Private is the Contacts tab's own "Private" filter. */
    KEPT,
}

/**
 * The values one contact offers each [Facet]: by key ([key], accent- and case-insensitive) with the value as written,
 * and its flags (has an email…). Built with [ContactSearch.Builder].
 */
class ContactFacets internal constructor(
    val values: Map<Facet, Map<String, String>>,
    private val flags: Set<String>,
    val named: Boolean,
) {
    fun has(flag: String): Boolean = flag in flags

    internal class Builder {
        private val values = HashMap<Facet, LinkedHashMap<String, String>>()
        private val flags = HashSet<String>()
        var named = false

        fun add(facet: Facet, value: String) {
            val v = value.trim()
            if (v.isEmpty()) return
            values.getOrPut(facet) { LinkedHashMap() }.putIfAbsent(key(v), v)
        }

        fun flag(f: String) {
            flags += f
        }

        fun build() = ContactFacets(values, flags, named)
    }

    companion object {
        const val HAS_EMAIL = "email"
        const val HAS_ADDRESS = "address"
        const val HAS_PHOTO = "photo"
        const val HAS_BIRTHDAY = "birthday"
        const val HAS_NUMBER = "number"
        const val NO_NUMBER = "number"
        const val NO_NAME = "name"
        const val TEMPORARY = "temporary"

        /** The key a value is compared by. */
        fun key(value: String): String = ContactSearch.fold(value.trim()).replace(Regex("\\s+"), " ")

        val EMPTY = ContactFacets(emptyMap(), emptySet(), named = false)
    }
}

/**
 * The Contacts filters beyond labels and accounts: AND across facets, OR within one ("Portugal or Spain", and has an
 * email). [Facet.HAS] and [Facet.MISSING] values are each a filter of their own (has an email *and* a photo).
 */
data class FieldFilter(val chosen: Map<Facet, Set<String>> = emptyMap()) {
    val isEmpty: Boolean get() = chosen.values.all { it.isEmpty() }

    /** How many values are chosen (the Filters chip's count). */
    val count: Int get() = chosen.values.sumOf { it.size }

    fun has(facet: Facet, key: String): Boolean = key in chosen[facet].orEmpty()

    fun toggle(facet: Facet, key: String): FieldFilter {
        val now = chosen[facet].orEmpty().let { if (key in it) it - key else it + key }
        return FieldFilter(if (now.isEmpty()) chosen - facet else chosen + (facet to now))
    }

    /**
     * Whether a contact with [facets] (null: nothing known beyond its listing) passes. [photo] and [temporary] come
     * from the list, not the contact's fields.
     */
    fun matches(facets: ContactFacets?, photo: Boolean, temporary: Boolean): Boolean {
        val f = facets ?: ContactFacets.EMPTY
        return chosen.all { (facet, keys) -> keys.isEmpty() || passes(facet, keys, f, photo, temporary) }
    }

    private fun passes(facet: Facet, keys: Set<String>, f: ContactFacets, photo: Boolean, temporary: Boolean): Boolean = when (facet) {
        Facet.HAS -> keys.all { k -> if (k == ContactFacets.HAS_PHOTO) photo else f.has(k) }
        Facet.MISSING -> keys.all { k -> missing(k, f) }
        Facet.KEPT -> keys.all { k -> k != ContactFacets.TEMPORARY || temporary }
        else -> f.values[facet]?.let { have -> keys.any { it in have } } == true
    }

    private fun missing(k: String, f: ContactFacets): Boolean = when (k) {
        ContactFacets.NO_NUMBER -> !f.has(ContactFacets.HAS_NUMBER)
        ContactFacets.NO_NAME -> !f.named
        else -> true
    }

    /** Drops values no contact offers any more (a country edited away), so no filter hides everyone silently. */
    fun retain(choices: Map<Facet, List<FacetChoice>>): FieldFilter {
        val kept = chosen.mapValues { (facet, keys) ->
            if (facet == Facet.HAS || facet == Facet.MISSING || facet == Facet.KEPT) keys
            else keys.filter { k -> choices[facet].orEmpty().any { it.key == k } }.toSet()
        }.filterValues { it.isNotEmpty() }
        return if (kept == chosen) this else FieldFilter(kept)
    }
}

/** One value a facet offers: its key, the value as most contacts write it, and how many contacts have it. */
data class FacetChoice(val key: String, val display: String, val count: Int)

object FacetChoices {
    /** The values each facet offers across [all] contacts, most used first (months in calendar order). */
    fun from(all: Collection<ContactFacets>): Map<Facet, List<FacetChoice>> {
        val counts = HashMap<Facet, HashMap<String, HashMap<String, Int>>>()
        for (f in all) {
            for ((facet, values) in f.values) {
                val byKey = counts.getOrPut(facet) { HashMap() }
                for ((k, display) in values) {
                    val spellings = byKey.getOrPut(k) { HashMap() }
                    spellings[display] = (spellings[display] ?: 0) + 1
                }
            }
        }
        return counts.mapValues { (facet, byKey) ->
            val list = byKey.map { (k, spellings) ->
                val usual = spellings.entries.maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }).key
                FacetChoice(k, usual, spellings.values.sum())
            }
            if (facet == Facet.BIRTHDAY_MONTH) list.sortedBy { it.key.toIntOrNull() ?: 0 }
            else list.sortedWith(compareByDescending<FacetChoice> { it.count }.thenBy { it.display.lowercase(Locale.ROOT) })
        }
    }
}

/** Country names as people write them, folded to one name each ("PT", "portugal" and "Portugal" are Portugal). */
object Countries {
    private val byKey: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (iso in Locale.getISOCountries()) {
            val name = Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.ENGLISH)
            if (name.isBlank() || name == iso) continue
            m[ContactFacets.key(name)] = name
            m[iso.lowercase(Locale.ROOT)] = name
        }
        fun alias(name: String, vararg aliases: String) = aliases.forEach { a -> m[ContactFacets.key(a)] = name }
        val us = m["us"] ?: "United States"
        val uk = m["gb"] ?: "United Kingdom"
        alias(us, "usa", "u.s.a.", "u.s.", "united states of america", "america")
        alias(uk, "uk", "u.k.", "great britain", "britain", "england", "scotland", "wales", "northern ireland")
        m["nl"]?.let { alias(it, "the netherlands", "holland") }
        m["ae"]?.let { alias(it, "uae", "u.a.e.") }
        m["cz"]?.let { alias(it, "czech republic") }
        m["kr"]?.let { alias(it, "south korea", "korea") }
        m["tr"]?.let { alias(it, "turkey", "turkiye") }
        m["ru"]?.let { alias(it, "russia") }
        m
    }

    /** The country's English name for [raw] when it is one (a name or ISO code), else [raw] tidied. */
    fun canonical(raw: String): String {
        val t = raw.trim().trimEnd('.')
        if (t.isEmpty()) return ""
        return byKey[ContactFacets.key(t)] ?: byKey[ContactFacets.key(raw.trim())] ?: t
    }
}
