package com.notistack.line.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput
import com.notistack.line.data.local.NotiStackDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 處理通知列直接回覆 (Direct Reply) 的廣播接收器
 * 負責將使用者輸入的回覆文字轉發給 LINE，並原地更新通知卡片保留「我: ...」內容
 */
class ReplyActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReplyActionReceiver"
        const val ACTION_REPLY = "com.notistack.line.ACTION_DIRECT_REPLY"
        const val EXTRA_CHAT_KEY = "extra_chat_key"
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
        const val EXTRA_CHAT_TITLE = "extra_chat_title"
        const val EXTRA_IS_GROUP = "extra_is_group"
        const val EXTRA_LINE_PENDING_INTENT = "extra_line_pending_intent"
        const val EXTRA_RESULT_KEY = "extra_result_key"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val resultKey = intent.getStringExtra(EXTRA_RESULT_KEY) ?: "line_reply_key"
        val replyText = remoteInput.getCharSequence(resultKey)?.toString()?.trim()

        if (replyText.isNullOrBlank()) return

        val chatKey = intent.getStringExtra(EXTRA_CHAT_KEY) ?: return
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: "user_0"
        val chatTitle = intent.getStringExtra(EXTRA_CHAT_TITLE) ?: ""
        val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
        val lineReplyPendingIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_LINE_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_LINE_PENDING_INTENT)
        }

        Log.d(TAG, "Received direct reply: '$replyText' for chat: $chatTitle")

        // 1. 轉發回覆文字給 LINE 原生 PendingIntent
        if (lineReplyPendingIntent != null) {
            val forwardIntent = Intent()
            val forwardBundle = Bundle().apply {
                putCharSequence(resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(resultKey).build()),
                forwardIntent,
                forwardBundle
            )
            try {
                lineReplyPendingIntent.send(context, 0, forwardIntent)
                Log.i(TAG, "Successfully forwarded reply to LINE")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply to LINE", e)
            }
        }

        // 2. 樂觀更新：將「我」發出的回覆寫入本地快取，並原地更新通知卡片 (通知保留在通知列中)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = NotiStackDatabase.getInstance(context)
                val saveResult = db.saveIncomingMessage(
                    chatKey = chatKey,
                    accountId = accountId,
                    chatTitle = chatTitle,
                    isGroup = isGroup,
                    senderName = "我",
                    content = replyText,
                    timestamp = System.currentTimeMillis(),
                    rawKey = null
                )

                val messages = db.getRecentMessages(chatKey, limit = 15)
                NotificationDispatcher.getInstance(context).dispatchStackedNotification(
                    chat = saveResult.chat,
                    messages = messages,
                    contentIntent = null,
                    isNewMessage = false // 原地更新，不重複震動與鈴響
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error updating notification with direct reply", e)
            }
        }
    }
}
