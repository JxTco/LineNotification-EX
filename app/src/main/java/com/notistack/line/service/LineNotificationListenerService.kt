package com.notistack.line.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notistack.line.data.local.NotiStackDatabase
import com.notistack.line.data.preferences.NotificationMode
import com.notistack.line.data.preferences.SettingsManager
import com.notistack.line.data.repository.NotificationLogRepository
import com.notistack.line.parser.MessageDeduplicator
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

        // 關鍵保護 1：絕對排除自身 App 的通知，從根本杜絕任何通知回圈與自我刪除
        if (sbn.packageName == packageName) {
            val isTest = sbn.notification.extras?.getBoolean("is_notistack_test", false) == true
            if (isTest) {
                // 僅在 UI 日誌顯示測試通知，絕不進入 LINE 處理流程
                val captured = NotificationParser.parsePosted(sbn)
                NotificationLogRepository.addNotification(captured)
            }
            return
        }

        // 關鍵保護 2：嚴格限定只處理官方 LINE 套件
        if (!NotificationParser.isTargetPackage(sbn.packageName)) {
            return
        }

        val captured = NotificationParser.parsePosted(sbn)
        Log.d(TAG, "LINE Notification Posted: [User ${captured.userId}] ${captured.title} - ${captured.text}")
        NotificationLogRepository.addNotification(captured)

        handleIncomingLineNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return

        // 排除自身通知
        if (sbn.packageName == packageName) {
            return
        }

        if (!NotificationParser.isTargetPackage(sbn.packageName)) {
            return
        }

        val captured = NotificationParser.parseRemoved(sbn, reason)
        val reasonDesc = NotificationParser.getRemovalReasonDescription(reason)
        Log.d(TAG, "LINE Notification Removed: [Reason $reasonDesc] ${captured.title}")
        NotificationLogRepository.addNotification(captured)

        // 判斷是否為 LINE 內已讀消除 (REASON_APP_CANCEL == 8)
        if (reason == 8) {
            handleLineReadDismissal(sbn)
        }
    }

    /**
     * 處理收到的 LINE 訊息：去重、儲存、堆疊、派發與模式判斷
     */
    private fun handleIncomingLineNotification(sbn: StatusBarNotification) {
        // 去重第 1 層：過濾 Android Group Summary 摘要通知 (只處理具體子通知)
        val isSummary = NotificationParser.isGroupSummary(sbn)
        if (isSummary) {
            Log.d(TAG, "Ignored LINE group summary notification: ${sbn.key}")
            // 若處於模式 B，主動消除原生群組摘要通知，防止摘要殘留於通知列
            if (settingsManager.notificationMode.value == NotificationMode.MODE_B_HIDE_NATIVE) {
                cancelNotification(sbn.key)
            }
            return
        }

        val chatInfo = NotificationParser.extractChatInfo(sbn) ?: return
        if (chatInfo.content.isBlank()) return

        // 去重第 2 層：記憶體滑動時間窗 (8 秒內完全相同的訊息視為系統重複推播或更新)
        if (MessageDeduplicator.isDuplicate(chatInfo.accountId, chatInfo.chatKey, chatInfo.senderName, chatInfo.content)) {
            Log.d(TAG, "Duplicate message event ignored by MessageDeduplicator: ${chatInfo.chatKey} - ${chatInfo.content}")
            // 若在模式 B，雖然訊息不重複計入，但仍確保消除原生通知
            if (settingsManager.notificationMode.value == NotificationMode.MODE_B_HIDE_NATIVE) {
                cancelNotification(sbn.key)
            }
            return
        }

        serviceScope.launch {
            try {
                // 去重第 3 層：本地資料庫比對去重，若為既有訊息則 isNewMessage 為 false 且不增未讀數
                val saveResult = database.saveIncomingMessage(
                    chatKey = chatInfo.chatKey,
                    accountId = chatInfo.accountId,
                    chatTitle = chatInfo.chatTitle,
                    isGroup = chatInfo.isGroup,
                    senderName = chatInfo.senderName,
                    content = chatInfo.content,
                    timestamp = chatInfo.timestamp,
                    rawKey = sbn.key
                )

                val chat = saveResult.chat
                val isNewMessage = saveResult.isNewMessage

                val isGlobalEnabled = settingsManager.isGlobalStackEnabled.value
                val isChatEnabled = chat.isStackEnabled

                // 若啟用堆疊，則構建並發布 MessagingStyle 自訂通知
                if (isGlobalEnabled && isChatEnabled) {
                    val messages = database.getRecentMessages(chat.chatKey, limit = 15)
                    dispatcher.dispatchStackedNotification(
                        chat = chat,
                        messages = messages,
                        contentIntent = chatInfo.contentIntent,
                        isNewMessage = isNewMessage
                    )

                    // 模式 B (隱藏 LINE 原生通知)：消除該則 LINE 原生通知 (僅消除 LINE 原生 key)
                    if (settingsManager.notificationMode.value == NotificationMode.MODE_B_HIDE_NATIVE) {
                        cancelNotification(sbn.key)
                        Log.d(TAG, "Mode B active: Cancelled LINE native notification for ${sbn.key}")
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
