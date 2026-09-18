package com.notistack.line.parser

import android.app.Notification
import android.os.Build
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.notistack.line.core.model.CapturedNotification
import com.notistack.line.core.model.EventType

object NotificationParser {

    const val LINE_PACKAGE_NAME = "jp.naver.line.android"

    /**
     * 從 StatusBarNotification 完整解析出結構化資訊
     */
    fun parsePosted(sbn: StatusBarNotification): CapturedNotification {
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val conversationTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        val actionTitles = mutableListOf<String>()
        var hasRemoteInput = false

        notification.actions?.forEach { action ->
            actionTitles.add(action.title?.toString() ?: "未命名動作")
            if (!action.remoteInputs.isNullOrEmpty()) {
                hasRemoteInput = true
            }
        }

        val userId = extractUserId(sbn.user)

        return CapturedNotification(
            eventType = EventType.POSTED,
            packageName = sbn.packageName,
            isLineApp = isTargetPackage(sbn.packageName),
            userId = userId,
            uid = sbn.uid,
            postTime = sbn.postTime,
            title = title,
            text = text,
            subText = subText,
            conversationTitle = conversationTitle,
            category = notification.category,
            channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.channelId else null,
            actionTitles = actionTitles,
            hasRemoteInputReply = hasRemoteInput,
            removeReason = null,
            rawKey = sbn.key
        )
    }

    /**
     * 從 SBN 提取聊天室關鍵識別資訊與訊息內容
     */
    fun extractChatInfo(sbn: StatusBarNotification): ParsedChatInfo? {
        val notification = sbn.notification
        val extras = notification.extras ?: return null

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return null
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()

        val userId = extractUserId(sbn.user)
        val accountId = "user_$userId"

        val isGroup: Boolean
        val chatTitle: String
        val senderName: String

        if (!conversationTitle.isNullOrEmpty()) {
            // 群組聊天：conversationTitle 為群組名，rawTitle 為發言者
            isGroup = true
            chatTitle = conversationTitle
            senderName = rawTitle
        } else {
            // 個人聊天或無 conversationTitle
            isGroup = false
            chatTitle = rawTitle
            senderName = rawTitle
        }

        val chatKey = "${accountId}_${chatTitle.replace(" ", "_")}"

        var hasRemoteInput = false
        notification.actions?.forEach { action ->
            if (!action.remoteInputs.isNullOrEmpty()) {
                hasRemoteInput = true
            }
        }

        return ParsedChatInfo(
            accountId = accountId,
            chatKey = chatKey,
            chatTitle = chatTitle,
            senderName = senderName,
            content = rawText,
            isGroup = isGroup,
            timestamp = sbn.postTime,
            contentIntent = notification.contentIntent,
            hasRemoteInput = hasRemoteInput
        )
    }

    /**
     * 從 UserHandle 擷取整數 User ID (支援 Samsung Dual Messenger, MIUI 雙開等)
     */
    fun extractUserId(userHandle: UserHandle): Int {
        val str = userHandle.toString() // e.g. "UserHandle{0}", "UserHandle{95}"
        val match = Regex("""UserHandle\{(\d+)\}""").find(str)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: userHandle.hashCode()
    }

    /**
     * 解釋移除原因代碼 (用於實機驗證分析)
     */
    fun getRemovalReasonDescription(reason: Int): String {
        return when (reason) {
            1 -> "REASON_CLICK (點擊開啟)"
            2 -> "REASON_CANCEL (使用者滑掉)"
            3 -> "REASON_CANCEL_ALL (使用者清除全部)"
            4 -> "REASON_ERROR (通知發生錯誤)"
            5 -> "REASON_PACKAGE_CHANGED (應用程式套件更新)"
            6 -> "REASON_USER_STOPPED (使用者終止程式)"
            7 -> "REASON_PACKAGE_BANNED (通知已被系統封鎖)"
            8 -> "REASON_APP_CANCEL (應用程式自發取消 - 通常代表 LINE 內已讀)"
            9 -> "REASON_APP_CANCEL_ALL (應用程式自發取消全部)"
            10 -> "REASON_LISTENER_CANCEL (被其他監聽服務取消)"
            11 -> "REASON_LISTENER_CANCEL_ALL (被其他監聽服務取消全部)"
            12 -> "REASON_GROUP_SUMMARY_CANCELED (通知組摘要取消)"
            13 -> "REASON_GROUP_OPTIMIZATION (通知群組自動優化)"
            14 -> "REASON_CHANNEL_BANNED (通知管道被禁用)"
            15 -> "REASON_CHANNEL_REMOVED (通知管道被移除)"
            16 -> "REASON_CLEAR_DATA (應用程式清除資料)"
            17 -> "REASON_ASSISTANT_CANCEL (助手取消)"
            else -> "未知原因 ($reason)"
        }
    }

    /**
     * 從 StatusBarNotification 建立移除事件記錄
     */
    fun parseRemoved(sbn: StatusBarNotification, reason: Int): CapturedNotification {
        val base = parsePosted(sbn)
        return base.copy(
            eventType = EventType.REMOVED,
            removeReason = reason
        )
    }

    fun isTargetPackage(packageName: String): Boolean {
        return packageName == LINE_PACKAGE_NAME || packageName == "com.notistack.line"
    }
}

/**
 * 結構化聊天室訊息資料
 */
data class ParsedChatInfo(
    val accountId: String,
    val chatKey: String,
    val chatTitle: String,
    val senderName: String,
    val content: String,
    val isGroup: Boolean,
    val timestamp: Long,
    val contentIntent: android.app.PendingIntent?,
    val hasRemoteInput: Boolean
)

