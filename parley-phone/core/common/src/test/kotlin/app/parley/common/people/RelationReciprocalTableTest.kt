package app.parley.common.people

import app.parley.common.people.RelationMirror.Correction
import app.parley.common.people.RelationMirror.Gender
import app.parley.common.people.RelationMirror.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole reciprocal table: for every relation type, what the other contact shows when the one who holds the
 * relation is she/her, he/him or not known; both directions; corrections the user makes; and the Android and vCard
 * forms of every type.
 */
class RelationReciprocalTableTest {
    /** Type → (other side when the holder's gender is unknown, when she/her, when he/him). */
    private val table: Map<String, Triple<String, String, String>> = mapOf(
        "spouse" to t("spouse", "wife", "husband"),
        "wife" to t("spouse", "wife", "husband"),
        "husband" to t("spouse", "wife", "husband"),
        "ex-spouse" to t("ex-spouse", "ex-wife", "ex-husband"),
        "ex-wife" to t("ex-spouse", "ex-wife", "ex-husband"),
        "ex-husband" to t("ex-spouse", "ex-wife", "ex-husband"),
        "girlfriend" to t("partner", "girlfriend", "boyfriend"),
        "boyfriend" to t("partner", "girlfriend", "boyfriend"),
        "partner" to same("partner"),
        "domestic-partner" to same("domestic-partner"),
        "fiance" to same("fiance"),
        "ex-partner" to same("ex-partner"),
        "date" to same("date"),
        "sweetheart" to same("sweetheart"),
        "parent" to t("child", "daughter", "son"),
        "mother" to t("child", "daughter", "son"),
        "father" to t("child", "daughter", "son"),
        "child" to t("parent", "mother", "father"),
        "son" to t("parent", "mother", "father"),
        "daughter" to t("parent", "mother", "father"),
        "sibling" to t("sibling", "sister", "brother"),
        "sister" to t("sibling", "sister", "brother"),
        "brother" to t("sibling", "sister", "brother"),
        "half-sibling" to t("half-sibling", "half-sister", "half-brother"),
        "half-sister" to t("half-sibling", "half-sister", "half-brother"),
        "half-brother" to t("half-sibling", "half-sister", "half-brother"),
        "stepparent" to t("stepchild", "stepdaughter", "stepson"),
        "stepmother" to t("stepchild", "stepdaughter", "stepson"),
        "stepfather" to t("stepchild", "stepdaughter", "stepson"),
        "stepchild" to t("stepparent", "stepmother", "stepfather"),
        "stepson" to t("stepparent", "stepmother", "stepfather"),
        "stepdaughter" to t("stepparent", "stepmother", "stepfather"),
        "stepsibling" to t("stepsibling", "stepsister", "stepbrother"),
        "stepsister" to t("stepsibling", "stepsister", "stepbrother"),
        "stepbrother" to t("stepsibling", "stepsister", "stepbrother"),
        "grandparent" to t("grandchild", "granddaughter", "grandson"),
        "grandmother" to t("grandchild", "granddaughter", "grandson"),
        "grandfather" to t("grandchild", "granddaughter", "grandson"),
        "grandchild" to t("grandparent", "grandmother", "grandfather"),
        "grandson" to t("grandparent", "grandmother", "grandfather"),
        "granddaughter" to t("grandparent", "grandmother", "grandfather"),
        "aunt-or-uncle" to t("niece-or-nephew", "niece", "nephew"),
        "aunt" to t("niece-or-nephew", "niece", "nephew"),
        "uncle" to t("niece-or-nephew", "niece", "nephew"),
        "niece-or-nephew" to t("aunt-or-uncle", "aunt", "uncle"),
        "niece" to t("aunt-or-uncle", "aunt", "uncle"),
        "nephew" to t("aunt-or-uncle", "aunt", "uncle"),
        "parent-in-law" to t("child-in-law", "daughter-in-law", "son-in-law"),
        "mother-in-law" to t("child-in-law", "daughter-in-law", "son-in-law"),
        "father-in-law" to t("child-in-law", "daughter-in-law", "son-in-law"),
        "child-in-law" to t("parent-in-law", "mother-in-law", "father-in-law"),
        "son-in-law" to t("parent-in-law", "mother-in-law", "father-in-law"),
        "daughter-in-law" to t("parent-in-law", "mother-in-law", "father-in-law"),
        "sibling-in-law" to t("sibling-in-law", "sister-in-law", "brother-in-law"),
        "sister-in-law" to t("sibling-in-law", "sister-in-law", "brother-in-law"),
        "brother-in-law" to t("sibling-in-law", "sister-in-law", "brother-in-law"),
        "godparent" to t("godchild", "goddaughter", "godson"),
        "godmother" to t("godchild", "goddaughter", "godson"),
        "godfather" to t("godchild", "goddaughter", "godson"),
        "godchild" to t("godparent", "godmother", "godfather"),
        "goddaughter" to t("godparent", "godmother", "godfather"),
        "godson" to t("godparent", "godmother", "godfather"),
        "cousin" to same("cousin"),
        "relative" to same("relative"),
        "kin" to same("kin"),
        "guardian" to same("ward"),
        "ward" to same("guardian"),
        "friend" to same("friend"),
        "contact" to same("contact"),
        "acquaintance" to same("acquaintance"),
        "met" to same("met"),
        "co-resident" to same("co-resident"),
        "roommate" to same("roommate"),
        "neighbor" to same("neighbor"),
        "classmate" to same("classmate"),
        "teacher" to same("student"),
        "student" to same("teacher"),
        "mentor" to same("mentee"),
        "mentee" to same("mentor"),
        "co-worker" to same("co-worker"),
        "colleague" to same("colleague"),
        "manager" to same("report"),
        "boss" to same("report"),
        "report" to same("manager"),
        "assistant" to same("manager"),
        "employer" to same("employee"),
        "employee" to same("employer"),
        "client" to same("supplier"),
        "supplier" to same("client"),
        "agent" to same("client"),
        "landlord" to same("tenant"),
        "tenant" to same("landlord"),
        "doctor" to same("patient"),
        "patient" to same("doctor"),
        "referred-by" to same("referral"),
        "referral" to same("referred-by"),
        "related" to same("related"),
    )

