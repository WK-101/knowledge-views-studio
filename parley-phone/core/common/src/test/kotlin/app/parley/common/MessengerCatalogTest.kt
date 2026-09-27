package app.parley.common

import app.parley.common.people.Handle
import app.parley.common.people.HandleService
import app.parley.common.people.Handles
import app.parley.common.qr.MessengerQr
import app.parley.common.qr.QrApp
import app.parley.common.record.Messengers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessengerCatalogTest {
    /** The app manifest's `<queries>` package names, read from the source tree (the test runs in core/common). */
    private fun manifestQueries(): Set<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/main/AndroidManifest.xml").exists()) dir = dir.parentFile
        val manifest = File(requireNotNull(dir) { "app/src/main/AndroidManifest.xml not found" }, "app/src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        return Regex("""<package\s+android:name="([^"$]+)"""").findAll(queries).map { it.groupValues[1] }.toSet()
    }

    @Test fun every_catalog_package_is_in_the_manifest_queries() {
        val queries = manifestQueries()
        val missing = MessengerCatalog.ALL_PACKAGES - queries
        assertTrue("Add to <queries> in app/src/main/AndroidManifest.xml: $missing", missing.isEmpty())
    }

    @Test fun every_messenger_package_in_the_manifest_is_in_the_catalog() {
        // The other <queries> packages are GrapheneOS detection and the lists companion, not messengers.
        val others = manifestQueries().filterNot { it.startsWith("app.grapheneos.") }
        val unknown = others - MessengerCatalog.ALL_PACKAGES
        assertTrue("Messenger packages in <queries> but not in MessengerCatalog: $unknown", unknown.isEmpty())
    }

    @Test fun packages_and_account_types_are_listed_once() {
        val packages = MessengerCatalog.entries.flatMap { it.packages }
        assertEquals(packages.size, packages.toSet().size)
        val accounts = MessengerCatalog.entries.flatMap { it.accountTypes }
        assertEquals(accounts.size, accounts.toSet().size)
    }

    @Test fun derived_registries_come_from_the_catalog() {
        // Chat-by-number apps include every flavour (Molly's UnifiedPush build, Telegram beta and Plus).
        val chat = MessengerApp.entries.map { it.packageName }.toSet()
        assertTrue("im.molly.app.unifiedpush" in chat)
        assertTrue("org.telegram.plus" in chat && "org.telegram.messenger.beta" in chat)
        assertEquals(MessengerCatalog.TELEGRAM, MessengerApp.forPackage("org.telegram.plus")?.entry)
        // QR families open with every app of theirs, main app first (store page).
        assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), QrApp.WHATSAPP.packages)
        assertEquals("org.thoughtcrime.securesms", QrApp.SIGNAL.packages.first())
        assertTrue("im.molly.app.unifiedpush" in QrApp.SIGNAL.packages)
        QrApp.entries.forEach { assertTrue(it.name, it.packages.isNotEmpty()) }
        // Accounts that backups leave out: every sync adapter the catalog knows.
        assertEquals(MessengerCatalog.ACCOUNT_TYPES, Messengers.PACKAGES.toSet())
        assertTrue(Messengers.isMessengerAccount("im.molly.app.unifiedpush"))
        assertTrue(Messengers.isMessengerAccount("kik.android"))
        // Handles open in the same apps as scanned links.
        assertEquals(QrApp.TELEGRAM.packages, Handles.link(Handle(HandleService.TELEGRAM, "someone_here"))!!.packages)
        assertEquals(MessengerCatalog.XMPP.packages, Handles.link(Handle(HandleService.XMPP, "a@b.org"))!!.packages)
    }

    @Test fun known_mimetypes_come_from_the_catalog() {
        assertEquals(ReachKind.VIDEO, MessengerMimes.KNOWN["vnd.android.cursor.item/vnd.com.whatsapp.w4b.video.call"])
        assertEquals(ReachKind.PAID_CALL, MessengerMimes.KNOWN["vnd.android.cursor.item/vnd.com.viber.voip.viber_out_call_none"])
        assertEquals(MessengerCatalog.WHATSAPP_BUSINESS, MessengerCatalog.forMime("vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"))
    }

    @Test fun one_app_per_entry() {
        val web = MessengerApp.forPackage("org.telegram.messenger.web")!!
        val main = MessengerApp.of(MessengerCatalog.TELEGRAM)
        val wa = MessengerApp.of(MessengerCatalog.WHATSAPP)
        val biz = MessengerApp.of(MessengerCatalog.WHATSAPP_BUSINESS)
        assertEquals(listOf(wa, biz, main), MessengerApp.onePerApp(listOf(web, biz, main, wa)))
        assertEquals(listOf(web), MessengerApp.onePerApp(listOf(web)))
    }

    @Test fun scanned_links_use_the_catalog_schemes_and_hosts() {
        val samples = listOf(
            "https://wa.me/491511234567", "whatsapp://send?phone=491511234567", "sgnl://signal.me/#p/+491511234567",
            "https://signal.group/#abc", "tg://resolve?domain=someone", "https://t.me/someone", "viber://chat?number=%2B491511234567",
            "threema://compose?id=ABCD1234", "3mid:ABCD1234", "https://threema.id/ABCD1234", "line://ti/p/abc", "https://line.me/ti/p/abc",
            "skype:someone?chat", "https://account.wire.com/user-profile/?id=1", "matrix:u/someone:example.org", "https://matrix.to/#/@a:b.org",
            "briar://abcdef", "simplex:/contact#abc", "https://simplex.chat/contact#abc", "weixin://dl/chat", "https://m.me/someone",
            "https://instagram.com/someone", "https://snapchat.com/add/someone", "https://open.kakao.com/o/abc", "https://zalo.me/g/abc",
            "https://discord.gg/abc",
        )
        for (s in samples) {
            val found = MessengerQr.classify(s)
            assertNotNull(s, found)
            val m = found!!
            val scheme = s.substringBefore(':').lowercase()
            val entries = MessengerCatalog.entries.filter { it.qr == m.app }
            val known = if (scheme == "https") {
                val host = s.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').removePrefix("www.")
                entries.any { e -> e.hosts.any { host == it || host.endsWith(".$it") } }
            } else {
                entries.any { scheme in it.schemes }
            }
            assertTrue("$s (${m.app}) isn't described in MessengerCatalog", known)
        }
        assertEquals(MessengerCatalog.TELEGRAM, MessengerCatalog.forScheme("TG"))
    }
}
