package app.parley.data.calls

import android.content.Context
import androidx.room.Room
import app.parley.data.history.CallFactsDao
import app.parley.data.history.HistoryDatabase

/** The history database's `call_facts` table in memory, the real SQL (a "new process" in a test reuses it). */
internal fun memoryFactTable(context: Context): CallFactsDao =
    Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries().build().callFacts()
