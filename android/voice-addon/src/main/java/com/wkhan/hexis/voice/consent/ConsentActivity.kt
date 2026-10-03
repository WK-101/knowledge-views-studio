package com.wkhan.hexis.voice.consent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

import com.wkhan.hexis.bridge.BridgeConsent
import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.bridge.security.BridgeTrust
import com.wkhan.hexis.bridge.security.SignatureVerifier
import com.wkhan.hexis.voice.R
import com.wkhan.hexis.voice.VoiceAddon

/**
 * Consent surface (no launcher). The core launches it for a result; it (1) verifies the caller is a
 * trusted Hexis build by signing keyset — so a random app can't even pop the mic dialog — (2) shows an
 * explicit prompt naming the caller and the scope being granted, then (3) requests RECORD_AUDIO and, on
 * grant, mints a scoped `voice.stt.listen` token bound to that core and returns it.
 *
 * Defense in depth: the bridge service re-verifies the keyset and the token on every call, so this
 * screen refusing an untrusted caller is an early, friendlier gate, not the only one.
 */
class ConsentActivity : Activity() {

    private var corePackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val core = callingPackage ?: intent.getStringExtra(BridgeConsent.EXTRA_CORE_PACKAGE)
        if (core == null) {
            deny()
            return
        }
        corePackage = core

        // Gate 1 — the caller must be a trusted Hexis build (relaxed only in a debuggable addon build).
        val trusted = SignatureVerifier.isTrusted(this, core, BridgeTrust.HEXIS_KEYSET)
        if (BridgeTrust.requireSignatureTrust(this) && !trusted) {
            deny()
            return
        }

        showConsentPrompt(core)
    }

    private fun showConsentPrompt(core: String) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(core, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: core

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(getString(R.string.consent_title))
            .setMessage(getString(R.string.consent_message, label))
            .setCancelable(false)
            .setPositiveButton(R.string.consent_allow) { _, _ -> requestMicOrGrant() }
            .setNegativeButton(R.string.consent_deny) { _, _ -> deny() }
            .show()
    }

    private fun requestMicOrGrant() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finishWithGrant()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            finishWithGrant()
        } else {
            deny()
        }
    }

    private fun finishWithGrant() {
        val core = corePackage ?: return deny()
        val token = VoiceAddon.tokenAuthority(this).mint(core, setOf(BridgeScopes.VOICE_STT_LISTEN))
        setResult(RESULT_OK, Intent().putExtra(BridgeConsent.EXTRA_TOKEN, token.value))
        finish()
    }

    private fun deny() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
    }
}
