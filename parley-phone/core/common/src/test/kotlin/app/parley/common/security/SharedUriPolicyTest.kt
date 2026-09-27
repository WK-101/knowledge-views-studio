package app.parley.common.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedUriPolicyTest {
    private val own = { a: String -> a.startsWith("app.parley.phone") }

    private fun ok(scheme: String?, authority: String?, encoded: String? = authority) = SharedUriPolicy.acceptable(scheme, authority, encoded, own)

    @Test fun other_apps_content_uris_are_taken() {
        assertTrue(ok("content", "com.android.providers.downloads.documents"))
        assertTrue(ok("content", "com.google.android.apps.docs.storage"))
    }

    @Test fun parleys_own_providers_are_refused() {
        assertFalse(ok("content", "app.parley.phone.files"))
        assertFalse(ok("content", "app.parley.phone.vaultphotos"))
        assertFalse(ok("content", "App.Parley.Phone.files"))
        assertFalse(ok("content", "com.other;app.parley.phone.files"))
    }

    @Test fun user_qualified_authorities_are_refused() {
        // The system strips "0@" when it resolves the provider: this would open Parley's own private provider.
        assertFalse(ok("content", "0@app.parley.phone.vaultphotos"))
        assertFalse(ok("content", "0@app.parley.phone.files"))
        assertFalse(ok("content", "10@com.other.provider"))
        assertFalse(ok("content", "0@app.parley.phone.files", "0%40app.parley.phone.files"))
        assertFalse(ok("content", "com.other.provider", "com.other%2Eprovider"))
        assertFalse(ok("content", "app.parley.phone.files:80"))
    }

    @Test fun other_schemes_and_empty_authorities_are_refused() {
        assertFalse(ok("file", ""))
        assertFalse(ok("file", null))
        assertFalse(ok("CONTENT", "com.other.provider"))
        assertFalse(ok("content", null))
        assertFalse(ok("content", ""))
        assertFalse(ok(null, "com.other.provider"))
    }
}
