package app.parley.common.fuzz

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.KdfParams
import app.parley.common.backup.KdfPolicy
import app.parley.common.backup.Recipient
import app.parley.common.backup.Unlock
import app.parley.common.qr.QrParser
import app.parley.common.qr.QrPayload
import app.parley.common.security.Bounded
import app.parley.common.spam.Ed25519
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackBuilder
import app.parley.common.spam.PackException
import app.parley.common.spam.PackIndex
import app.parley.common.spam.PackManifest
import app.parley.common.sync.shared.SharedLabelInvites
import app.parley.common.templates.RuleTemplates
import app.parley.common.templates.TemplateException
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzes what arrives as a code or a file someone else made: scanned QR text, Parley links (rule packs, label
 * invitations), the encrypted envelope behind contact QR codes and invitations, and spam-list packs.
 */
class CodeFuzzTest {
    private fun text(b: ByteArray) = String(b, Charsets.UTF_8)

    @Test fun qrParsingNeverCrashesAndStaysBounded() {
        val target = Fuzz.Target(
            "qr", iterations = 2_000,
            dictionary = listOf(
                "BEGIN:VCARD\n", "END:VCARD", "MECARD:", "BIZCARD:", "WIFI:", "MATMSG:", "BEGIN:VEVENT\n", "tel:", "smsto:", "mailto:", "geo:",
                "https://", "parley://", "?d=", "\\;", "\\:", ";;", "%", "%2", "#", "@", "\n", "﻿", "wa.me/", "t.me/", "signal.me/#p/",
                "DTSTART:", "TZID=", "T:WPA;", "S:", "P:", ",", "?q=", "&body=",
            ),
        )
        Fuzz.run(target) { input -> checkQr(text(input)) }
        // Longer than any QR code holds: read as plain text, cut.
        val long = "BEGIN:VCARD\n" + "N:a;b\n".repeat(QrParser.MAX_INPUT)
        val p = QrParser.parse(long)
        assertTrue(p is QrPayload.Text && p.truncated)
    }

    private fun checkQr(t: String) {
        val p = QrParser.parse(t)
        assertTrue("raw longer than the input", p.raw.length <= minOf(t.length, QrParser.MAX_INPUT))
        when (p) {
            is QrPayload.Contact -> assertTrue("too many contacts", p.records.size <= QrParser.MAX_CARDS)
            is QrPayload.Parley -> assertTrue(p.raw.startsWith("parley://"))
            is QrPayload.Geo -> assertTrue(p.lat in -90.0..90.0 && p.lon in -180.0..180.0)
            else -> Unit
        }
    }

    @Test fun ruleLinksNeverCrash() {
        val sk = ByteArray(32) { it.toByte() }
        val signed = RuleTemplates.sign(
            RuleTemplates.parse("""{"id":"fuzz.rules","name":"Fuzz","rules":[{"type":"PREFIX","pattern":"+1900"},{"type":"EXACT","pattern":"+15551234"}]}"""),
            sk,
        )
        val target = Fuzz.Target(
            "links", iterations = 1_500,
            dictionary = listOf("parley://template?d=", "d=", "&", "H4sI", "%", "=", "-", "_"),
            allowed = setOf(TemplateException::class.java),
            extraSeeds = listOf(RuleTemplates.toLink(signed).toByteArray(), signed.toByteArray()),
        )
        Fuzz.run(target) { input ->
            val t = text(input)
            RuleTemplates.fromLink(t)
            if (t.trimStart().startsWith("{")) {
                try {
                    RuleTemplates.open(t)
                } catch (_: TemplateException) {
                    // A file that isn't a valid signed template: the screen says so.
                }
            }
        }
    }

