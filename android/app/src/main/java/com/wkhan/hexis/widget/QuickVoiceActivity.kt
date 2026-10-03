package com.wkhan.hexis.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wkhan.hexis.App
import com.wkhan.hexis.MainActivity
import com.wkhan.hexis.ui.AppViewModel
import com.wkhan.hexis.ui.AppViewModelFactory
import com.wkhan.hexis.ui.screens.VoiceCapturePanel
import com.wkhan.hexis.ui.theme.AppTheme

/**
 * "Speak a task or note without opening the whole app" popup — the Quick-bar widget's Voice button. A
 * lightweight translucent window that starts push-to-talk straight away and hosts the same intent-router
 * [VoiceCapturePanel] (transcript + add task / add note / start-or-stop timer / search / command). Add-task,
 * add-note and timer actions complete right here against the local DB; search / command open the full app
 * with the transcript carried in. Entirely dependent on the optional voice addon: with none installed (or
 * connected) the popup shows a one-line "install the addon" prompt instead — the core stays independent.
 */
class QuickVoiceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as App

        fun openApp(action: String?) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
            })
            finish()
        }

        fun done() { QuickBarWidget.refresh(this); finish() }

        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.wkhan.hexis.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                val vm: AppViewModel = viewModel(factory = AppViewModelFactory(app))
                VoicePopup(vm = vm, onOpenApp = ::openApp, onFinish = ::done)
            }
        }
    }
}

/** The whole popup body — scrim + bottom sheet driving push-to-talk through the shared intent router. */
@Composable
private fun VoicePopup(vm: AppViewModel, onOpenApp: (String?) -> Unit, onFinish: () -> Unit) {
    val available by vm.voiceAvailable.collectAsStateWithLifecycle()
    val ui by vm.voiceUi.collectAsStateWithLifecycle()

    // We only know whether the addon is present after the bridge state resolves; drive the UI off a
    // tri-state so the popup doesn't flash the "install" prompt before that first value settles.
    var started by remember { mutableStateOf(false) }
    var resolved by remember { mutableStateOf(false) }
    var sawActive by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    // Fresh VM for this throwaway window — kick the bridge discovery so voiceAvailable settles.
    LaunchedEffect(Unit) { vm.refreshBridge() }

    // Begin listening the moment the addon is confirmed available (push-to-talk, once); if it stays
    // unavailable past a short discovery grace, surface the install prompt instead.
    LaunchedEffect(available) {
        if (available) {
            resolved = true
            if (!started) { started = true; vm.startVoiceCapture() }
        } else {
            kotlinx.coroutines.delay(1200)
            resolved = true
        }
    }
    // Close when capture finishes (status back to IDLE after a committed action / cancel), but never on
    // the initial IDLE before we've seen the session go active.
    LaunchedEffect(ui.status) {
        if (ui.status != AppViewModel.VoiceStatus.IDLE) {
            sawActive = true
        } else if (sawActive && !leaving) {
            kotlinx.coroutines.delay(60) // a search / command emits VoiceNav just before IDLE — let it win
            if (!leaving) onFinish()
        }
    }
    // Search / command-palette routes can't be served in this tiny window — hand off to the full app.
    LaunchedEffect(Unit) {
        vm.voiceNav.collect { nav ->
            leaving = true
            when (nav) {
                is AppViewModel.VoiceNav.Search -> onOpenApp("voice_search:${nav.query}")
                is AppViewModel.VoiceNav.Palette -> onOpenApp("voice_command:${nav.text}")
            }
        }
    }

    Box(
        Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
            vm.cancelVoiceCapture(); onFinish()
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().imePadding()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        ) {
            when {
                available -> VoiceCapturePanel(vm)
                resolved -> VoiceUnavailable(onOpenApp = { onOpenApp(null) }, onClose = onFinish)
                else -> VoiceConnecting()
            }
        }
    }
}

/** Brief grace state while the bridge discovers a connected addon, so the install prompt never flashes. */
@Composable
private fun VoiceConnecting() {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Voice capture", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text("Connecting to the voice addon…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Shown when no voice addon is installed/connected — the core app has no voice features of its own. */
@Composable
private fun VoiceUnavailable(onOpenApp: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Voice capture", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Install the separate Hexis Voice addon and connect it in Settings → Addon bridges to dictate tasks and notes. Hexis itself never records audio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) { Text("Open Hexis") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
    }
}
