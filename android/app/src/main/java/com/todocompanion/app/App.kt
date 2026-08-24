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
        appScope.launch { repository.ensureSeed() }
    }
}
