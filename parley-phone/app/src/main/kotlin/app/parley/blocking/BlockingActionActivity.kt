package app.parley.blocking

import android.app.Activity
import android.os.Bundle
import app.parley.container
import kotlinx.coroutines.launch

/**
 * Invisible, not exported: runs a screening notification action on Android 10 and 11, where notification
 * actions can't require unlocking. Starting an activity from the lock screen asks to unlock first, so "Expecting a
 * call" and "Not spam" can't be triggered by someone holding a locked phone.
 */
class BlockingActionActivity : Activity() {
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        app.parley.ui.AppLocale.override(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val app = applicationContext
        app.container.scope.launch { runCatching { BlockingActionReceiver.perform(app, i) } }
        finish()
    }
}
