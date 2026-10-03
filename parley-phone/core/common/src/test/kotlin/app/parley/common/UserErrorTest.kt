package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException
import javax.crypto.AEADBadTagException

class UserErrorTest {
    private class LockedException : Exception("vault locked")
    private class SerializationException : Exception("Unexpected JSON token at offset 12")

    @Test fun each_kind_is_recognised() {
        assertEquals(UserError.NO_SPACE, UserError.of(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(UserError.FILE_GONE, UserError.of(FileNotFoundException("/storage/x.vcf: open failed: ENOENT")))
        assertEquals(UserError.NO_ACCESS, UserError.of(SecurityException("Permission Denial: opening provider")))
        assertEquals(UserError.LOCKED, UserError.of(LockedException()))
        assertEquals(UserError.DAMAGED, UserError.of(EOFException()))
        assertEquals(UserError.DAMAGED, UserError.of(ZipException("invalid entry")))
        assertEquals(UserError.DAMAGED, UserError.of(AEADBadTagException("Tag mismatch")))
        assertEquals(UserError.DAMAGED, UserError.of(SerializationException()))
        assertEquals(UserError.DAMAGED, UserError.of(NumberFormatException("For input string: \"x\"")))
        assertEquals(UserError.EXPLAINED, UserError.of(ExplainedFailure("This file is too large")))
        assertEquals(UserError.UNKNOWN, UserError.of(IllegalStateException("Cursor window allocation of 2048 kb failed")))
        assertEquals(UserError.UNKNOWN, UserError.of(IOException("Broken pipe")))
    }

    @Test fun only_parse_and_format_failures_blame_the_file() {
        assertEquals(UserError.DAMAGED, UserError.of(app.parley.common.backup.BackupIntegrityException("Damaged snapshot index")))
        // A failed require() or a Keystore failure is no reason to call the file damaged.
        assertEquals(UserError.UNKNOWN, UserError.of(IllegalArgumentException("Failed requirement.")))
        assertEquals(UserError.UNKNOWN, UserError.of(java.security.KeyStoreException("Keystore operation failed")))
        assertEquals(UserError.UNKNOWN, UserError.of(java.security.InvalidKeyException("Key not usable")))
    }

    @Test fun causes_are_looked_through() {
        val wrapped = RuntimeException("export", IllegalStateException("io", IOException("No space left on device")))
        assertEquals(UserError.NO_SPACE, UserError.of(wrapped))
        assertEquals(UserError.EXPLAINED, UserError.of(RuntimeException(ExplainedFailure("Couldn't open the file"))))
    }

    @Test fun a_cycle_of_causes_ends() {
        val a = Exception("a")
        val b = Exception("b", a)
        a.initCause(b)
        assertEquals(UserError.UNKNOWN, UserError.of(a))
    }
}
