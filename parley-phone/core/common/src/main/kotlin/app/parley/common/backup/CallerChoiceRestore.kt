package app.parley.common.backup

import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.extras.CallerChoice

/**
 * Device contacts' vibration and auto-answer in the backup. Lookup keys of local and many synced contacts differ
 * on another phone, so each choice travels with its contact's [PersonRef] (name and numbers) and is matched to a
 * contact restored here more strictly than other notes, since auto-answer picks up a call on its own:
 * - the same lookup key counts only when the name or a number agrees too (a local contact's key can name someone else
 *   on the new phone);
 * - otherwise a contact sharing one of the numbers;
 * - a name alone (the only contact with it) brings back the vibration but never auto-answer.
 */
object CallerChoiceRestore {
    data class Entry(val ref: PersonRef, val choice: CallerChoice)

    data class Result(val choices: Map<String, CallerChoice>, val unmatched: Int)

    fun restore(entries: List<Entry>, contacts: List<ContactSummary>): Result {
        val byKey = contacts.associateBy { it.lookupKey }
        val out = LinkedHashMap<String, CallerChoice>()
        var unmatched = 0
        for (e in entries.filterNot { it.choice.isEmpty }) {
            val m = match(e.ref, byKey, contacts)
            val choice = m?.let { (_, sure) -> if (sure) e.choice else e.choice.copy(autoAnswer = false) }
            if (m == null || choice == null || choice.isEmpty) {
                unmatched++
            } else {
                // Two entries for one contact here: the first wins field by field, like a rekey.
                val key = m.first.lookupKey
                val there = out[key]
                out[key] = if (there == null) {
                    choice
                } else {
                    CallerChoice(there.vibration ?: choice.vibration, there.autoAnswer || choice.autoAnswer, there.neverCalls || choice.neverCalls)
                }
            }
        }
        return Result(out, unmatched)
    }

    /** The contact for [ref] and whether the match is sure enough for auto-answer; null when nobody matches. */
    private fun match(ref: PersonRef, byKey: Map<String, ContactSummary>, contacts: List<ContactSummary>): Pair<ContactSummary, Boolean>? {
        fun numbers(c: ContactSummary) = c.phones.mapNotNull { PhoneIdentity.portableKey(it.number) }
        byKey[ref.key]?.let { c ->
            val sameNumber = ref.phones.isNotEmpty() && numbers(c).any { it in ref.phones }
            val sameName = !ref.name.isNullOrBlank() && ref.name == c.displayName
            if (sameNumber) return c to true
            // A key with nothing to check it against (an older backup) or with the same name: the vibration only.
            if (sameName || (ref.name == null && ref.phones.isEmpty())) return c to false
        }
        if (ref.phones.isNotEmpty()) contacts.firstOrNull { c -> numbers(c).any { it in ref.phones } }?.let { return it to true }
        val name = ref.name?.takeIf { it.isNotBlank() } ?: return null
        return contacts.filter { it.displayName == name }.singleOrNull()?.let { it to false }
    }
}
