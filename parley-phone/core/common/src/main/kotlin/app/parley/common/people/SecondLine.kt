package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.TextSearch

/** What the contacts list shows under each name (Settings › Appearance › "Second line"). */
enum class SecondLineMode(val title: String) {
    NUMBER("Phone number"),
    COMPANY_TITLE("Company · title"),
    NICKNAME("Nickname"),
    ACCOUNT("Account"),
    NONE("Nothing"),
}

/** Contact fields the list rows need beyond [ContactSummary], loaded in one pass over the provider. */
data class PersonExtra(
    val company: String = "",
    val title: String = "",
    val nickname: String = "",
    /** Account labels of the contact's raw contacts ("Google · me@…", "Phone only"). */
    val accounts: List<String> = emptyList(),
    /** Titles of the labels (groups) the contact belongs to. */
    val labels: Set<String> = emptySet(),
    /** The contact has a date-of-death event. */
    val deceased: Boolean = false,
) {
    val companyTitle: String
        get() = listOf(company.trim(), title.trim()).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(" · ")
}

object SecondLines {

    /**
     * The second line for every contact in [contacts].
     *
     * - The chosen [mode] fills the line when the contact has that field.
     * - When two or more visible names are the same (after case and accent folding), each of them gets the
     *   first field that tells them apart, in this order: company · title, nickname, number, account.
     *   That happens even with [SecondLineMode.NONE], because two identical rows are useless.
     */
    fun compute(
        contacts: List<ContactSummary>,
        extras: Map<Long, PersonExtra>,
        mode: SecondLineMode,
        formatNumber: (String) -> String = { it },
    ): Map<Long, String> {
        val out = HashMap<Long, String>()
        fun number(c: ContactSummary) = (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull())?.number?.let(formatNumber).orEmpty()
        fun field(c: ContactSummary, m: SecondLineMode): String {
            val e = extras[c.id] ?: PersonExtra()
            return when (m) {
                SecondLineMode.NUMBER -> number(c)
                SecondLineMode.COMPANY_TITLE -> e.companyTitle
                SecondLineMode.NICKNAME -> e.nickname.trim()
                SecondLineMode.ACCOUNT -> e.accounts.distinct().joinToString(", ")
                SecondLineMode.NONE -> ""
            }
        }
        for (c in contacts) field(c, mode).takeIf { it.isNotEmpty() }?.let { out[c.id] = it }

        val byName = contacts.groupBy { TextSearch.normalize(it.displayName).trim() }
        val order = listOf(SecondLineMode.COMPANY_TITLE, SecondLineMode.NICKNAME, SecondLineMode.NUMBER, SecondLineMode.ACCOUNT)
        for ((name, group) in byName) {
            if (name.isEmpty() || group.size < 2) continue
            // Already told apart by the chosen line?
            val current = group.map { out[it.id].orEmpty() }
            if (current.all { it.isNotEmpty() } && current.toSet().size == group.size) continue
            val pick = order.firstOrNull { m ->
                val values = group.map { field(it, m) }
                values.any { it.isNotEmpty() } && values.toSet().size > 1
            } ?: continue
            for (c in group) field(c, pick).takeIf { it.isNotEmpty() }?.let { out[c.id] = it }
        }
        return out
    }

    /** Name shown in lists: the nickname when [preferNickname] is on and the contact has one. */
    fun displayName(c: ContactSummary, extra: PersonExtra?, preferNickname: Boolean): String =
        extra?.nickname?.trim()?.takeIf { preferNickname && it.isNotEmpty() } ?: c.displayName
}
