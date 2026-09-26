package com.wkhan.hexis.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wkhan.hexis.App
import com.wkhan.hexis.data.entity.NoteEntity
import com.wkhan.hexis.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * "Jot a note without opening the whole app" popup. Fires from the Quick-bar widget's Note button.
 * Floats over the launcher in its own translucent task (see the manifest: excludeFromRecents +
 * taskAffinity="" + the QuickCapture theme), autofocuses the body and shows the keyboard immediately,
 * and writes a new note straight into the active workspace's inbox — no full-screen app launch.
 * Fully offline; no network, no account, no new permission.
 */
class QuickNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as App
        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.wkhan.hexis.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                QuickNotePanel(
                    onSave = { title, body ->
                        if (title.isNotBlank() || body.isNotBlank()) {
                            app.appScope.launch {
                                val ws = runCatching { app.repository.settingsSnapshot().activeWorkspaceId }
                                    .getOrDefault(com.wkhan.hexis.data.entity.WorkspaceEntity.DEFAULT_ID)
                                app.repository.upsertNote(NoteEntity(id = "", kind = "note", workspaceId = ws, title = title.trim(), body = body.trim()))
                            }
                        }
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}

@Composable
private fun QuickNotePanel(onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val bodyFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { bodyFocus.requestFocus(); keyboard?.show() }

    // A dimmed scrim behind a bottom sheet — a proper modal popup, not a bare card floating on the
    // launcher. Tap-away on the scrim dismisses; the sheet consumes its own taps.
    Box(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 16.dp,
            // Lift above the keyboard and the nav bar so the whole panel is visible while typing.
            modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        ) {
            // Borderless title + body, matching the in-app Notes editor (no boxed fields).
            val clear = TextFieldDefaults.colors(
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            )
            Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                // Grabber handle — the sheet affordance.
                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.width(36.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)),
                    )
                }
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Create, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                    Text("New note", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextField(
                    value = title, onValueChange = { title = it },
                    placeholder = { Text("Title", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true, colors = clear,
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = body, onValueChange = { body = it },
                    placeholder = { Text("Start writing…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = clear, textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).focusRequester(bodyFocus),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onSave(title, body) }) { Text("Save", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
