package app.parley.data.people

import app.parley.common.AppSettings
import app.parley.common.people.ContactRef
import app.parley.common.people.RelationLinks
import app.parley.common.people.RelationMirror
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.db.ContactMetaEntity
import app.parley.data.vault.VaultCrypto

/**
 * A relation shown on a contact's page from another contact's relation to it: open [navId] on a tap. [ownerKey] and
 * [computed] say what a correction is remembered against ([RelationMirrors.correct]). [fromMe]: it is My card's own
 * relation to this contact, shown as you named it ("Wife"), and a tap opens My card.
 */
data class RelationFromOther(
    val navId: Long,
    val row: RelationMirror.Row,
    val ownerKey: String = "",
    val computed: RelationMirror.Row = row,
    val fromMe: Boolean = false,
)

/**
 * Two-way relations with private contacts (docs/CONTACT_MODEL.md, "Relations with private contacts"). Between two
 * contacts of the address book Parley writes the opposite relation on the other contact ([RelationMirrors]). When one
 * of them is private it doesn't: on a device contact that row would carry the private contact's name to every app
 * that reads contacts (and to its sync account), and a private contact's sealed details are only written when it is
 * edited. Instead the other contact's page shows the opposite relation from Parley's own links, while private
 * contacts can be shown (not in discreet mode, the vault's details open for a private one), and only with "Add
 * relations to both contacts" on.
 */
object RelationsFromOthers {
    /** The relations [self]'s page shows from others' relations to it; [metas]: Parley's rows of every contact. */
    suspend fun load(c: DataContainer, self: ContactDetails, selfPrivate: Boolean, metas: List<ContactMetaEntity>, s: AppSettings): List<RelationFromOther> {
        val key = self.lookupKey
        if (key.isEmpty() || selfPrivate && s.hideVault) return emptyList()
        return fromMyCard(c, key) + fromContacts(c, self, selfPrivate, metas, s)
    }

    /**
     * My card's relations to this contact (by the links its editor's picker made), as you named them: "my wife" shows
     * here as "Wife". My card is yours, so this needs no setting and writes nothing to the contact.
     */
    fun fromMyCard(c: DataContainer, key: String): List<RelationFromOther> {
        val me = c.people.me
        val names = me.links.value.filterValues { it.lookupKey == key }.keys
        if (names.isEmpty()) return emptyList()
        val myName = MeCardDetails.nameOf(me.details.value)
        return me.details.value.relations.filter { it.value.isNotBlank() && RelationLinks.nameKey(it.value) in names }.map { rel ->
            val r = RelationMirrors.rowOf(rel)
            RelationFromOther(0L, r.copy(name = myName), ownerKey = ME_KEY, fromMe = true)
        }
    }

    /** The key My card's relations are known by in corrections and links (it has no lookup key of its own). */
    const val ME_KEY = "me"

    private suspend fun fromContacts(
        c: DataContainer,
        self: ContactDetails,
        selfPrivate: Boolean,
        metas: List<ContactMetaEntity>,
        s: AppSettings,
    ): List<RelationFromOther> {
        val key = self.lookupKey
        val privateShown = !s.hideVault
        if (!s.mirrorRelations) return emptyList()
        if (selfPrivate && !privateShown) return emptyList()
        val incoming = ArrayList<RelationMirror.Incoming>()
        val navs = HashMap<String, Long>()
        for (m in linkingHere(metas, key, selfPrivate, privateShown)) {
            val ownerPrivate = ContactRef.isPrivateKey(m.lookupKey)
            val (nav, owner) = owner(c, m, ownerPrivate) ?: continue
            navs[m.lookupKey] = nav
            val names = RelationLinks.decode(m.relationLinks).filterValues { it.lookupKey == key }.keys
            val name = owner.displayName.ifBlank { owner.composedName }
            // A device contact's relations kept in Parley only count too: they are how it relates to a private contact.
            (owner.relations + ParleyRelationRows.decode(m.parleyRelations)).filter { RelationLinks.nameKey(it.value) in names }.forEach { rel ->
                incoming += RelationMirror.Incoming(m.lookupKey, name, ownerPrivate, RelationMirrors.rowOf(rel), RelationMirror.genderOf(owner.pronouns))
            }
        }
        val own = self.relations.map(RelationMirrors::rowOf)
        val corrections = c.people.relationMirrors.corrections()
        return RelationMirror.fromOthers(incoming, own, selfPrivate, privateShown, key, corrections)
            .mapNotNull { r -> navs[r.ownerKey]?.let { RelationFromOther(it, r.row, r.ownerKey, r.computed) } }
    }

    /**
     * The other contacts whose relations link to [key]. Between two device contacts the opposite row is written, so
     * only pairs with a private contact are read, and a private one only while it may be shown.
     */
    private fun linkingHere(metas: List<ContactMetaEntity>, key: String, selfPrivate: Boolean, privateShown: Boolean): List<ContactMetaEntity> =
        metas.filter { m ->
            val ownerPrivate = ContactRef.isPrivateKey(m.lookupKey)
            val pair = (selfPrivate || ownerPrivate) && (!ownerPrivate || privateShown)
            m.lookupKey != key && pair && RelationLinks.decode(m.relationLinks).values.any { it.lookupKey == key }
        }

    /** The other contact (its navigation id and details), or null when it is gone or can't be read now. */
    private suspend fun owner(c: DataContainer, m: ContactMetaEntity, private: Boolean): Pair<Long, ContactDetails>? {
        if (!private) {
            val (id, _) = c.contacts.currentOf(m.lookupKey, m.contactId) ?: return null
            return c.contacts.details(id)?.let { id to it }
        }
        val v = ContactRef.vaultIdOf(m.lookupKey) ?: return null
        val summary = c.vault.summariesNow().firstOrNull { it.id == v } ?: return null
        val details = try {
            c.vault.details(v)
        } catch (_: VaultCrypto.LockedException) {
            null
        } catch (_: VaultCrypto.KeyUnavailableException) {
            null
        }
        return details?.let { ContactRef.Private(v).navId to it.copy(displayName = summary.name) }
    }
}