    @Test fun invitationsNeverCrash() {
        val target = Fuzz.Target(
            "links", iterations = 1_500,
            dictionary = listOf(
                """{"label":"""", """"epoch":1,""", """"inviter":"""", """"exp":9999999999999,""", """"ticketEpoch":1,""", """"invite":"""",
                """"sig":"""", """"key":"""", """"anchor":"""", """"title":"""", "}", "\"", "AAAA", "null", "[]", "1e999",
            ),
            extraSeeds = listOf(
                """{"label":"abcdef0123456789","epoch":1,"inviter":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","exp":1,"ticketEpoch":1}""".toByteArray(),
            ),
        )
        Fuzz.run(target) { input ->
            SharedLabelInvites.decode(input, now = 0L)
            if (SharedLabelInvites.isLink(text(input))) {
                // A link with a wrong passcode must fail fast (the envelope refuses it before or right after the key).
                SharedLabelInvites.fromLink(text(input).take(LINK_CHARS), "ABCD-EFGH", now = 0L)
            }
        }
    }

    @Test fun theEncryptedEnvelopeRefusesDamagedInput() {
        // The envelope behind encrypted contact QR codes, invitations and simple-mode setups. A cheap KDF keeps the
        // run short; mutations of the header and body hit the same parsing as the real settings.
        val kdf = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
        val random = java.security.SecureRandom.getInstance("SHA1PRNG").apply { setSeed(SEED) }
        val plain = Random(SEED).nextBytes(PLAIN_BYTES)
        val sealed = BackupCrypto.encryptBytes(plain, listOf(Recipient.Passphrase("ABCDEFGH".toCharArray())), kdf, random)
        val target = Fuzz.Target(
            "links", iterations = 400,
            allowed = setOf(IOException::class.java, GeneralSecurityException::class.java),
            extraSeeds = listOf(sealed),
        )
        Fuzz.run(target) { input ->
            val opened = BackupCrypto.decryptBytes(input, Unlock.Passphrase("ABCDEFGH".toCharArray()), KdfPolicy.exactly(kdf))
            // Authenticated: anything that opens is exactly what was sealed.
            assertTrue("a damaged envelope opened", opened.contentEquals(plain))
        }
        assertEquals(plain.toList(), BackupCrypto.decryptBytes(sealed, Unlock.Passphrase("ABCDEFGH".toCharArray()), KdfPolicy.exactly(kdf)).toList())
    }

    @Test fun gunzipStaysWithinItsCap() {
        val target = Fuzz.Target(
            "links", iterations = 1_500,
            allowed = setOf(IOException::class.java),
            extraSeeds = listOf(gzip(ByteArray(10_000)), gzip("parley".repeat(100).toByteArray())),
        )
        Fuzz.run(target) { input ->
            assertTrue(Bounded.gunzip(input, Bounded.Caps.QR_GUNZIP).size <= Bounded.Caps.QR_GUNZIP)
        }
    }

    @Test fun listPacksNeverCrashAndParsedOnesAreConsistent() {
        val sk = ByteArray(32) { (it * 7).toByte() }
        val packs = listOf(true, false).map { signed ->
            val b = PackBuilder(PackManifest(id = "fuzz.pack", name = "Fuzz", version = 1, categories = mapOf("1" to "Telemarketing")))
            b.addNumber("+1 855 555 0100", 1, 90)
            b.addNumber("+33 6 99 99 99 99", 1, 70)
            b.addRange("+33162", 1, 80)
            b.build(if (signed) sk else null, now = 1_700_000_000_000L)
        }
        val target = Fuzz.Target(
            "listpack-ranges", iterations = 1_500,
            dictionary = listOf("manifest.json", "numbers.bin", "ranges.txt", "signature.sig", "PK\u0003\u0004", "{", "\"format\":1", "+44 1 90\n"),
            allowed = setOf(PackException::class.java),
            extraSeeds = packs,
        )
        Fuzz.run(target) { input ->
            val ranges = ListPack.parseRanges(text(input))
            assertTrue(ranges.all { it.prefix.startsWith("+") && it.category in 0..255 && it.score in 0..100 })
            val p = ListPack.parse(input)
            assertTrue(p.ranges.all { it.prefix.startsWith("+") })
            PackIndex.of(p).lookup("+18555550100", "US", useRanges = true)
        }
        assertEquals(Ed25519.fingerprint(Ed25519.publicKey(sk)), ListPack.parse(packs[0]).fingerprint)
    }

    private fun gzip(b: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private companion object {
        const val SEED = 42L
        const val PLAIN_BYTES = 300
        const val LINK_CHARS = 2_000
    }
}
