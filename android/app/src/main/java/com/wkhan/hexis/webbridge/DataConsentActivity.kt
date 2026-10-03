package com.wkhan.hexis.webbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.bridge.data.DataConsent
import com.wkhan.hexis.bridge.security.BridgeTrust
import com.wkhan.hexis.bridge.security.SignatureVerifier

/**
 * The CORE-hosted consent for the `data` capability — the mirror of the voice addon's consent, reversed:
 * here the core owns the data, so the core grants a consumer addon (the web bridge) access to it.
 *
 * A consumer launches this for a result with the scopes it wants. We (1) verify the caller is a trusted
 * Hexis build by signing keyset, (2) show the user exactly which data domains are being requested, and on
 * approval (3) mint a scoped token — intersected with the known-valid scopes, default-deny — and return it.
 * The core's provider re-verifies that token on every call, and the kill switch revokes it instantly.
 */
class DataConsentActivity : Activity() {

    @Suppress("ReturnCount") // sequential consent guards read clearest as early returns
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val consumer = callingPackage ?: intent.getStringExtra(DataConsent.EXTRA_CONSUMER_PACKAGE)
        if (consumer == null) return deny()

        // Gate 1 — the caller must be a trusted Hexis build (relaxed only in a debuggable core build).
        val trusted = SignatureVerifier.isTrusted(this, consumer, BridgeTrust.HEXIS_KEYSET)
        if (BridgeTrust.requireSignatureTrust(this) && !trusted) return deny()

        val requested = (intent.getStringExtra(DataConsent.EXTRA_SCOPES) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val granted = requested.intersect(BridgeScopes.DATA_ALL)
        if (granted.isEmpty()) return deny()

        showConsent(consumer, granted)
    }

    private fun showConsent(consumer: String, granted: Set<String>) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(consumer, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: consumer

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Allow web access to your data?")
            .setMessage(
                "“$label” wants to read/write these Hexis data areas so you can use them from a browser on " +
                    "your network:\n\n• ${domains(granted).joinToString("\n• ")}\n\n" +
                    "Your data stays on this device — it is served on demand and you can revoke this anytime " +
                    "in Settings → Addon bridges.",
            )
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> grant(consumer, granted) }
            .setNegativeButton("Deny") { _, _ -> deny() }
            .show()
    }

    private fun grant(consumer: String, granted: Set<String>) {
        val token = HexisDataAuthority.tokens(this).mint(consumer, granted)
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(DataConsent.EXTRA_TOKEN, token.value)
                .putExtra(DataConsent.EXTRA_GRANTED_SCOPES, granted.joinToString(",")),
        )
        finish()
    }

    private fun deny() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /** A friendly domain summary from a scope set (e.g. "Tasks (read/write)", "Notes (read)"). */
    private fun domains(scopes: Set<String>): List<String> {
        val byDomain = linkedMapOf(
            "Tasks" to Pair(BridgeScopes.DATA_TASKS_READ, BridgeScopes.DATA_TASKS_WRITE),
            "Notes" to Pair(BridgeScopes.DATA_NOTES_READ, BridgeScopes.DATA_NOTES_WRITE),
            "Calendar" to Pair(BridgeScopes.DATA_CALENDAR_READ, BridgeScopes.DATA_CALENDAR_WRITE),
            "Time" to Pair(BridgeScopes.DATA_TIME_READ, BridgeScopes.DATA_TIME_WRITE),
            "Habits" to Pair(BridgeScopes.DATA_HABITS_READ, BridgeScopes.DATA_HABITS_WRITE),
        )
        return byDomain.mapNotNull { (label, rw) ->
            val r = rw.first in scopes
            val w = rw.second in scopes
            when {
                r && w -> "$label (read/write)"
                r -> "$label (read)"
                w -> "$label (write)"
                else -> null
            }
        }
    }
}
