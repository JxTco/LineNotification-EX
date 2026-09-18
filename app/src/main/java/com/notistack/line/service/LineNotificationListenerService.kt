package com.notistack.line.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notistack.line.data.local.NotiStackDatabase
import com.notistack.line.data.preferences.NotificationMode
import com.notistack.line.data.preferences.SettingsManager
import com.notistack.line.data.repository.NotificationLogRepository
import com.notistack.line.parser.NotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LineNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var database: NotiStackDatabase
    private lateinit var dispatcher: NotificationDispatcher
    private lateinit var settingsManager: SettingsManager

    companion object {
        private const val TAG = "LineNotifyService"
        var instance: LineNotificationListenerService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        database = NotiStackDatabase.getInstance(this)
        dispatcher = NotificationDispatcher.getInstance(this)
        settingsManager = SettingsManager.getInstance(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NotificationLogRepository.setServiceConnected(true)
        Log.i(TAG, "NotificationListenerService connected successfully.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        NotificationLogRepository.setServiceConnected(false)
        Log.w(TAG, "NotificationListenerService disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val captured = NotificationParser.parsePosted(sbn)
        if (captured.isLineApp) {
            Log.d(TAG, "Line Notification Posted: [User ${captured.userId}] ${captured.title} - ${captured.text}")
            NotificationLogRepository.addNotification(captured)

            handleIncomingLineNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return

        val captured = NotificationParser.parseRemoved(sbn, reason)
        if (captured.isLineApp) {
            val reasonDesc = NotificationParser.getRemovalReasonDescription(reason)
            Log.d(TAG, "Line Notification Removed: [Reason $reasonDesc] ${captured.title}")
            NotificationLogRepository.addNotification(captured)

            // 判斷是否為 LINE 內已讀消除 (REASON_APP_CANCEL == 8)
            if (reason == 8) {
                handleLineReadDismissal(sbn)
            }
        }
    }

    /**
     * 處理收到的 LINE 訊息：儲存、堆疊、派發與模式判斷
     */
    private fun handleIncomingLineNotification(sbn: StatusBarNotification) {
        val chatInfo = NotificationParser.extractChatInfo(sbn) ?: return
        if (chatInfo.content.isBlank()) return

        serviceScope.launch {
            try {
                // 1. 儲存至本地對話與訊息資料庫
                val chat = database.saveIncomingMessage(
                    chatKey = chatInfo.chatKey,
                    accountId = chatInfo.accountId,
                    chatTitle = chatInfo.chatTitle,
                    isGroup = chatInfo.isGroup,
                    senderName = chatInfo.senderName,
                    content = chatInfo.content,
                    timestamp = chatInfo.timestamp,
                    rawKey = sbn.key
                )

                val isGlobalEnabled = settingsManager.isGlobalStackEnabled.value
                val isChatEnabled = chat.isStackEnabled

                // 2. 若啟用堆疊，則構建並發布 MessagingStyle 自訂通知
                if (isGlobalEnabled && isChatEnabled) {
                    val messages = database.getRecentMessages(chat.chatKey, limit = 15)
                    dispatcher.dispatchStackedNotification(
                        chat = chat,
                        messages = messages,
                        contentIntent = chatInfo.contentIntent
                    )

                    // 3. 模式 B (隱藏 LINE 原生通知)：自動取消原生通知
                    if (settingsManager.notificationMode.value == NotificationMode.MODE_B_HIDE_NATIVE) {
                        cancelNotification(sbn.key)
                        Log.d(TAG, "Mode B active: Cancelled native notification for ${sbn.key}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming LINE notification", e)
            }
        }
    }

    /**
     * 處理 LINE 原生已讀消除事件 (同步清除自訂堆疊通知)
     */
    private fun handleLineReadDismissal(sbn: StatusBarNotification) {
        val chatInfo = NotificationParser.extractChatInfo(sbn) ?: return

        serviceScope.launch {
            try {
                Log.i(TAG, "Detected read in LINE for chat: ${chatInfo.chatTitle}. Auto dismissing stacked notification.")
                // 1. 清空該聊天室快取之未讀訊息
                database.clearChatMessages(chatInfo.chatKey)
                // 2. 取消自訂堆疊通知
                dispatcher.cancelStackedNotification(chatInfo.chatKey)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling read dismissal", e)
            }
        }
    }
}
