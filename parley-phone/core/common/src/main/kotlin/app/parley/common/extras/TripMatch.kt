package app.parley.common.extras

import app.parley.common.TextSearch

/**
 * X2 "Who's in…" (trip mode): the people linked to a city you type or pick. Nothing is detected: there is no
 * location permission, the city is always typed. A person matches through a postal address (city, region or
 * country), a note that mentions the place, or the place libphonenumber's offline geocoder gives for one of their
 * numbers ("Lisbon", "Mountain View, CA", "Portugal").
 */
object TripMatch {
    /** One contact as trip mode sees it. [places]: city, region and country of each address, and the formatted address. */
    data class Person(
        val id: Long,
        val name: String,
        val places: List<String> = emptyList(),
        val note: String = "",
        /** Offline geocoder descriptions of the person's numbers. */
        val numberPlaces: List<String> = emptyList(),
    )

    /** Why someone is listed, strongest first. */
    enum class Reason { ADDRESS, NUMBER, NOTE }

    data class Hit(val person: Person, val reasons: Set<Reason>) {
        val best: Reason get() = Reason.entries.first { it in reasons }
    }

    /** Lower case, no accents, punctuation as spaces, single spaces: "São  Paulo," → "sao paulo". */
    fun normalize(text: String): String =
        TextSearch.normalize(text).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * True when [text] mentions [city] as whole words ("Rome" is in "Rome, Italy" but not in "Romeo"). Case and
     * accents don't matter; a blank city matches nothing.
     */
    fun mentions(text: String, city: String): Boolean {
        val c = normalize(city)
        if (c.isEmpty()) return false
        val t = normalize(text)
        if (t.isEmpty()) return false
        return " $t ".contains(" $c ")
    }

    /** Everyone linked to [city], strongest reason first, then by name. */
    fun match(city: String, people: List<Person>): List<Hit> {
        if (normalize(city).isEmpty()) return emptyList()
        return people.mapNotNull { p ->
            val reasons = buildSet {
                if (p.places.any { mentions(it, city) }) add(Reason.ADDRESS)
                if (p.numberPlaces.any { mentions(it, city) }) add(Reason.NUMBER)
                if (mentions(p.note, city)) add(Reason.NOTE)
            }
            if (reasons.isEmpty()) null else Hit(p, reasons)
        }.sortedWith(compareBy<Hit> { it.best.ordinal }.thenBy { normalize(it.person.name) })
    }

    /**
     * Cities to offer in the picker: the cities in your contacts' addresses, most common first, one spelling each
     * (the most used; "lisbon" and "Lisbon" are one city). [recent] (the last city you looked up) comes first.
     */
    fun cityChoices(cities: List<String>, recent: String? = null, limit: Int = 40): List<String> {
        val groups = cities.map { it.trim() }.filter { normalize(it).isNotEmpty() }.groupBy { normalize(it) }
        val ranked = groups.values.sortedWith(compareByDescending<List<String>> { it.size }.thenBy { normalize(it.first()) })
            .map { spellings -> spellings.groupingBy { it }.eachCount().maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).key }
        val r = recent?.trim()?.takeIf { normalize(it).isNotEmpty() }
        return (listOfNotNull(r) + ranked.filter { r == null || normalize(it) != normalize(r) }).take(limit)
    }
}
