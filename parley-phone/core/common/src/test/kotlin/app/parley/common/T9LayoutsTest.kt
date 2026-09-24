package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K2 separators, K6 keypad alphabets, Arabic-Indic digits. */
class T9LayoutsTest {
    private fun m(q: String, name: String, layout: KeypadLayout = KeypadLayout.LATIN) = T9.match(q, T9.Encoded(name, layout), emptyList())

    // ---- K2: 0 = space, 1 = punctuation ----

    @Test fun zero_is_a_space_between_words() {
        val r = m("56460764", "John Smith")!!
        assertEquals(listOf(0..3, 5..7), r.nameRanges)
        // Word prefixes on both sides of the space.
        assertNotNull(m("5607", "John Smith"))
        // Trailing 0: another word must follow.
        assertNotNull(m("56460", "John Smith"))
        assertNull(m("764840", "John Smith"))
    }

    @Test fun one_is_punctuation_between_words() {
        assertNotNull(m("6127436", "O'Brien"))
        assertNotNull(m("5326158", "Jean-Luc"))
        // A space is not punctuation, and punctuation is not a space.
        assertNull(m("5646176484", "John Smith"))
        assertNull(m("6027436", "O'Brien"))
    }

    @Test fun separators_need_letters_on_both_sides() {
        assertNull(m("564600764", "John Smith"))
        // A query starting with 0 or 1 is a number, not a separator search.
        assertNull(m("05646", "John Smith"))
    }

    @Test fun separators_do_not_break_digit_names() {
        assertNotNull(m("101", "Room 101"))
    }

    // ---- K6: layouts ----

    @Test fun every_layout_letter_has_its_key() {
        for (layout in KeypadLayout.entries) {
            for (d in '2'..'9') {
                for (c in layout.lettersFor(d)) {
                    assertEquals("${layout.name} $c", d, T9.digitFor(c, layout))
                }
            }
        }
    }

    @Test fun layouts_disagree_and_the_chosen_one_decides() {
        assertEquals('4', T9.digitFor('і', KeypadLayout.UKRAINIAN))
        assertEquals('3', T9.digitFor('є', KeypadLayout.UKRAINIAN))
        assertEquals('2', T9.digitFor('ґ', KeypadLayout.UKRAINIAN))
        assertEquals('6', T9.digitFor('ў', KeypadLayout.BELARUSIAN))
        // Serbian ћ is on 6 in the Serbian layout, and with ч on 7 otherwise.
        assertEquals('6', T9.digitFor('ћ', KeypadLayout.SERBIAN))
        assertEquals('7', T9.digitFor('ћ', KeypadLayout.LATIN))
        assertNotNull(m("4", "Ірина", KeypadLayout.UKRAINIAN))
    }

    @Test fun russian_yo_is_the_cyrillic_letter() {
        // "Алёна": ё must not be read as Latin ë.
        assertEquals(T9.encode("Алена"), T9.encode("Алёна"))
        assertNotNull(m("24", "Алёна"))
    }

    @Test fun hebrew_final_forms_share_the_base_key() {
        val l = KeypadLayout.HEBREW
        assertEquals(T9.digitFor('מ', l), T9.digitFor('ם', l))
        assertEquals(T9.digitFor('נ', l), T9.digitFor('ן', l))
        assertEquals(T9.digitFor('כ', l), T9.digitFor('ך', l))
        assertEquals(T9.digitFor('פ', l), T9.digitFor('ף', l))
        assertEquals(T9.digitFor('צ', l), T9.digitFor('ץ', l))
        assertNotNull(m("222", "דוד", l)) // David
        // Found even with the Latin-only layout (fallback).
        assertNotNull(m("222", "דוד"))
    }

    @Test fun arabic_vowel_marks_and_tatweel_are_ignored() {
        val plain = "محمد"
        val marked = "مُحَمَّد"
        val stretched = "محـمد"
        val code = T9.tokenize(plain, KeypadLayout.ARABIC).single().code
        assertEquals("8685", code)
        assertEquals(code, T9.tokenize(marked, KeypadLayout.ARABIC).single().code)
        assertEquals(code, T9.tokenize(stretched, KeypadLayout.ARABIC).single().code)
        val r = m(code, marked, KeypadLayout.ARABIC)!!
        // The highlight covers the whole written word, marks included.
        assertEquals(listOf(0 until marked.length), r.nameRanges)
        // Hamza forms of alef share its key.
        assertEquals(T9.digitFor('ا', KeypadLayout.ARABIC), T9.digitFor('أ', KeypadLayout.ARABIC))
        assertEquals(T9.digitFor('ا', KeypadLayout.ARABIC), T9.digitFor('إ', KeypadLayout.ARABIC))
    }

    @Test fun arabic_indic_digits_become_ascii() {
        assertEquals('3', T9.asciiDigit('٣'))
        assertEquals('7', T9.asciiDigit('۷'))
        assertNull(T9.asciiDigit('a'))
        assertEquals("0501234567", PhoneNumbers.clean("٠٥٠١٢٣٤٥٦٧"))
        assertEquals("0501234567", PhoneNumbers.digits("۰۵۰۱۲۳۴۵۶۷"))
    }

    @Test fun combining_accents_do_not_split_words() {
        val decomposed = "José García"
        assertEquals(2, T9.tokenize(decomposed).size)
        assertNotNull(m("5673", decomposed))
    }

    @Test fun key_letters_for_display() {
        assertEquals("АБВГ", KeypadLayout.RUSSIAN.lettersFor('2'))
        assertEquals("АБВГҐ", KeypadLayout.UKRAINIAN.lettersFor('2'))
        assertEquals("", KeypadLayout.LATIN.lettersFor('2'))
        assertEquals("", KeypadLayout.RUSSIAN.lettersFor('1'))
        assertEquals("דהו", KeypadLayout.HEBREW.lettersFor('2'))
    }

    @Test fun layout_from_locale() {
        assertEquals(KeypadLayout.UKRAINIAN, KeypadLayout.forLocale("uk"))
        assertEquals(KeypadLayout.HEBREW, KeypadLayout.forLocale("iw"))
        assertEquals(KeypadLayout.SERBIAN, KeypadLayout.forLocale("sr", "Cyrl"))
        assertEquals(KeypadLayout.LATIN, KeypadLayout.forLocale("sr", "Latn"))
        assertEquals(KeypadLayout.LATIN, KeypadLayout.forLocale("fr"))
    }

    @Test fun suggestions_from_contact_names() {
        val s = KeypadLayout.suggest(sequenceOf("Олексій", "Ігор", "Борис", "Γιώργος", "John"))
        assertEquals(KeypadLayout.UKRAINIAN, s.first())
        assertTrue(KeypadLayout.RUSSIAN in s)
        assertTrue(KeypadLayout.GREEK in s)
        assertTrue(KeypadLayout.suggest(sequenceOf("John", "Émile")).isEmpty())
        assertEquals(listOf(KeypadLayout.ARABIC), KeypadLayout.suggest(sequenceOf("محمد")))
    }
}
