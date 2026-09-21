package com.notistack.line.service

import android.os.Build
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

        // 清理先前測試中可能殘留於系統 Snooze 隊列中的 LINE 通知，解除 OS 靜音壓制
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val snoozed = snoozedNotifications
                snoozed?.forEach { sbn ->
                    if (NotificationParser.isTargetPackage(sbn.packageName)) {
                        try {
                            snoozeNotification(sbn.key, 1L)
                        } catch (e: Exception) {
                            cancelNotification(sbn.key)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean snoozed notifications", e)
            }
        }
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
            val chatInfo = NotificationParser.extractChatInfo(sbn)
            if (chatInfo != null && ReplyStateTracker.wasJustReplied(chatInfo.chatKey)) {
                Log.i(TAG, "Skipped read dismissal for chat ${chatInfo.chatKey} because user just replied from notification card.")
            } else {
                handleLineReadDismissal(sbn)
            }
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
            // 若處於模式 B，消除原生群組摘要通知，防止摘要殘留於通知列
            if (settingsManager.notificationMode.value == NotificationMode.MODE_B_HIDE_NATIVE) {
                cancelNotification(sbn.key)
            }
            return
        }

        val chatInfo = NotificationParser.extractChatInfo(sbn) ?: return
        if (chatInfo.content.isBlank()) return

        // 去重第 2 層：比對系統事件層級 (相同 sbn.key、相同內文與相同 postTime)
        if (MessageDeduplicator.isDuplicateSystemEvent(sbn.key, chatInfo.content, sbn.postTime)) {
            Log.d(TAG, "Duplicate system callback event ignored: ${sbn.key}")
            return
        }

        // 快取原生直接回覆 Action (若有的話)
        if (chatInfo.hasRemoteInput && chatInfo.replyPendingIntent != null) {
            dispatcher.cacheReplyAction(
                chatKey = chatInfo.chatKey,
                lineReplyPendingIntent = chatInfo.replyPendingIntent,
                resultKey = chatInfo.remoteInputResultKey,
                label = chatInfo.remoteInputLabel
            )
        }

        serviceScope.launch {
            try {
                // 處理收回訊息事件
                if (chatInfo.isRetraction) {
                    val isKeep = settingsManager.isRetractKeepEnabled.value
                    if (isKeep) {
                        database.markLatestMessageRetracted(chatInfo.chatKey, chatInfo.senderName)
                        Log.i(TAG, "Marked retracted message for chat: ${chatInfo.chatKey}")
                    } else {
                        database.deleteLatestMessage(chatInfo.chatKey, chatInfo.senderName)
                        Log.i(TAG, "Deleted retracted message for chat: ${chatInfo.chatKey}")
                    }

                    // 重新刷新自訂堆疊通知卡片
                    val currentChat = database.getChat(chatInfo.chatKey)
                    if (currentChat != null && currentChat.isStackEnabled) {
                        val messages = database.getRecentMessages(currentChat.chatKey, limit = 15)
                        if (messages.isEmpty()) {
                            dispatcher.cancelStackedNotification(currentChat.chatKey)
                        } else {
                            dispatcher.dispatchStackedNotification(
                                chat = currentChat,
                                messages = messages,
                                contentIntent = chatInfo.contentIntent,
                                isNewMessage = false
                            )
                        }
                    }

                    // 模式 B：隱藏原生收回訊息通知
                    if (settingsManager.notificationMode.value == NotificationMode.MODE_B_HIDE_NATIVE) {
                        cancelNotification(sbn.key)
                    }
                    return@launch
                }

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

                    // 模式 B (隱藏 LINE 原生通知)：消除該則 LINE 原生通知
                    // 注意：絕不使用 snoozeNotification (snooze 會導致 Android OS 將該聊天室後續通知全面靜音壓制)
                    if (settingsManager.notificationMode.value == NotificationMode.MODE_B_HIDE_NATIVE) {
                        cancelNotification(sbn.key)
                        Log.d(TAG, "Mode B active: Cancelled LINE native notification for ${sbn.key}")
                    }
                } else {
                    // 該聊天室已關閉自訂堆疊 (或全域關閉堆疊)
                    if (settingsManager.notificationMode.value == NotificationMode.MODE_B_HIDE_NATIVE) {
                        if (!chat.keepNativeWhenDisabled) {
                            // 使用者選擇在關閉堆疊時，連原生通知也一併消除 (徹底靜音封鎖)
                            cancelNotification(sbn.key)
                            Log.d(TAG, "Chat ${chat.title} stack disabled and keepNative is false: Cancelled LINE native notification.")
                        } else {
                            // 使用者選擇保留該聊天室之原生通知 (回歸 LINE 原生提醒)
                            Log.d(TAG, "Chat ${chat.title} stack disabled: Preserved LINE native notification.")
                        }
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
