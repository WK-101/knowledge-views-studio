package com.cairn.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cairn.reader.ui.CairnRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // The article a notification asked us to open; consumed once by CairnRoot.
    private val pendingItem = mutableStateOf<String?>(null)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingItem.value = intent?.getStringExtra(EXTRA_OPEN_ITEM)
        maybeRequestNotifications()
        setContent {
            val open by pendingItem
            CairnRoot(openItemId = open, onOpenConsumed = { pendingItem.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_ITEM)?.let { pendingItem.value = it }
    }

    // Volume-key page turns in the reader (opt-in). When no reader handler is registered these
    // fall through to the system so volume behaves normally everywhere else.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (com.cairn.reader.ui.reader.ReaderPaging.onVolumeKey(down = true)) return true
            KeyEvent.KEYCODE_VOLUME_UP -> if (com.cairn.reader.ui.reader.ReaderPaging.onVolumeKey(down = false)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Swallow the matching key-up so the system doesn't play the volume-change sound / show the slider.
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            com.cairn.reader.ui.reader.ReaderPaging.handler != null
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_ITEM = "open_item_id"
    }
}
