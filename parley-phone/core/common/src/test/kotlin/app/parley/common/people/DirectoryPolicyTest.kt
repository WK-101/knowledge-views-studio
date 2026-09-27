package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectoryPolicyTest {
    @Test fun directory_trusts_the_caller_parameter_only_from_the_contacts_provider() {
        val cp2 = "com.android.providers.contacts"
        assertEquals("com.car.dialer", DirectoryPolicy.effectiveCaller(cp2, cp2, "com.car.dialer"))
        assertNull(DirectoryPolicy.effectiveCaller(cp2, cp2, null))
        // Any other app naming someone else is still itself.
        assertEquals("com.evil", DirectoryPolicy.effectiveCaller("com.evil", cp2, "com.car.dialer"))
        assertNull(DirectoryPolicy.effectiveCaller(null, cp2, "x"))
        assertEquals(DirectoryPolicy.Request.DIRECTORIES, DirectoryPolicy.request(listOf("directories")))
        assertEquals(DirectoryPolicy.Request.PHONE_LOOKUP, DirectoryPolicy.request(listOf("phone_lookup", "+447700900123")))
        assertEquals(DirectoryPolicy.Request.OTHER, DirectoryPolicy.request(listOf("contacts", "filter", "a")))
        assertEquals(DirectoryPolicy.Request.OTHER, DirectoryPolicy.request(listOf("phone_lookup")))
    }
}
