package app.parley.shortcuts

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.pm.ShortcutManagerCompat
import app.parley.MainActivity
import app.parley.container
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
                container.scope.launch { container.placer.call(number) }
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

    companion object {
        const val ACTION = "app.parley.SHORTCUT"
        const val EXTRA_KIND = "kind"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_CONTACT = "contact"
    }
}
