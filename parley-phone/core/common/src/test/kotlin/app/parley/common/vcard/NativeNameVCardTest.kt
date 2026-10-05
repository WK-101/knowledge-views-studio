package app.parley.common.vcard

import app.parley.common.people.NativeName
import app.parley.common.people.NativeNames
import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name in their own language, several languages and citizenship through vCard 4.0, older cards and CSV. */
class NativeNameVCardTest {
    private val name = row(Mime.NAME, Col.D1 to "Ivan Petrov", Col.D2 to "Ivan", Col.D3 to "Petrov")
    private val native = row(
        Mime.NICKNAME, *NativeNames.rowValues(NativeName("Иван Петров", "Иван", "Петров", "ru")).toList().toTypedArray(),
    )

    @Test fun the_native_name_is_an_altid_alternative_of_fn_and_n_and_a_labelled_nickname() {
        val r = record("Ivan Petrov", name, native)
        val text = unfolded(r)
        assertTrue(text, Regex("FN;ALTID=1:Ivan Petrov").containsMatchIn(text))
        assertTrue(text, Regex("FN;(ALTID=1;LANGUAGE=ru|LANGUAGE=ru;ALTID=1):Иван Петров").containsMatchIn(text))
        assertTrue(text, Regex("N;(ALTID=1;LANGUAGE=ru|LANGUAGE=ru;ALTID=1):Петров;Иван;").containsMatchIn(text))
        assertTrue(text, Regex("item\\d+\\.NICKNAME;.*LANGUAGE=ru.*:Иван Петров").containsMatchIn(text))
        assertTrue(text, Regex("item\\d+\\.X-ABLabel:Name in Russian").containsMatchIn(text))
        val back = assertLossless(r)
        val n = back.rows(Mime.NICKNAME).single()
        assertEquals(NativeName("Иван Петров", "Иван", "Петров", "ru"), NativeNames.fromRow { n[it] })
        // The main name is still the everyday one.
        assertEquals("Ivan Petrov", back.displayName)
        assertEquals("Ivan", back.rows(Mime.NAME).single()[Col.D2])
    }

    @Test fun a_native_name_synced_without_its_language_column_round_trips_by_its_label() {
        val synced = row(Mime.NICKNAME, Col.D1 to "王伟", Col.D2 to "0", Col.D3 to "Name in Chinese")
        val r = record("Wang Wei", row(Mime.NAME, Col.D1 to "Wang Wei", Col.D2 to "Wei", Col.D3 to "Wang"), synced)
        val text = unfolded(r)
        assertTrue(text, Regex("FN;(ALTID=1;LANGUAGE=zh|LANGUAGE=zh;ALTID=1):王伟").containsMatchIn(text))
        val back = assertLossless(r)
        assertEquals("zh", NativeNames.fromRow { back.rows(Mime.NICKNAME).single()[it] }.language)
    }

    @Test fun another_apps_v4_card_with_alternative_fn_and_n_makes_a_native_name() {
        val card = """
            BEGIN:VCARD
            VERSION:4.0
            FN;ALTID=1;LANGUAGE=en:Ivan Petrov
            FN;ALTID=1;LANGUAGE=ru:Иван Петров
            N;ALTID=2;LANGUAGE=en:Petrov;Ivan;;;
            N;ALTID=2;LANGUAGE=ru:Петров;Иван;;;
            END:VCARD
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val r = VCardStream.readAll(card).first.single()
        assertEquals("Ivan Petrov", r.displayName)
        val n = NativeNames.fromRow { r.rows(Mime.NICKNAME).single()[it] }
        assertEquals(NativeName("Иван Петров", "Иван", "Петров", "ru"), n)
        assertEquals("Name in Russian", r.rows(Mime.NICKNAME).single()[Col.D3])
    }

    @Test fun a_v3_nickname_with_a_language_in_another_script_is_the_native_name() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Giorgos\r\nN:;Giorgos;;;\r\nNICKNAME;LANGUAGE=el:Γιώργος\r\nNICKNAME:Gio\r\nEND:VCARD\r\n"
        val r = VCardStream.readAll(card).first.single()
        val rows = r.rows(Mime.NICKNAME)
        val native = rows.single { NativeNames.isRow({ c -> it[c] }) }
        assertEquals("Γιώργος", native[Col.D1])
        assertEquals("el", NativeNames.fromRow { native[it] }.language)
        assertEquals("Gio", rows.single { !NativeNames.isRow({ c -> it[c] }) }[Col.D1])
    }

    @Test fun several_languages_keep_their_order_with_pref() {
        val r = record(
            "Ivan Petrov", name,
            row(Mime.LANGUAGE, Col.D1 to "ru", primary = true),
            row(Mime.LANGUAGE, Col.D1 to "en"),
            row(Mime.LANGUAGE, Col.D1 to "de"),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("LANG;PREF=1:ru"))
        assertTrue(text, text.contains("LANG;PREF=2:en"))
        assertTrue(text, text.contains("LANG;PREF=3:de"))
        val back = assertLossless(r)
        assertEquals(listOf("ru", "en", "de"), back.rows(Mime.LANGUAGE).map { it[Col.D1] })
        assertTrue(back.rows(Mime.LANGUAGE).first().isPrimary)
    }

    @Test fun citizenship_round_trips_as_x_parley_citizenship() {
        val r = record("Ivan Petrov", name, row(Mime.CITIZENSHIP, Col.D1 to "RU"), row(Mime.CITIZENSHIP, Col.D1 to "DE"))
        val text = unfolded(r)
        assertTrue(text, text.contains("X-PARLEY-CITIZENSHIP:RU"))
        assertTrue(text, text.contains("X-PARLEY-CITIZENSHIP:DE"))
        assertEquals(listOf("RU", "DE"), assertLossless(r).rows(Mime.CITIZENSHIP).map { it[Col.D1] })
    }

    @Test fun csv_carries_the_native_name_languages_and_citizenship() {
        val r = record(
            "Ivan Petrov", name, native,
            row(Mime.NICKNAME, Col.D1 to "Vanya"),
            row(Mime.LANGUAGE, Col.D1 to "ru"), row(Mime.LANGUAGE, Col.D1 to "en"),
            row(Mime.CITIZENSHIP, Col.D1 to "RU"), row(Mime.CITIZENSHIP, Col.D1 to "DE"),
        )
        val csv = ContactCsv.writeAll(listOf(r))
        val back = ContactCsv.readAll(csv).first.single()
        // The nickname column holds the nickname only; the native name has its own columns.
        assertEquals(listOf("Vanya"), back.rows(Mime.NICKNAME).filter { !NativeNames.isRow({ c -> it[c] }) }.map { it[Col.D1] })
        val n = back.rows(Mime.NICKNAME).single { NativeNames.isRow({ c -> it[c] }) }
        assertEquals("Иван Петров", n[Col.D1])
        assertEquals("ru", NativeNames.fromRow { n[it] }.language)
        assertEquals(listOf("ru", "en"), back.rows(Mime.LANGUAGE).map { it[Col.D1] })
        assertEquals(listOf("RU", "DE"), back.rows(Mime.CITIZENSHIP).map { it[Col.D1] })
    }
}