    /** Types with no fair opposite: the other side shows nothing. */
    private val oneSided = setOf("crush", "muse", "me", "emergency", "caregiver", "babysitter")

    /** Words that are one way round only: going there and back lands on the usual word ("Boss" → "Direct report" → "Manager"). */
    private val synonyms = mapOf("boss" to "manager", "assistant" to "report", "agent" to "supplier")

    private fun t(unknown: String, female: String, male: String) = Triple(unknown, female, male)
    private fun same(k: String) = Triple(k, k, k)
    private fun inv(key: String, g: Gender) = RelationMirror.inverse(RelationTypes.byKey(key)!!, g)?.key

    /** The gender a type itself says the person is (a wife is she/her), or unknown for a neutral word. */
    private fun genderOf(key: String): Gender = when {
        table.values.any { it.second == key && it.first != key && it.second != it.third } -> Gender.FEMALE
        table.values.any { it.third == key && it.first != key && it.second != it.third } -> Gender.MALE
        else -> Gender.UNKNOWN
    }

    @Test fun every_type_is_in_the_table_or_has_no_fair_opposite() {
        val keys = RelationTypes.all.map { it.key }.toSet()
        assertEquals(keys, table.keys + oneSided)
        assertTrue((table.keys intersect oneSided).isEmpty())
    }

    @Test fun each_type_maps_by_the_holders_gender_and_to_the_neutral_word_when_unknown() {
        for ((key, expected) in table) {
            assertEquals("$key, gender unknown", expected.first, inv(key, Gender.UNKNOWN))
            assertEquals("$key, she/her", expected.second, inv(key, Gender.FEMALE))
            assertEquals("$key, he/him", expected.third, inv(key, Gender.MALE))
        }
        oneSided.forEach { key -> Gender.entries.forEach { g -> assertNull("$key", inv(key, g)) } }
    }

    @Test fun both_directions_agree() {
        // X is Y's [key]; Y (holder of gender g) shows on X as [back]; X's own relation [back] to Y, with X's gender as
        // [key] says it, gives [key] again: Wife ↔ Husband, Mother ↔ Son, Spouse ↔ Spouse.
        for (key in table.keys) {
            for (g in Gender.entries) {
                val back = inv(key, g) ?: error("$key has an opposite")
                val expected = when {
                    key in synonyms -> synonyms.getValue(key)
                    // Girlfriend held by someone whose gender isn't known is "Partner" there, and Partner stays Partner.
                    key in setOf("girlfriend", "boyfriend") && g == Gender.UNKNOWN -> "partner"
                    else -> key
                }
                assertEquals("$key → $back (holder $g)", expected, inv(back, genderOf(key)))
            }
        }
    }

