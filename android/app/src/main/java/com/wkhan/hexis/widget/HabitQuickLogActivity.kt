package com.wkhan.hexis.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wkhan.hexis.App
import com.wkhan.hexis.MainActivity
import com.wkhan.hexis.data.entity.HabitEntity
import com.wkhan.hexis.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * The "log a value without opening the whole app" popup that a habit widget fires into when you tap a
 * numeric or timed habit. It floats over the launcher in its own translucent task (see the manifest:
 * excludeFromRecents + taskAffinity="" + the QuickCapture theme), shows a single calm value stepper, and
 * writes the check-in straight to Room — the habit then vanishes from the widget and its meter climbs,
 * with no full-screen app launch. A timed habit also gets a "Start focus timer" shortcut for the live
 * coach. Fully offline; no network, no account, no new permission.
 */
class HabitQuickLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as App
        val habitId = intent?.getStringExtra(EXTRA_HABIT_ID)
            ?: intent?.data?.getQueryParameter("id")
            ?: run { finish(); return }
        val zone = ZoneId.systemDefault()
        // The day to log — today for the "vanishing" widgets, or a specific cell for the week-row grid.
        val today = intent?.getLongExtra(EXTRA_DAY, Long.MIN_VALUE)
            ?.takeIf { it != Long.MIN_VALUE } ?: LocalDate.now(zone).toEpochDay()

        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.wkhan.hexis.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            // Load the habit + today's count once, off the main thread.
            var habit by remember { mutableStateOf<HabitEntity?>(null) }
            var current by remember { mutableIntStateOf(0) }
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(habitId) {
                val h = withContext(Dispatchers.IO) { app.repository.getHabitsOnce().firstOrNull { it.id == habitId } }
                val c = withContext(Dispatchers.IO) {
                    app.repository.getHabitCheckinsOnce().firstOrNull { it.habitId == habitId && it.epochDay == today }?.count ?: 0
                }
                habit = h; current = c; loaded = true
                if (h == null) finish()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                val h = habit
                if (loaded && h != null) {
                    HabitQuickLogPanel(
                        habit = h,
                        current = current,
                        onDismiss = { finish() },
                        onSave = { value ->
                            // Write on the process-wide scope so it survives this transient activity finishing.
                            app.appScope.launch {
                                app.repository.setCheckinValue(h.id, today, value)
                                Widgets.refreshHabitWidgets(this@HabitQuickLogActivity)
                            }
                            finish()
                        },
                        onStartTimer = {
                            startActivity(Intent(this@HabitQuickLogActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(MainActivity.EXTRA_ACTION, "focus_habit:${h.id}")
                            })
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_DAY = "epochDay"

        /** Intent to open the popup for one habit, floating over the launcher (used by widget receivers).
         *  [epochDay] targets a specific day (week-row grid); omit for today (the "vanishing" widgets). */
        fun intent(context: Context, habitId: String, epochDay: Long = Long.MIN_VALUE): Intent =
            Intent(context, HabitQuickLogActivity::class.java).apply {
                putExtra(EXTRA_HABIT_ID, habitId)
                if (epochDay != Long.MIN_VALUE) putExtra(EXTRA_DAY, epochDay)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                // A unique data URI keeps distinct habit/day taps from collapsing onto one another.
                data = Uri.parse("hexis://habitlog/$habitId/$epochDay")
            }
    }
}

@Composable
private fun HabitQuickLogPanel(
    habit: HabitEntity,
    current: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onStartTimer: () -> Unit,
) {
    val timed = habit.unit?.startsWith("min") == true
    val step = if (timed) 5 else (habit.clickIncrement.takeIf { it > 0 } ?: 1)
    val target = habit.targetPerDay.coerceAtLeast(1)
    val unit = habit.unit ?: if (timed) "min" else ""
    var value by remember { mutableIntStateOf(current) }

    // Full-window scrim; tap-away dismisses. The card floats centred.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(28.dp)
                .width(320.dp)
                // Swallow taps on the card so they don't dismiss.
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Habit identity.
                Text(
                    (habit.emoji?.plus("  ") ?: "") + habit.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    if (timed) "Log today's minutes · goal $target" else "Set today's value · goal $target" + (if (unit.isNotBlank()) " $unit" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(20.dp))

                // Big −  value  + stepper.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StepButton(Icons.Filled.Remove, "Decrease") { value = (value - step).coerceAtLeast(0) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(128.dp)) {
                        // Type any value directly (e.g. 8000 steps) — the ± buttons stay for small nudges.
                        OutlinedTextField(
                            value = if (value == 0) "" else value.toString(),
                            onValueChange = { s -> value = s.filter { it.isDigit() }.take(7).toIntOrNull() ?: 0 },
                            placeholder = { Text("0", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (unit.isNotBlank()) Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StepButton(Icons.Filled.Add, "Increase") { value += step }
                }
                Spacer(Modifier.size(18.dp))

                // Quick-set chips: the goal, half, and clear.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip("Goal $target") { value = target }
                    if (target > 1) QuickChip("½ · ${target / 2}") { value = (target / 2).coerceAtLeast(1) }
                    QuickChip("Clear") { value = 0 }
                }

                if (timed) {
                    Spacer(Modifier.size(6.dp))
                    TextButton(onClick = onStartTimer) { Text("▶  Start focus timer instead") }
                }

                Spacer(Modifier.size(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { onSave(value) }) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, cd, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
