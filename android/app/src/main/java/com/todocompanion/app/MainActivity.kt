package com.todocompanion.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.todocompanion.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    // Read by AppRoot; carries a one-shot launch action (e.g. from the home-screen widget).
    private val launchAction = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchAction.value = intent?.getStringExtra(EXTRA_ACTION)
        enableEdgeToEdge()
        setContent { AppRoot(launchAction = launchAction) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchAction.value = intent.getStringExtra(EXTRA_ACTION)
    }

    companion object {
        const val EXTRA_ACTION = "com.todocompanion.app.action"
        const val ACTION_QUICK_ADD = "quick_add"
    }
}
