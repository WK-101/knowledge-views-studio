package app.parley.common.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class BackupSignatureTest {
    private companion object {
        val CHEAP = KdfParams.Scrypt(10, 8, 1)
        val PASS = "violet tugboat mango oxide".toCharArray()
        val RECOVERY: RecoveryKey = RecoveryKey.generate()
        val BUNDLE: KeyBundle by lazy { BackupCrypto.createKeyBundle(PASS, RECOVERY, CHEAP) }
        val OTHER_BUNDLE: KeyBundle by lazy { BackupCrypto.createKeyBundle("someone else entirely".toCharArray(), RecoveryKey.generate(), CHEAP) }
    }

    /** A software stand-in for the phone's Keystore signing key. */
    private class Phone(bundle: KeyBundle?, pass: CharArray?) : ArchiveSigner {
        val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicKey: ByteArray = pair.public.encoded
        override val endorsement: ByteArray? =
            if (bundle != null && pass != null) ArchiveSignatures.endorse(BackupCrypto.unlockPrivateKey(bundle, pass), publicKey) else null
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
    }

    private fun archive(bundle: KeyBundle, signer: ArchiveSigner?, contacts: Int = 2): ByteArray {
        val bo = ByteArrayOutputStream()
        val enc = BackupCrypto.encrypt(bo, listOf(Recipient.PublicKey(bundle)), CHEAP)
        val w = BackupArchiveWriter(enc, ArchiveMeta(1_700_000_000_000, "test", signing = signer?.let { ArchiveSigning(enc.header.bytes, it) }))
        w.writeContacts((1..contacts).map { Fixtures.contact("k$it", "Person $it", Fixtures.phone("+4412345678$it")) })
        w.finish()
        enc.finish()
        return bo.toByteArray()
    }

    private fun origin(file: ByteArray, unlock: Unlock, thisPhone: ByteArray?): ArchiveOrigin {
        val header = BackupCrypto.readHeader(ByteArrayInputStream(file))
        val opened = BackupCrypto.open(header, unlock)
        val reader = BackupArchiveReader.open({ BackupCrypto.decrypt(ByteArrayInputStream(file), opened.dataKey) })
        return ArchiveSignatures.verify(header.bytes, reader.manifest, opened.bundle, thisPhone)
    }

    @Test fun made_on_this_phone() {
        val phone = Phone(BUNDLE, PASS)
        assertEquals(ArchiveOrigin.THIS_PHONE, origin(archive(BUNDLE, phone), Unlock.Passphrase(PASS), phone.publicKey))
    }

    @Test fun made_on_another_phone_that_the_backup_key_vouches_for() {
        val oldPhone = Phone(BUNDLE, PASS)
        val newPhone = Phone(null, null)
        val file = archive(BUNDLE, oldPhone)
        assertEquals(ArchiveOrigin.OTHER_PHONE, origin(file, Unlock.Passphrase(PASS), newPhone.publicKey))
        assertEquals(ArchiveOrigin.OTHER_PHONE, origin(file, Unlock.Recovery(RECOVERY), newPhone.publicKey))
    }

    @Test fun older_backups_are_unsigned() {
        assertEquals(ArchiveOrigin.UNSIGNED, origin(archive(BUNDLE, null), Unlock.Passphrase(PASS), Phone(null, null).publicKey))
    }

    @Test fun a_forged_backup_with_the_genuine_bundle_is_not_trusted() {
        // The attacker saw one backup: they have the bundle (public) but not the passphrase. Their archive opens with
        // the user's passphrase, but their own key can't carry an endorsement by the user's bundle.
        val attacker = object : ArchiveSigner by Phone(null, null) {
            override val endorsement: ByteArray =
                ArchiveSignatures.endorse(BackupCrypto.unlockPrivateKey(OTHER_BUNDLE, "someone else entirely".toCharArray()), publicKey)
        }
        val forged = archive(BUNDLE, attacker)
        assertEquals(ArchiveOrigin.UNKNOWN_SIGNER, origin(forged, Unlock.Passphrase(PASS), Phone(null, null).publicKey))
        // Copying a genuine phone's key and endorsement doesn't help without its private key.
        val genuine = Phone(BUNDLE, PASS)
        val copycat = object : ArchiveSigner {
            override val publicKey = genuine.publicKey
            override val endorsement = genuine.endorsement
            override fun sign(data: ByteArray) = attacker.sign(data)
        }
        assertEquals(ArchiveOrigin.BAD_SIGNATURE, origin(archive(BUNDLE, copycat), Unlock.Passphrase(PASS), null))
    }

    @Test fun any_change_breaks_the_signature() {
        val phone = Phone(BUNDLE, PASS)
        val file = archive(BUNDLE, phone)
        val header = BackupCrypto.readHeader(ByteArrayInputStream(file))
        val key = BackupCrypto.open(header, Unlock.Passphrase(PASS))
        val m = BackupArchiveReader.open({ BackupCrypto.decrypt(ByteArrayInputStream(file), key.dataKey) }).manifest
        assertEquals(ArchiveOrigin.THIS_PHONE, ArchiveSignatures.verify(header.bytes, m, key.bundle, phone.publicKey))
        assertEquals(ArchiveOrigin.BAD_SIGNATURE, ArchiveSignatures.verify(header.bytes, m.copy(createdAt = m.createdAt + 1), key.bundle, phone.publicKey))
        val otherHeader = header.bytes.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(ArchiveOrigin.BAD_SIGNATURE, ArchiveSignatures.verify(otherHeader, m, key.bundle, phone.publicKey))
    }

    @Test fun scrypt_bundles_round_trip_and_change_passphrase_upgrades_pbkdf2() {
        val restored = KeyBundle.fromBytes(BUNDLE.toBytes())
        assertEquals(BUNDLE, restored)
        assertEquals(CHEAP, restored.kdf)
        val old = BackupCrypto.createKeyBundle(PASS, RECOVERY, KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS))
        assertEquals(1, old.toBytes()[8].toInt())
        val upgraded = BackupCrypto.changePassphrase(old, PASS, "new pass phrase words".toCharArray(), CHEAP)
        assertEquals(CHEAP, upgraded.kdf)
        assertArrayEquals(
            BackupCrypto.unlockPrivateKey(old, RECOVERY).encoded,
            BackupCrypto.unlockPrivateKey(upgraded, "new pass phrase words".toCharArray()).encoded,
        )
    }

    @Test fun scrypt_passphrase_archives_open_and_pbkdf2_ones_still_do() {
        val plain = "hello".encodeToByteArray()
        for (kdf in listOf(CHEAP, KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS))) {
            val ct = BackupCrypto.encryptBytes(plain, listOf(Recipient.Passphrase(PASS)), kdf)
            assertArrayEquals(plain, BackupCrypto.decryptBytes(ct, Unlock.Passphrase(PASS)))
            assertEquals(kdf, BackupCrypto.readHeader(ByteArrayInputStream(ct)).kdf)
        }
    }

    /** A header naming [alg]/[param] as its KDF, otherwise well formed. */
    private fun craftedHeader(alg: Int, param: Int): ByteArray {
        val good = BackupCrypto.encryptBytes(ByteArray(0), listOf(Recipient.Passphrase(PASS)), KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS))
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply { write(good, 0, 13); writeByte(alg); writeInt(param); write(good, 18, good.size - 18) }
        return bo.toByteArray()
    }

    @Test fun attacker_chosen_kdf_costs_are_refused_before_deriving() {
        val crafted = listOf(
            1 to 10_000_000, 2 to KdfParams.Scrypt(20, 8, 1).param, 2 to KdfParams.Scrypt(16, 16, 1).param, 2 to KdfParams.Scrypt(12, 8, 64).param, 9 to 1,
        )
        for ((alg, param) in crafted) {
            try {
                BackupCrypto.readHeader(ByteArrayInputStream(craftedHeader(alg, param)))
                fail("accepted $alg/$param")
            } catch (_: BackupIntegrityException) {
            }
        }
        // A QR payload accepts only the sender's fixed setting.
        val qr = KdfPolicy.exactly(KdfParams.Pbkdf2(200_000))
        try {
            BackupCrypto.readHeader(ByteArrayInputStream(craftedHeader(1, 2_000_000)), qr)
            fail("QR accepted another cost")
        } catch (_: BackupIntegrityException) {
        }
        assertNotNull(BackupCrypto.readHeader(ByteArrayInputStream(craftedHeader(1, 200_000)), qr))
    }

    @Test fun the_opening_bundle_is_reported() {
        val file = archive(BUNDLE, null)
        val header = BackupCrypto.readHeader(ByteArrayInputStream(file))
        assertEquals(BUNDLE.keyId, BackupCrypto.open(header, Unlock.Passphrase(PASS)).bundle?.keyId)
        val direct = BackupCrypto.encryptBytes(ByteArray(1), listOf(Recipient.Passphrase(PASS)), CHEAP)
        assertNull(BackupCrypto.open(BackupCrypto.readHeader(ByteArrayInputStream(direct)), Unlock.Passphrase(PASS)).bundle)
        assertTrue(BackupCrypto.DEFAULT_KDF is KdfParams.Scrypt)
    }
}
