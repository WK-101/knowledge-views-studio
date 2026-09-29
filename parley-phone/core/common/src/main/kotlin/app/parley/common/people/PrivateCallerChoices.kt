package app.parley.common.people

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime

/**
 * A private contact's star, labels, ringtone and "send to voicemail" are kept in its caller-ID copy
 * (docs/CONTACT_MODEL.md). Entries saved before that kept the star and ringtone only in their sealed details, and
 * their labels only as the group rows of the address-book record they were moved in with. [seed] reads those once, so
 * the caller-ID copy can be filled in and nothing is lost when the contact is shown, edited or made visible again.
 */
object PrivateCallerChoices {
    data class Seed(
        val starred: Boolean,
        val ringtone: String?,
        val sendToVoicemail: Boolean,
        val labels: List<PrivateLabels.Membership>,
    )

    /** From the sealed details' own values and the stored [record] (null when the entry was made in Parley). */
    fun seed(starred: Boolean, ringtone: String?, sendToVoicemail: Boolean, record: ContactRecord?): Seed = Seed(
        starred = starred || record?.starred == true,
        ringtone = ringtone?.takeIf { it.isNotBlank() } ?: record?.customRingtone?.takeIf { it.isNotBlank() },
        sendToVoicemail = sendToVoicemail || record?.sendToVoicemail == true,
        labels = labelsOf(record),
    )

    /**
     * The labels [record] was in: its group rows that carry a title (user-made groups; system groups such as "My
     * contacts" have none and aren't labels), once per title.
     */
    fun labelsOf(record: ContactRecord?): List<PrivateLabels.Membership> =
        record?.raws.orEmpty().asSequence().flatMap { it.rows }.filter { it.mimeType == Mime.GROUP }
            .mapNotNull { row ->
                val title = row[Col.GROUP_TITLE]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PrivateLabels.Membership(row[Col.D1]?.toLongOrNull() ?: 0L, title)
            }
            .distinctBy { it.title }.toList()
}
