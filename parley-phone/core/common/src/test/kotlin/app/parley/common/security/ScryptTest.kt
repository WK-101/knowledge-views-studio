package app.parley.common.security

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Test vectors of RFC 7914 (section 11 for PBKDF2-HMAC-SHA256, section 12 for scrypt). */
class ScryptTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun pbkdf2_hmac_sha256_vectors() {
        assertArrayEquals(
            hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"),
            Scrypt.pbkdf2Sha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64),
        )
        assertArrayEquals(
            hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"),
            Scrypt.pbkdf2Sha256("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80000, 64),
        )
    }

    @Test fun scrypt_empty_password() {
        assertArrayEquals(
            hex(
                "77 d6 57 62 38 65 7b 20 3b 19 ca 42 c1 8a 04 97 f1 6b 48 44 e3 07 4a e8 df df fa 3f ed e2 14 42 " +
                    "fc d0 06 9d ed 09 48 f8 32 6a 75 3a 0f c8 1f 17 e8 d3 e0 fb 2e 0d 36 28 cf 35 e2 0c 38 d1 89 06",
            ),
            Scrypt.derive(ByteArray(0), ByteArray(0), 16, 1, 1, 64),
        )
    }

    @Test fun scrypt_password_nacl() {
        assertArrayEquals(
            hex(
                "fd ba be 1c 9d 34 72 00 78 56 e7 19 0d 01 e9 fe 7c 6a d7 cb c8 23 78 30 e7 73 76 63 4b 37 31 62 " +
                    "2e af 30 d9 2e 22 a3 88 6f f1 09 27 9d 98 30 da c7 27 af b9 4a 83 ee 6d 83 60 cb df a2 cc 06 40",
            ),
            Scrypt.derive("password".encodeToByteArray(), "NaCl".encodeToByteArray(), 1024, 8, 16, 64),
        )
    }

    @Test fun scrypt_pleaseletmein() {
        assertArrayEquals(
            hex(
                "70 23 bd cb 3a fd 73 48 46 1c 06 cd 81 fd 38 eb fd a8 fb ba 90 4f 8e 3e a9 b5 43 f6 54 5d a1 f2 " +
                    "d5 43 29 55 61 3f 0f cf 62 d4 97 05 24 2a 9a f9 e6 1e 85 dc 0d 65 1e 40 df cf 01 7b 45 57 58 87",
            ),
            Scrypt.derive("pleaseletmein".encodeToByteArray(), "SodiumChloride".encodeToByteArray(), 16384, 8, 1, 64),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_n_that_is_not_a_power_of_two() {
        Scrypt.derive(ByteArray(1), ByteArray(1), 1000, 8, 1, 32)
    }
}
