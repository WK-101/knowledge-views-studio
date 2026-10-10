package app.parley.data

import kotlinx.serialization.json.Json

/**
 * Lossless JSON for a [ContactDetails] being edited, row ids and raw-contact bookkeeping included, so an editor
 * draft survives process death in saved state. Unlike [ContactDetailsJson] (vault storage), nothing is dropped:
 * blank rows the user just added and the ids that tell an update from an insert both come back.
 *
 * Generated from the model (`@Serializable`), so a new field is kept without a codec line. A draft lives only in the
 * saved state of one editor, never across an update of Parley, so it has no stored format to keep: a draft that no
 * longer reads is dropped and the contact is loaded again.
 */
object ContactDraftJson {
    // Defaults are left out (smaller saved state); unknown keys are skipped rather than losing the whole draft.
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(d: ContactDetails): String = json.encodeToString(ContactDetails.serializer(), d)

    fun decode(s: String): ContactDetails = json.decodeFromString(ContactDetails.serializer(), s)
}
