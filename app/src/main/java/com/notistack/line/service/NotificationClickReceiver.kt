package com.notistack.line.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.notistack.line.data.local.NotiStackDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 處理使用者點擊堆疊通知卡片的廣播
 * 1. 轉發觸發 LINE 原生 PendingIntent，直達該聊天室
 * 2. 同步清空該聊天室快取訊息，並取消自訂堆疊通知 (達成點擊即已讀自動消除)
 */
class NotificationClickReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationClick"
        const val ACTION_STACK_CLICKED = "com.notistack.line.ACTION_STACK_CLICKED"
        const val EXTRA_CHAT_KEY = "extra_chat_key"
        const val EXTRA_CONTENT_INTENT = "extra_content_intent"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STACK_CLICKED) return
        val chatKey = intent.getStringExtra(EXTRA_CHAT_KEY) ?: return

        val originalIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CONTENT_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CONTENT_INTENT)
        }

        Log.i(TAG, "Notification clicked for chat: . Forwarding to LINE and clearing card.")

        // 1. 直達跳轉至 LINE 聊天室
        if (originalIntent != null) {
            try {
                originalIntent.send()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward click Intent to LINE", e)
            }
        }

        // 2. 清空該聊天室訊息與取消卡片 (已讀同步)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = NotiStackDatabase.getInstance(context)
                db.clearChatMessages(chatKey)
                NotificationDispatcher.getInstance(context).cancelStackedNotification(chatKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear messages on notification click", e)
            }
        }
    }
}
