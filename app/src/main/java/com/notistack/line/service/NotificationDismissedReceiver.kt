package com.notistack.line.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.notistack.line.data.local.NotiStackDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 處理使用者手動在通知列滑掉（移除）堆疊通知卡片的廣播
 * 當使用者劃除卡片時，立即將該聊天室快取之訊息清空，保證下次新訊息進來時不會帶出已劃除的舊訊息
 */
class NotificationDismissedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationDismissed"
        const val ACTION_STACK_DISMISSED = "com.notistack.line.ACTION_STACK_DISMISSED"
        const val EXTRA_CHAT_KEY = "extra_chat_key"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STACK_DISMISSED) return
        val chatKey = intent.getStringExtra(EXTRA_CHAT_KEY) ?: return

        Log.i(TAG, "Notification manually dismissed by user for chat: . Clearing message cache.")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = NotiStackDatabase.getInstance(context)
                db.clearChatMessages(chatKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear chat messages on notification dismiss", e)
            }
        }
    }
}
