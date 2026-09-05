package com.cairn.reader.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Handles the Mark-read / Save quick actions on new-article notifications, on-device. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var itemRepository: ItemRepository

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF, 0)
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_MARK_READ -> itemRepository.setRead(itemId, true)
                    ACTION_SAVE -> itemRepository.setReadLater(itemId, true)
                }
            } finally {
                runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "com.cairn.reader.action.MARK_READ"
        const val ACTION_SAVE = "com.cairn.reader.action.SAVE"
        const val EXTRA_ITEM = "item_id"
        const val EXTRA_NOTIF = "notif_id"
    }
}
