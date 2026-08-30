package com.todocompanion.app.tiles

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.widget.HabitsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * R36 · FW-1 — a Quick Settings tile for one-tap habit check-in. The tile counts habits still due today;
 * tapping checks off the next one right from the shade and decrements the count. When everything's done
 * (or nothing's due), it goes Active and a tap opens the Habits tab. Fully offline, no permissions.
 */
class HabitTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? App ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val due = dueHabits(app)
            val next = due.firstOrNull()
            if (next != null) {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val current = app.repository.getHabitCheckinsOnce().firstOrNull { it.habitId == next.id && it.epochDay == today }?.count ?: 0
                app.repository.cycleCheckin(next.id, today, next.targetPerDay, current)
                HabitsWidget.refresh(applicationContext)
                withContext(Dispatchers.Main) { refreshTile() }
            } else {
                withContext(Dispatchers.Main) { launchHabits() }
            }
        }
    }

    /** Build habits expected today whose goal isn't met yet, in reminder-then-name order. */
    private suspend fun dueHabits(app: App): List<com.todocompanion.app.data.entity.HabitEntity> {
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val checkins = app.repository.getHabitCheckinsOnce()
        return app.repository.getHabitsOnce()
            .filter { !it.archived && !it.paused && it.habitType != "break" && HabitStats.isExpectedDay(it, today) && today >= it.startEpochDay() }
            .filter { h ->
                val count = checkins.firstOrNull { it.habitId == h.id && it.epochDay == today }?.count ?: 0
                !HabitStats.meetsGoal(h, count)
            }
            .sortedWith(compareBy({ it.reminderTimes.split(",").mapNotNull { r -> r.trim().toIntOrNull() }.minOrNull() ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    }

    private fun launchHabits() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_habits")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val app = applicationContext as? App ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val due = dueHabits(app)
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                if (due.isEmpty()) {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Habits ✓"
                } else {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "${due.size} habit${if (due.size == 1) "" else "s"} to do"
                }
                runCatching { tile.icon = Icon.createWithResource(this@HabitTileService, android.R.drawable.checkbox_on_background) }
                tile.updateTile()
            }
        }
    }
}
