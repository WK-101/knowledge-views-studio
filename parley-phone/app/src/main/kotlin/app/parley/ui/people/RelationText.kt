package app.parley.ui.people

import android.content.res.Resources
import app.parley.R
import app.parley.common.people.RelationType
import app.parley.common.people.RelationTypes

/** I5: localised names of the relation types. The stored value stays the English label (see [RelationTypes]). */
object RelationText {
    fun label(res: Resources, t: RelationType): String = labelRes(t)?.let(res::getString) ?: t.label

    private fun labelRes(t: RelationType): Int? =
        when (t.key) {
            "spouse" -> R.string.rel_spouse
            "partner" -> R.string.rel_partner
            "domestic-partner" -> R.string.rel_domestic_partner
            "child" -> R.string.rel_child
            "parent" -> R.string.rel_parent
            "mother" -> R.string.rel_mother
            "father" -> R.string.rel_father
            "sister" -> R.string.rel_sister
            "brother" -> R.string.rel_brother
            "relative" -> R.string.rel_relative
            "friend" -> R.string.rel_friend
            "manager" -> R.string.rel_manager
            "assistant" -> R.string.rel_assistant
            "referred-by" -> R.string.rel_referred_by
            "contact" -> R.string.rel_contact
            "acquaintance" -> R.string.rel_acquaintance
            "met" -> R.string.rel_met
            "co-worker" -> R.string.rel_co_worker
            "colleague" -> R.string.rel_colleague
            "co-resident" -> R.string.rel_co_resident
            "neighbor" -> R.string.rel_neighbor
            "sibling" -> R.string.rel_sibling
            "kin" -> R.string.rel_kin
            "muse" -> R.string.rel_muse
            "crush" -> R.string.rel_crush
            "date" -> R.string.rel_date
            "sweetheart" -> R.string.rel_sweetheart
            "me" -> R.string.rel_me
            "agent" -> R.string.rel_agent
            "emergency" -> R.string.rel_emergency
            "wife" -> R.string.rel_wife
            "husband" -> R.string.rel_husband
            "fiance" -> R.string.rel_fiance
            "girlfriend" -> R.string.rel_girlfriend
            "boyfriend" -> R.string.rel_boyfriend
            "ex-partner" -> R.string.rel_ex_partner
            "son" -> R.string.rel_son
            "daughter" -> R.string.rel_daughter
            "grandparent" -> R.string.rel_grandparent
            "grandmother" -> R.string.rel_grandmother
            "grandfather" -> R.string.rel_grandfather
            "grandchild" -> R.string.rel_grandchild
            "grandson" -> R.string.rel_grandson
            "granddaughter" -> R.string.rel_granddaughter
            "aunt" -> R.string.rel_aunt
            "uncle" -> R.string.rel_uncle
            "niece" -> R.string.rel_niece
            "nephew" -> R.string.rel_nephew
            "cousin" -> R.string.rel_cousin
            "stepmother" -> R.string.rel_stepmother
            "stepfather" -> R.string.rel_stepfather
            "stepson" -> R.string.rel_stepson
            "stepdaughter" -> R.string.rel_stepdaughter
            "stepsister" -> R.string.rel_stepsister
            "stepbrother" -> R.string.rel_stepbrother
            "mother-in-law" -> R.string.rel_mother_in_law
            "father-in-law" -> R.string.rel_father_in_law
            "son-in-law" -> R.string.rel_son_in_law
            "daughter-in-law" -> R.string.rel_daughter_in_law
            "sister-in-law" -> R.string.rel_sister_in_law
            "brother-in-law" -> R.string.rel_brother_in_law
            "godparent" -> R.string.rel_godparent
            "godchild" -> R.string.rel_godchild
            "guardian" -> R.string.rel_guardian
            "roommate" -> R.string.rel_roommate
            "classmate" -> R.string.rel_classmate
            "teacher" -> R.string.rel_teacher
            "student" -> R.string.rel_student
            "mentor" -> R.string.rel_mentor
            "boss" -> R.string.rel_boss
            "employee" -> R.string.rel_employee
            "client" -> R.string.rel_client
            "supplier" -> R.string.rel_supplier
            "doctor" -> R.string.rel_doctor
            "caregiver" -> R.string.rel_caregiver
            "babysitter" -> R.string.rel_babysitter
            "landlord" -> R.string.rel_landlord
            "tenant" -> R.string.rel_tenant
            else -> null
        }

    fun group(res: Resources, g: RelationType.Group): String = res.getString(
        when (g) {
            RelationType.Group.FAMILY -> R.string.rel_group_family
            RelationType.Group.PARTNER -> R.string.rel_group_partner
            RelationType.Group.SOCIAL -> R.string.rel_group_social
            RelationType.Group.WORK -> R.string.rel_group_work
            RelationType.Group.OTHER -> R.string.rel_group_other
        },
    )

    /** Picker search: the English label or key (as stored) and the label in the current language. */
    fun search(res: Resources, query: String): List<RelationType> {
        val q = app.parley.common.TextSearch.normalize(query.trim())
        if (q.isEmpty()) return RelationTypes.all
        val english = RelationTypes.search(query)
        val local = RelationTypes.all.filter { app.parley.common.TextSearch.normalize(label(res, it)).contains(q) }
        return (local + english).distinct()
    }
}