    @Test fun pronouns_say_the_gender_and_names_never_do() {
        assertEquals(Gender.FEMALE, RelationMirror.genderOf("she/her"))
        assertEquals(Gender.FEMALE, RelationMirror.genderOf(" She / Hers "))
        assertEquals(Gender.MALE, RelationMirror.genderOf("he/him"))
        assertEquals(Gender.MALE, RelationMirror.genderOf("he"))
        assertEquals(Gender.UNKNOWN, RelationMirror.genderOf("they/them"))
        assertEquals(Gender.UNKNOWN, RelationMirror.genderOf("she/they"))
        assertEquals(Gender.UNKNOWN, RelationMirror.genderOf(""))
        assertEquals(Gender.UNKNOWN, RelationMirror.genderOf(null))
    }

    @Test fun wife_on_a_he_him_contact_shows_husband_and_spouse_otherwise() {
        // "Wife: Sam" on Alex: Sam's contact shows Alex as husband when Alex is he/him, else spouse (or wife for she/her).
        assertEquals(Row("Alex", "husband", null), RelationMirror.reciprocal("wife", null, "Alex", RelationMirror.genderOf("he/him")))
        assertEquals(Row("Alex", "spouse", null), RelationMirror.reciprocal("wife", null, "Alex", RelationMirror.genderOf("")))
        assertEquals(Row("Alex", "spouse", null), RelationMirror.reciprocal("spouse", null, "Alex"))
        // A custom label shows as typed on its own side, "Related" on the other.
        assertEquals(Row("Alex", "related", null), RelationMirror.reciprocal(null, "Bandmate", "Alex"))
    }

    @Test fun a_correction_is_remembered_for_the_pair_and_applies_on_both_sides() {
        val computed = Row("Alex", "spouse", null)
        val husband = Row("", "husband", null)
        var list = RelationMirror.remember(emptyList(), Correction("alex-key", "sam-key", computed, husband))
        // On Sam's page (and whenever Parley writes it) Alex is "Husband" now.
        assertEquals(Row("Alex", "husband", null), RelationMirror.corrected(computed, "alex-key", "sam-key", list))
        // Only for that pair and that relation.
        assertEquals(computed, RelationMirror.corrected(computed, "alex-key", "kim-key", list))
        assertEquals(Row("Alex", "friend", null), RelationMirror.corrected(Row("Alex", "friend", null), "alex-key", "sam-key", list))
        // A custom inverse the user typed.
        val related = Row("Alex", "related", null)
        list = RelationMirror.remember(list, Correction("alex-key", "sam-key", related, Row("", null, "Bandmate")))
        assertEquals(Row("Alex", null, "Bandmate"), RelationMirror.corrected(related, "alex-key", "sam-key", list))
        // Stored and read back.
        assertEquals(list, RelationMirror.decodeCorrections(RelationMirror.encodeCorrections(list)))
        // Correcting back to what Parley would show forgets it.
        list = RelationMirror.remember(list, Correction("alex-key", "sam-key", computed, Row("", "spouse", null)))
        assertEquals(computed, RelationMirror.corrected(computed, "alex-key", "sam-key", list))
        assertEquals(1, list.size)
    }

    @Test fun a_private_contacts_relation_shows_corrected_and_by_its_gender() {
        val ana = RelationMirror.Incoming("parley-private:7", "Ana", true, Row("Bob", "husband", null), RelationMirror.genderOf("she/her"))
        assertEquals(Row("Ana", "wife", null), RelationMirror.fromOthers(listOf(ana), emptyList(), false, true, "bob-key").single().row)
        val corrections = listOf(Correction("parley-private:7", "bob-key", Row("", "wife", null), Row("", "partner", null)))
        val shown = RelationMirror.fromOthers(listOf(ana), emptyList(), false, true, "bob-key", corrections).single()
        assertEquals(Row("Ana", "partner", null), shown.row)
        assertEquals(Row("Ana", "wife", null), shown.computed)
    }

    @Test fun every_type_round_trips_through_androids_relation_row() {
        for (t in RelationTypes.all) {
            val (type, label) = RelationTypes.toAndroid(t)
            assertEquals(t.key, RelationTypes.fromAndroid(type, label)?.key)
        }
        // Android's built-in TYPE_* values map to their types.
        val builtIn = mapOf(
            1 to "assistant", 2 to "brother", 3 to "child", 4 to "domestic-partner", 5 to "father", 6 to "friend", 7 to "manager",
            8 to "mother", 9 to "parent", 10 to "partner", 11 to "referred-by", 12 to "relative", 13 to "sister", 14 to "spouse",
        )
        builtIn.forEach { (type, key) -> assertEquals(key, RelationTypes.fromAndroid(type, null)?.key) }
    }
}
