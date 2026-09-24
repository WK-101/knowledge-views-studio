package app.parley.common.people

import app.parley.common.TextSearch

/**
 * I8: Contacts-tab search over more than names and numbers: e-mail, nickname, company and job title, postal
 * address, notes, websites and messenger handles. Only for the Contacts tab's search box (never the keypad's T9).
 * [match] says which field matched, so the row can say "Matched: address".
 */
object BroadSearch {
    enum class Field {
        NAME,
        NUMBER,
        EMAIL,
        NICKNAME,
        COMPANY,
        ADDRESS,
        NOTE,
        WEBSITE,
        HANDLE,
    }

    /** The extra searchable text of one contact (everything beyond the summary's name, numbers and e-mails). */
    data class Extra(
        val nickname: String = "",
        val company: String = "",
        val title: String = "",
        val addresses: List<String> = emptyList(),
        val note: String = "",
        val websites: List<String> = emptyList(),
        val handles: List<String> = emptyList(),
    )

    /** Which field [query] matches first, or null for no match. A blank query matches everything by name. */
    fun match(query: String, name: String, numbers: List<String>, emails: List<String>, extra: Extra?): Field? {
        val q = TextSearch.normalize(query.trim())
        if (q.isEmpty()) return Field.NAME
        if (TextSearch.matches(query, name)) return Field.NAME
        if (TextSearch.matches(query, "", numbers)) return Field.NUMBER
        fun any(values: List<String>) = values.any { it.isNotBlank() && TextSearch.normalize(it).contains(q) }
        return when {
            any(emails) -> Field.EMAIL
            extra == null -> null
            any(listOf(extra.nickname)) -> Field.NICKNAME
            any(listOf(extra.company, extra.title)) -> Field.COMPANY
            any(extra.addresses) -> Field.ADDRESS
            any(listOf(extra.note)) -> Field.NOTE
            any(extra.websites) -> Field.WEBSITE
            any(extra.handles) -> Field.HANDLE
            else -> null
        }
    }

    /** True when the row should say which field matched ("Matched: address"); false for the name or number. */
    fun explains(field: Field?): Boolean = field != null && field != Field.NAME && field != Field.NUMBER
}
