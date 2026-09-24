package app.parley.common.templates

import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.OffHoursAllow
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.ScreeningSettings
import app.parley.common.spam.Ed25519
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RuleTemplateTest {
    /** The templates shipped in the app's assets. */
    private val assets: List<File> by lazy {
        val dir = listOf(File("../../app/src/main/assets/templates"), File("app/src/main/assets/templates")).first { it.isDirectory }
        dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected TemplateException")
        } catch (_: TemplateException) {
        }
    }

    @Test fun every_shipped_template_parses_and_cites_its_sources() {
        assertTrue(assets.size >= 8)
        val ids = HashSet<String>()
        assets.forEach { f ->
            val t = RuleTemplates.parse(f.readText())
            assertTrue("duplicate id ${t.id}", ids.add(t.id))
            if (t.warnList != null) {
                // Every regulator range cites an official page and when it was checked.
                assertTrue("${t.id} has no source", t.sources.isNotEmpty())
                t.sources.forEach { s -> assertTrue(s.url.startsWith("https://")); assertTrue(s.accessed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) }
                val p = ListPack.parse(RuleTemplates.toPack(t, now = 1L)!!)
                assertEquals(t.warnList!!.ranges.size, p.ranges.size)
                assertEquals(RuleTemplates.packId(t), p.manifest.id)
            }
            assertTrue(RuleTemplates.describe(t).isNotEmpty())
        }
    }

    @Test fun shipped_ranges_match_what_they_claim() {
        val uk = RuleTemplates.parse(assets.first { it.name.startsWith("uk-") }.readText())
        val idx = PackIndex.of(ListPack.parse(RuleTemplates.toPack(uk, 1L)!!))
        assertNotNull(idx.findRange("+447000123456"))
        assertNotNull(idx.findRange("+448445551234"))
        assertNotNull(idx.findRange("+449098790000"))
        assertNull(idx.findRange("+447700900123")) // ordinary mobile
        val it = RuleTemplates.parse(assets.first { f -> f.name.startsWith("it-") }.readText())
        val itIdx = PackIndex.of(ListPack.parse(RuleTemplates.toPack(it, 1L)!!))
        assertEquals(1, itIdx.findRange("+390844123456")!!.category)
        assertEquals(2, itIdx.findRange("+390843123456")!!.category)
    }

    @Test fun rules_and_settings_templates() {
        val foreign = RuleTemplates.parse(assets.first { it.name == "general-foreign.json" }.readText())
        val r = RuleTemplates.toRules(foreign).single()
        assertEquals(RuleType.NOT_MY_REGION, r.type)
        assertEquals(BlockAction.SILENCE, r.action)
        assertEquals(RuleKind.BLOCK, r.kind)

        val night = RuleTemplates.parse(assets.first { it.name == "general-contacts-at-night.json" }.readText())
        val before = ScreeningSettings()
        val after = night.settings!!.apply(before)
        assertTrue(after.offHours.enabled)
        assertEquals(22 * 60, after.offHours.schedule.startMinute)
        assertEquals(OffHoursAllow.CONTACTS, after.offHours.allow)
        // Uninstall restores exactly what was there.
        val snap = night.settings!!.snapshot(before)
        assertEquals(before, snap.apply(after))
        assertNull(snap.blockInvalid)

        val invalid = RuleTemplates.parse(assets.first { it.name == "general-invalid.json" }.readText())
        assertTrue(invalid.settings!!.apply(before).blockInvalid)
        assertEquals(before.blockNonContacts, invalid.settings!!.apply(before).blockNonContacts)
    }

    @Test fun rejects_bad_templates() {
        expectFailure { RuleTemplates.parse("not json") }
        expectFailure { RuleTemplates.parse("""{"id":"Bad Id","name":"x","rules":[{"type":"PREFIX","pattern":"+1900"}]}""") }
        expectFailure { RuleTemplates.parse("""{"id":"a","name":"x"}""") }
        expectFailure { RuleTemplates.parse("""{"id":"a","name":"x","rules":[{"type":"LABEL","pattern":"5"}]}""") }
        expectFailure { RuleTemplates.parse("""{"id":"a","name":"x","rules":[{"type":"PREFIX","pattern":""}]}""") }
        expectFailure { RuleTemplates.parse("""{"id":"a","name":"x","warnList":{"ranges":[{"prefix":"0900"}]}}""") }
        expectFailure { RuleTemplates.parse("""{"id":"a","name":"x","format":2,"rules":[{"type":"PREFIX","pattern":"+1900"}]}""") }
        expectFailure { RuleTemplates.parse("""{"id":"a","name":"x","sources":[{"url":"http://x.example"}],"rules":[{"type":"PREFIX","pattern":"+1900"}]}""") }
        // Unknown fields from newer versions are ignored.
        assertEquals("a", RuleTemplates.parse("""{"id":"a","name":"x","future":1,"rules":[{"type":"PREFIX","pattern":"+1900"}]}""").id)
    }

    @Test fun signed_files_and_qr_links_round_trip_and_detect_tampering() {
        val sk = Ed25519.newSecret()
        val t = RuleTemplates.fromRules(
            "family.rules", "Grandma's rules", "Me",
            listOf(
                BlockRule(pattern = "+1900", type = RuleType.PREFIX),
                BlockRule(pattern = "5", type = RuleType.LABEL, label = "Family"),
                BlockRule(pattern = "+15551234", type = RuleType.EXACT, expiresAt = 5L),
                BlockRule(pattern = "+44", type = RuleType.PREFIX, kind = RuleKind.ALLOW),
            ),
            version = 3,
        )
        assertEquals(2, t.rules.size)
        val signed = RuleTemplates.sign(t, sk)
        val opened = RuleTemplates.open(signed)
        assertEquals(t, opened.template)
        assertEquals(Ed25519.fingerprint(Ed25519.publicKey(sk)), opened.fingerprint)

        val link = RuleTemplates.toLink(signed)
        assertTrue(link.startsWith(RuleTemplates.LINK_PREFIX))
        assertTrue(RuleTemplates.fitsInQr(link))
        assertEquals(t, RuleTemplates.fromLink(link).template)

        expectFailure { RuleTemplates.open(signed.replace("+1900", "+1901")) }
        expectFailure { RuleTemplates.fromLink("parley://template?d=%%%") }
        expectFailure { RuleTemplates.open("{}") }
    }

    @Test fun gallery_state_round_trips() {
        val s = TemplateGalleryState(
            installed = listOf(InstalledTemplate("a", "A", 1, listOf(4, 5), "template.a", TemplateSettings(blockInvalid = false), 9)),
            imported = listOf(ImportedTemplate("{}", "ab cd", 3)),
        )
        assertEquals(s, TemplateGalleryState.decode(s.encode()))
        assertEquals(TemplateGalleryState(), TemplateGalleryState.decode("garbage"))
        assertFalse(TemplateGalleryState.decode(null).installed.any())
    }
}
