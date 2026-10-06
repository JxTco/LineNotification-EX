package com.notistack.line.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 來電操作廣播接收器 (Phase 4.2.6)
 *
 * 攔截來電通知卡片上的「接聽」、「拒絕」與「靜音」操作：
 * 1. 點擊「接聽」或「拒絕」：第一時間 (0ms) 終止 CallRingtonePlayer 鈴聲與震動，清除通話卡片，隨後轉發呼叫 LINE 原廠 PendingIntent。
 * 2. 點擊「靜音」：立即調用 CallRingtonePlayer.muteRingtone() 實現一鍵靜音與停震，通話保持響鈴等待狀態不被掛斷。
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_CALL_ANSWER = "com.notistack.line.ACTION_CALL_ANSWER"
        const val ACTION_CALL_DECLINE = "com.notistack.line.ACTION_CALL_DECLINE"
        const val ACTION_CALL_MUTE = "com.notistack.line.ACTION_CALL_MUTE"

        const val EXTRA_CHAT_KEY = "extra_chat_key"
        const val EXTRA_ORIGINAL_PENDING_INTENT = "extra_original_pending_intent"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val chatKey = intent.getStringExtra(EXTRA_CHAT_KEY) ?: ""

        Log.i(TAG, "Received call action: $action for chat: $chatKey")

        when (action) {
            ACTION_CALL_ANSWER, ACTION_CALL_DECLINE -> {
                // 步驟 1: 即刻終止鈴聲播放與震動 (0 毫秒延遲)
                CallRingtonePlayer.stopRingtone(context)

                // 步驟 2: 消除自訂來電通知卡片
                if (chatKey.isNotBlank()) {
                    NotificationDispatcher.getInstance(context).cancelCallNotification(chatKey)
                }

                // 步驟 3: 轉發呼叫 LINE 原生 PendingIntent (接聽或拒絕)
                val originalPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT, PendingIntent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT)
                }

                if (originalPendingIntent != null) {
                    try {
                        originalPendingIntent.send()
                        Log.i(TAG, "Successfully forwarded original call pending intent for $action")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send original call pending intent", e)
                    }
                }
            }

            ACTION_CALL_MUTE -> {
                // 步驟: 一鍵靜音鈴聲並停止震動
                CallRingtonePlayer.muteRingtone(context)
            }
        }
    }
}
