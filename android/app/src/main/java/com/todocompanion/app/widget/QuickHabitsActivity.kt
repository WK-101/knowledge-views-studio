package com.todocompanion.app.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.todocompanion.app.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Check a habit without opening the whole app" popup — the Quick-bar widget's Habit button. Lists
 * today's still-due habits (the exact set the Habit Zero widget shows, via [HabitZeroData]). Tapping a
 * simple build habit checks it in place; a numeric/timed habit hands off to [HabitQuickLogActivity]'s
 * value stepper. Fully offline; writes the check-in straight to Room.
 */
class QuickHabitsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as App
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.todocompanion.app.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            var rows by remember { mutableStateOf<List<HabitZeroData.Rem>?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                rows = withContext(Dispatchers.IO) { runCatching { HabitZeroData.compute(app, today).remaining }.getOrDefault(emptyList()) }
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                QuickHabitsPanel(
                    rows = rows,
                    onTap = { rem ->
                        if (rem.kind == "build") {
                            app.appScope.launch {
                                val h = app.repository.getHabitsOnce().firstOrNull { it.id == rem.id } ?: return@launch
                                val cur = app.repository.getHabitCheckinsOnce().firstOrNull { it.habitId == rem.id && it.epochDay == today }?.count ?: 0
                                app.repository.cycleCheckin(rem.id, today, h.targetPerDay, cur)
                                Widgets.refreshHabitWidgets(this@QuickHabitsActivity)
                            }
                            rows = rows?.filterNot { it.id == rem.id }
                            if (rows.isNullOrEmpty()) finish()
                        } else {
                            startActivity(HabitQuickLogActivity.intent(this, rem.id))
                            finish()
                        }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}

@Composable
private fun QuickHabitsPanel(rows: List<HabitZeroData.Rem>?, onTap: (HabitZeroData.Rem) -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Check a habit", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                when {
                    rows == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                    rows.isEmpty() -> Text("All done for today 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 8.dp)) {
                        items(rows, key = { it.id }) { r ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onTap(r) }.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(r.emoji.ifBlank { "•" }, fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 1)
                                    if (r.meta.isNotBlank() && r.meta != "○")
                                        Text(r.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                                }
                                Icon(Icons.Filled.CheckCircle, "Check in", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
