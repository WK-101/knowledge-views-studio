package com.wkhan.hexis.voice.consent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.voice.VoiceAddon

/**
 * Minimal consent surface (no launcher). The core launches it for a result; it requests RECORD_AUDIO
 * and, on grant, mints a scoped `voice.stt.listen` token bound to the calling core and returns it.
 *
 * TODO(Phase 1): a real consent UI naming the scope and the core, plus persisting the grant. The
 * skeleton relies on the system permission dialog as its only visible surface.
 */
class ConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun finishWithGrant() {
        val core = callingPackage ?: intent.getStringExtra(EXTRA_CORE_PACKAGE)
        if (core == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val token = VoiceAddon.tokenAuthority.mint(core, setOf(BridgeScopes.VOICE_STT_LISTEN))
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token.value))
        finish()
    }

    companion object {
        const val EXTRA_CORE_PACKAGE = "com.wkhan.hexis.voice.extra.CORE_PACKAGE"
        const val EXTRA_TOKEN = "com.wkhan.hexis.voice.extra.TOKEN"
        private const val REQUEST_RECORD_AUDIO = 1
    }
}
