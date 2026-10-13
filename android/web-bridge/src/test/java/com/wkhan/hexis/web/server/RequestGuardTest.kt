package com.wkhan.hexis.web.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down the Origin/Host guards — especially the port handling that caused a 403 on every call. */
class RequestGuardTest {

    // ---- origin (the regression) --------------------------------------------------------------------

    @Test fun sameOrigin_withPort_isAllowed() {
        // The exact on-device case: non-default port in both Host and Origin.
        assertTrue(RequestGuard.originAllowed("192.168.1.50:8787", "https://192.168.1.50:8787"))
        assertTrue(RequestGuard.originAllowed("localhost:8080", "http://localhost:8080"))
    }

    @Test fun noOrigin_isAllowed() {
        assertTrue(RequestGuard.originAllowed("192.168.1.50:8787", null)) // same-origin GET
    }

    @Test fun crossOrigin_isRejected() {
        assertFalse(RequestGuard.originAllowed("192.168.1.50:8787", "https://evil.example.com"))
        // Same host, different port is a different origin.
        assertFalse(RequestGuard.originAllowed("192.168.1.50:8787", "https://192.168.1.50:9999"))
    }

    @Test fun missingHostHeader_withOrigin_isRejected() {
        assertFalse(RequestGuard.originAllowed(null, "https://192.168.1.50:8787"))
    }

    // ---- host (DNS-rebinding guard) -----------------------------------------------------------------

    @Test fun host_allowsLanIpAndLocal() {
        assertTrue(RequestGuard.hostAllowed("192.168.1.50"))
        assertTrue(RequestGuard.hostAllowed("10.0.0.5"))
        assertTrue(RequestGuard.hostAllowed("localhost"))
        assertTrue(RequestGuard.hostAllowed("hexis.local"))
    }

    @Test fun host_rejectsPublicHostname() {
        assertFalse(RequestGuard.hostAllowed("evil.example.com"))
        assertFalse(RequestGuard.hostAllowed(null))
    }
}
