package com.todocompanion.app.widget

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Start tracking without opening the whole app" popup — the Quick-bar widget's Time button. Lists your
 * time activities; tapping one starts tracking it in place (the same path the Time widget/QS tile use),
 * then the popup closes. A "Focus timer" shortcut opens the Focus screen, where the focus / pomodoro /
 * stopwatch modes with their live ring live. Fully offline; writes straight to Room.
 */
class QuickTimeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as App
        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.todocompanion.app.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            var acts by remember { mutableStateOf<List<TimeActivityEntity>?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                acts = withContext(Dispatchers.IO) {
                    runCatching { app.repository.wsTimeActivitiesOnce().filter { !it.archived } }.getOrDefault(emptyList())
                }
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                QuickTimePanel(
                    acts = acts,
                    onStart = { a ->
                        app.appScope.launch {
                            app.repository.startTimeTracking(a.id)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@QuickTimeActivity, "Tracking ${a.name}", Toast.LENGTH_SHORT).show()
                            }
                            Widgets.refreshAll(this@QuickTimeActivity)
                        }
                        finish()
                    },
                    onTimer = { min, label ->
                        // Headless: the timer runs from a notification + exact alarm; no app window.
                        FocusTimer.start(this, min, label)
                        Toast.makeText(this, if (min > 0) "$label · ${min}m started" else "$label started", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}

@Composable
private fun QuickTimePanel(
    acts: List<TimeActivityEntity>?, onStart: (TimeActivityEntity) -> Unit, onTimer: (Int, String) -> Unit, onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Track time", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)

                // Headless timer modes — start straight from the popup, run in a notification, no app.
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimerChip("Pomodoro", "25 min", Modifier.weight(1f)) { onTimer(25, "Pomodoro") }
                    TimerChip("Focus", "50 min", Modifier.weight(1f)) { onTimer(50, "Focus") }
                    TimerChip("Stopwatch", "count up", Modifier.weight(1f)) { onTimer(0, "Stopwatch") }
                }

                Text("Start tracking an activity", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                when {
                    acts == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    acts.isEmpty() -> Text("No activities yet — add one in the Time view.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(acts, key = { it.id }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onStart(a) }.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(a.emoji?.ifBlank { "•" } ?: "•", fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
                                Text(a.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.PlayArrow, "Start", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerChip(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(sub, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
    }
}
