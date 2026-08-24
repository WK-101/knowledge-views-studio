package com.todocompanion.app

import android.app.Application
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.reminders.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application-scoped singletons. Doubles as a tiny service locator (no DI framework yet). */
class App : Application() {

    val database by lazy { AppDatabase.get(this) }
    val repository by lazy { AppRepository(database) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        appScope.launch { seedIfEmpty() }
    }

    private suspend fun seedIfEmpty() {
        val existing = database.taskDao().getAll()
        if (existing.isNotEmpty()) return
        val welcome = repository.createTask("Welcome to ToDo Companion", importance = 4, urgency = 3)
        repository.createTask("Tap + to capture — try: \"pay rent tomorrow 5pm !!\"", parentId = welcome)
        repository.createTask("Everything is offline, private, and free", parentId = welcome)
        val work = repository.createTask("Work", importance = 4)
        val report = repository.createTask("Quarterly report", parentId = work, importance = 5, urgency = 4)
        repository.createTask("Collect figures", parentId = report)
        repository.createTask("Draft summary", parentId = report)
        val home = repository.createTask("Home", importance = 3)
        repository.createTask("Book dentist", parentId = home, importance = 3, urgency = 4)
    }
}
