package app.parley.shortcuts

import android.R
import android.app.Activity
import android.app.AlertDialog
import app.parley.calls.ProximityProbe
import app.parley.common.calls.CallSource
import app.parley.common.calls.PocketGuard
import app.parley.ui.DataL10n
import kotlinx.coroutines.Dispatchers
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.pm.ShortcutManagerCompat
import app.parley.MainActivity
import app.parley.container
import app.parley.CallGate
import app.parley.messaging.NumberActionActivity
import kotlinx.coroutines.launch

/** Invisible trampoline for home-screen shortcuts and the direct-dial widget. Not exported. */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = runCatching { Shortcuts.Kind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrNull()
        val number = intent.getStringExtra(EXTRA_NUMBER)
        val contactId = intent.getLongExtra(EXTRA_CONTACT, -1L)
        when (kind) {
            Shortcuts.Kind.CALL -> if (!number.isNullOrBlank()) {
                if (contactId > 0) ShortcutManagerCompat.reportShortcutUsed(this, "fav-$contactId")
                call(number)
                return
            }
            Shortcuts.Kind.MESSAGE -> if (!number.isNullOrBlank()) runCatching {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
            }
            Shortcuts.Kind.OPEN -> startActivity(
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CALLER)
                    .putExtra(MainActivity.EXTRA_CONTACT_ID, contactId).putExtra(MainActivity.EXTRA_NUMBER, number),
            )
            null -> Unit
        }
        finish()
    }

    /**
     * An emergency number takes the same path as calls made in Parley ([CallGate]): no pocket guard and no remembered
     * or label SIM, which may have no service. The platform check crosses into the phone process, so it runs off the
     * main thread; the activity stays (invisible) until it answers.
     */
    private fun call(number: String) {
        val c = container
        c.scope.launch(Dispatchers.Main) {
            val gate = CallGate(c)
            if (gate.isEmergency(number)) {
                c.scope.launch { gate.place(number, null, null, emptyList()) }
                return@launch finish()
            }
            if (isFinishing || isDestroyed) return@launch
            // "Confirm before calling" asks here too, with the same questions as in Parley (the call gate's sheet).
            if (c.settings.current().confirmBeforeCall) {
                startActivity(NumberActionActivity.callBackIntent(this@ShortcutActivity, number))
                return@launch finish()
            }
            // One tap on a widget or shortcut in a pocket shouldn't call anyone; ask while the sensor is covered.
            if (c.callExtras.config.value.pocketGuard) return@launch guardThenCall(number)
            c.scope.launch { c.placer.call(number) }
            finish()
        }
    }

    private fun guardThenCall(number: String) {
        val c = container
        c.scope.launch(Dispatchers.Main) {
            val covered = ProximityProbe.isCovered(this@ShortcutActivity)
            if (isFinishing || isDestroyed) return@launch
            if (!PocketGuard.shouldAsk(true, CallSource.SHORTCUT, covered)) {
                c.scope.launch { c.placer.call(number) }
                finish()
                return@launch
            }
            AlertDialog.Builder(this@ShortcutActivity, R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(app.parley.R.string.shortcut_call_confirm, DataL10n.ltr(number)))
                .setMessage(getString(app.parley.R.string.pocket_body))
                .setPositiveButton(getString(app.parley.R.string.shortcut_call)) { _, _ -> c.scope.launch { c.placer.call(number) } }
                .setNegativeButton(getString(app.parley.R.string.dc_cancel), null)
                .setOnDismissListener { finish() }
                .show()
        }
    }

    companion object {
        const val ACTION = "app.parley.SHORTCUT"
        const val EXTRA_KIND = "kind"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_CONTACT = "contact"
    }
}
