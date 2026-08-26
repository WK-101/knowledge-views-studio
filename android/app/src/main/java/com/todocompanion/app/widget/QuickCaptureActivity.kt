package com.todocompanion.app.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.todocompanion.app.App
import com.todocompanion.app.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A translucent, dialog-styled capture screen — the "add a task without opening the whole app" popup the
 * Quick-add widget (and other surfaces) fire into. It floats over the launcher in its own task
 * (excludeFromRecents + taskAffinity="" in the manifest), pops the keyboard immediately, writes straight
 * to the Room Inbox via [com.todocompanion.app.data.AppRepository.quickCaptureTask], refreshes the task
 * widgets, and finishes — the app itself never comes forward. Fully offline; no network, no account.
 *
 * The text field understands the same quick-add grammar as the in-app sheet: a due date/time in plain
 * words ("tomorrow 3pm"), p1–p4 priority, ~list, #estimate and * to star.
 */
class QuickCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        val app = applicationContext as App
        // Prefill from a shared/voice text or a todocompanion://add?text= deep link, if present.
        val prefill = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.data?.getQueryParameter("text")
            ?: ""

        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.todocompanion.app.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                var text by remember { mutableStateOf(prefill) }
                var saving by remember { mutableStateOf(false) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

                fun save() {
                    val t = text.trim()
                    if (t.isBlank() || saving) { if (t.isBlank()) finish(); return }
                    saving = true
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { app.repository.quickCaptureTask(t) }
                        // Keep every task-facing widget current after a background add.
                        AgendaWidget.refresh(this@QuickCaptureActivity)
                        TodayWidget.refresh(this@QuickCaptureActivity)
                        DoNextWidget.refresh(this@QuickCaptureActivity)
                        Next7Widget.refresh(this@QuickCaptureActivity)
                        android.widget.Toast.makeText(this@QuickCaptureActivity, "Added to Inbox", android.widget.Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }

                Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Quick add", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.size(12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.weight(1f).focusRequester(focus),
                                placeholder = { Text("Task… e.g. Call bank tomorrow 3pm p1") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { save() }),
                            )
                            Spacer(Modifier.size(8.dp))
                            IconButton(onClick = { save() }, enabled = !saving) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                        Text("Understands dates, p1–p4, ~list, #25 estimate and * to star.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
