package app.parley.common.spam

import java.util.Base64
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

    /** RFC 8032 §7.1 tests 1, 2, 3 and "SHA(abc)", through both the platform and the pure implementation. */
    @Test fun ed25519_rfc8032_vectors_on_both_paths() {
        val vectors = listOf(
            Triple(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60", "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Triple(
                "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb", "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
            Triple(
                "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7", "af82",
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            ),
            Triple(
                "833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42",
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                "dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704",
            ),
        )
        assertTrue("the JVM has Ed25519", Ed25519.platformAvailable)
        for ((sk, msg, sig) in vectors) {
            val m = hex(msg)
            assertArrayEquals(hex(sig), Ed25519.sign(hex(sk), m))
            assertArrayEquals(hex(sig), Ed25519.signPure(hex(sk), m))
            val pk = Ed25519.publicKey(hex(sk))
            assertTrue(Ed25519.verify(pk, m, hex(sig)))
            assertTrue(Ed25519.verifyPure(pk, m, hex(sig)))
            val bad = hex(sig).also { it[0] = (it[0] + 1).toByte() }
            assertFalse(Ed25519.verify(pk, m, bad))
            assertFalse(Ed25519.verifyPure(pk, m, bad))
        }
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

    @Test fun keys_are_pinned_per_pack_and_for_the_companion() {
        val sk = Ed25519.newSecret()
        fun pack(key: ByteArray?, id: String = "test.pack") = ListPack.parse(
            PackBuilder(PackManifest(id = id, name = "Test", version = 1)).apply { addNumber("+18555550100", 1, 90) }.build(key),
        )
        val signed = pack(sk)
        val installedKey = signed.manifest.publicKey
        fun state(origin: PackOrigin, fp: String?) =
            PackState(id = "test.pack", name = "Test", version = 1, fingerprint = fp, publicKey = installedKey, origin = origin)
        val installed = state(PackOrigin.FILE, signed.fingerprint)
        // Same key: fine. Another key or unsigned: refused.
        assertNull(ListPack.refusal(installed, installedKey, pack(sk), PackOrigin.FILE, null))
        assertNotNull(ListPack.refusal(installed, installedKey, pack(Ed25519.newSecret()), PackOrigin.FILE, null))
        assertNotNull(ListPack.refusal(installed, installedKey, pack(null), PackOrigin.FILE, null))
        // A built-in is never replaced by a file or the companion.
        assertNotNull(ListPack.refusal(state(PackOrigin.BUILTIN, null), null, pack(sk), PackOrigin.FILE, null))
        // The companion's lists must be signed, and with the pinned companion key once there is one.
        assertNotNull(ListPack.refusal(null, null, pack(null, "other"), PackOrigin.UPDATER, null))
        assertNull(ListPack.refusal(null, null, pack(sk, "other"), PackOrigin.UPDATER, null))
        assertNull(ListPack.refusal(null, null, pack(sk, "other"), PackOrigin.UPDATER, installedKey))
        assertNotNull(ListPack.refusal(null, null, pack(Ed25519.newSecret(), "other"), PackOrigin.UPDATER, installedKey))
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
        val manifestChanged = rewrite(good) { name, b ->
            if (name == ListPack.MANIFEST) b.decodeToString().replace("Test list", "Evil list").encodeToByteArray() else b
        }
        expectFailure(manifestChanged, "signature")
        val noManifest = rewrite(good) { name, b -> if (name == ListPack.MANIFEST) null else b }
        expectFailure(noManifest, "manifest")
    }

    @Test fun dot_only_and_path_ids_are_rejected() {
        // Regression: a pack with id ".." was stored at lists/.. and removing it deleted every pack.
        for (bad in listOf(".", "..", "...", "a/b", "a\\b", "", " x")) {
            assertFalse(bad, ListPack.isValidId(bad))
            val zip = PackBuilder(PackManifest(id = bad, name = "Bad")).build(null)
            expectFailure(zip, "invalid id")
        }
        assertTrue(ListPack.isValidId("gov.ftc"))
        assertTrue(ListPack.isValidId(".hidden"))
        // Storage folders are derived from the id and never equal it.
        val dir = ListPack.storageName("..")
        assertTrue(dir.matches(Regex("pack_[0-9a-f]{64}")))
        assertFalse(ListPack.storageName("a") == ListPack.storageName("b"))
    }

    @Test fun replacement_compares_the_full_publisher_key() {
        val sk = Ed25519.newSecret()
        val key = Base64.getEncoder().encodeToString(Ed25519.publicKey(sk))
        val same = ListPack.parse(PackBuilder(PackManifest(id = "p", name = "P", version = 2)).build(sk)).manifest
        val other = ListPack.parse(PackBuilder(PackManifest(id = "p", name = "P", version = 2)).build(Ed25519.newSecret())).manifest
        assertTrue(ListPack.sameKey(key, same))
        assertFalse(ListPack.sameKey(key, other))
        assertFalse(ListPack.sameKey(null, same))
        assertFalse(ListPack.sameKey(key, same.copy(publicKey = null)))
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
