package app.parley.common.spam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ListPackTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun ed25519_rfc8032_vectors() {
        val sk1 = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        assertArrayEquals(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), Ed25519.publicKey(sk1))
        val sig1 = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertArrayEquals(sig1, Ed25519.sign(sk1, ByteArray(0)))
        assertTrue(Ed25519.verify(Ed25519.publicKey(sk1), ByteArray(0), sig1))

        val sk2 = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val sig2 = hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")
        assertArrayEquals(sig2, Ed25519.sign(sk2, byteArrayOf(0x72)))
        assertFalse(Ed25519.verify(Ed25519.publicKey(sk2), byteArrayOf(0x73), sig2))
    }

    @Test fun ed25519_matches_the_jdk() {
        val sk = Ed25519.newSecret()
        val msg = "parley".encodeToByteArray()
        val priv = KeyFactory.getInstance("Ed25519").generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, sk))
        val jdkSig = Signature.getInstance("Ed25519").apply { initSign(priv); update(msg) }.sign()
        assertArrayEquals(jdkSig, Ed25519.sign(sk, msg))
        assertTrue(Ed25519.verify(Ed25519.publicKey(sk), msg, jdkSig))
    }

    private fun sample(signed: Boolean): ByteArray {
        val b = PackBuilder(PackManifest(id = "test.pack", name = "Test list", version = 3, categories = mapOf("1" to "Telemarketing")))
        assertTrue(b.addNumber("+1 855 555 0100", 1, 90))
        assertTrue(b.addNumber("(212) 555-0199", 1, 40, "US"))
        assertTrue(b.addNumber("06 99 99 99 99", 1, 70, "FR"))
        assertFalse(b.addNumber("123", 1, 70, "FR"))
        assertTrue(b.addRange("+33162", 1, 80))
        return b.build(if (signed) Ed25519.newSecret() else null)
    }

    @Test fun build_parse_and_lookup() {
        val p = ListPack.parse(sample(signed = true))
        assertEquals(SignatureStatus.SIGNED, p.signature)
        assertNotNull(p.fingerprint)
        assertEquals(3, p.manifest.entries)
        val idx = PackIndex.of(p)
        assertEquals(90, idx.lookup("+18555550100", "US", useRanges = true)?.score)
        // National form, E.164 and international digits without '+'.
        assertEquals(40, idx.lookup("2125550199", "US", true)?.score)
        assertEquals(70, idx.lookup("0699999999", "FR", true)?.score)
        assertEquals(70, idx.lookup("+33 6 99 99 99 99", "DE", true)?.score)
        // Ranges only when enabled.
        assertTrue(idx.lookup("01 62 12 34 56", "FR", true)!!.range)
        assertNull(idx.lookup("01 62 12 34 56", "FR", false))
        assertNull(idx.lookup("+33612345678", "FR", true))
    }

    @Test fun unsigned_pack_is_accepted_and_marked() {
        assertEquals(SignatureStatus.UNSIGNED, ListPack.parse(sample(signed = false)).signature)
    }

    private fun rewrite(zip: ByteArray, change: (String, ByteArray) -> ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(zip)).use { i ->
                while (true) {
                    val e = i.nextEntry ?: break
                    val bytes = change(e.name, i.readBytes()) ?: continue
                    z.putNextEntry(ZipEntry(e.name))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test fun tampering_is_detected() {
        val good = sample(signed = true)
        val numbersChanged = rewrite(good) { name, b -> if (name == ListPack.NUMBERS) b.copyOf().also { it[9] = 1 } else b }
        expectFailure(numbersChanged, "checksum")
        val manifestChanged = rewrite(good) { name, b -> if (name == ListPack.MANIFEST) b.decodeToString().replace("Test list", "Evil list").encodeToByteArray() else b }
        expectFailure(manifestChanged, "signature")
        val noManifest = rewrite(good) { name, b -> if (name == ListPack.MANIFEST) null else b }
        expectFailure(noManifest, "manifest")
    }

    private fun expectFailure(zip: ByteArray, contains: String) {
        try {
            ListPack.parse(zip)
            fail("expected failure: $contains")
        } catch (e: PackException) {
            assertTrue(e.message, e.message!!.contains(contains))
        }
    }

    @Test fun builtin_arcep_pack() {
        val p = ListPack.parse(BuiltInPacks.toPack(BuiltInPacks.FRANCE_ARCEP))
        assertEquals(12, p.ranges.size)
        val idx = PackIndex.of(p)
        for (n in listOf("01 62 00 00 00", "0948123456", "+33 5 69 11 22 33")) assertNotNull(n, idx.lookup(n, "FR", true))
        assertNull(idx.lookup("01 64 00 00 00", "FR", true))
        assertEquals(listOf(BuiltInPacks.FRANCE_ARCEP), BuiltInPacks.suggestedFor("fr"))
        assertTrue(BuiltInPacks.suggestedFor("DE").isEmpty())
    }

    @Test fun ftc_csv_to_pack() {
        val csv = "Company_Phone_Number,Created_Date,Subject,Recorded_Message_Or_Robocall\r\n" +
            "8555550100,2026-01-01,Imposters,N\r\n8555550100,2026-01-02,Imposters,N\r\n2125550199,2026-01-03,Reducing your debt,Y\r\n"
        val b = PackBuilder(PackManifest(id = "gov.ftc", name = "FTC"))
        assertEquals(2, FtcCsv.addTo(b, csv))
        val idx = PackIndex.of(ListPack.parse(b.build()))
        val scam = idx.lookup("+18555550100", "US", false)!!
        assertEquals(FtcCsv.CAT_SCAM, scam.category)
        assertEquals(55, scam.score)
        assertEquals(FtcCsv.CAT_ROBOCALL, idx.lookup("+12125550199", "US", false)!!.category)
    }
}
