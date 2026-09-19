package com.notistack.line.parser

import android.app.Notification
import android.app.PendingIntent
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
     * 從 StatusBarNotification 建立移除事件記錄
     */
    fun parseRemoved(sbn: StatusBarNotification, reason: Int): CapturedNotification {
        val base = parsePosted(sbn)
        return base.copy(
            eventType = EventType.REMOVED,
            removeReason = reason
        )
    }

    /**
     * 判定是否為 LINE 原生通知 (嚴格限定官方 LINE package，絕不包含自身套件)
     */
    fun isTargetPackage(packageName: String): Boolean {
        return packageName == LINE_PACKAGE_NAME
    }

    /**
     * 判定是否為 Android 群組摘要通知 (Group Summary)
     */
    fun isGroupSummary(sbn: StatusBarNotification): Boolean {
        return (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
    }

    /**
     * 從 SBN 提取聊天室關鍵識別資訊與訊息內容
     * 精確辨識個人聊天室 (DM) 與群組聊天室 (Group)，保證同一發送人在不同聊天室絕不混淆
     */
    fun extractChatInfo(sbn: StatusBarNotification): ParsedChatInfo? {
        val notification = sbn.notification
        val extras = notification.extras ?: return null

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return null
        var rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

        val userId = extractUserId(sbn.user)
        val accountId = "user_$userId"

        val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            notification.shortcutId?.trim()
        } else null

        var isGroup: Boolean = false
        var chatTitle: String = rawTitle
        var senderName: String = rawTitle

        // 規則 0: 嘗試從 AndroidX MessagingStyle 原生結構解析 (若 LINE 內部採用 MessagingStyle)
        val messagingStyle = try {
            androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        } catch (e: Exception) {
            null
        }

        if (messagingStyle != null) {
            val styleGroupTitle = messagingStyle.conversationTitle?.toString()?.trim()
            val isStyleGroup = messagingStyle.isGroupConversation || !styleGroupTitle.isNullOrEmpty()
            val latestMessage = messagingStyle.messages.lastOrNull()

            if (latestMessage != null) {
                val styleSender = latestMessage.person?.name?.toString()?.trim()
                val styleContent = latestMessage.text?.toString()?.trim()
                if (!styleContent.isNullOrBlank()) {
                    rawText = styleContent
                }
                if (!styleSender.isNullOrBlank()) {
                    senderName = styleSender
                }
            }

            if (isStyleGroup && !styleGroupTitle.isNullOrEmpty()) {
                isGroup = true
                chatTitle = styleGroupTitle
            }
        }

        // 若 MessagingStyle 未能明確判定群組，依序執行文字與欄位特徵判定
        if (!isGroup) {
            // 規則 1: 具有明確的 conversationTitle (標準群組對話)
            if (!conversationTitle.isNullOrEmpty()) {
                isGroup = true
                chatTitle = conversationTitle
                senderName = rawTitle
            }
            // 規則 2: subText 包含群組名稱 (LINE 常見群組模式：title 是發送人，subText 是群組名)
            else if (!subText.isNullOrEmpty() && subText != rawTitle) {
                isGroup = true
                chatTitle = subText
                senderName = rawTitle
            }
            // 規則 3: title 包含括號格式，例如 "工作群組 (小明)" 或 "工作群組（小明）" 或 "[工作群組] 小明"
            else if (rawTitle.contains(" (") || rawTitle.contains("（") || (rawTitle.startsWith("[") && rawTitle.contains("]"))) {
                val parenMatch = Regex("""^(.*?)[（\(](.*?)[）\)]\s*$""").find(rawTitle)
                    ?: Regex("""^\[(.*?)\]\s*(.*?)$""").find(rawTitle)

                if (parenMatch != null) {
                    isGroup = true
                    chatTitle = parenMatch.groupValues[1].trim()
                    senderName = parenMatch.groupValues[2].trim()
                } else {
                    chatTitle = rawTitle
                    senderName = rawTitle
                    isGroup = false
                }
            }
            // 規則 4: title 是群組名，而內文以 "發送人: 訊息" 開頭
            else {
                val textPrefixMatch = Regex("""^([^:\n]{1,30})[:：]\s*(.*)$""").find(rawText)
                if (textPrefixMatch != null) {
                    isGroup = true
                    chatTitle = rawTitle
                    senderName = textPrefixMatch.groupValues[1].trim()
                    rawText = textPrefixMatch.groupValues[2].trim()
                } else {
                    // 個人聊天室 (1-on-1 Direct Message)
                    isGroup = false
                    chatTitle = rawTitle
                    senderName = rawTitle
                }
            }
        }

        // 建立絕不碰撞的 chatKey (個人聊天室與群組聊天室使用不同前綴)
        val sanitizedTitle = chatTitle.replace(" ", "_").replace("/", "_")
        val chatKey = if (!shortcutId.isNullOrBlank()) {
            "${accountId}_sc_${shortcutId}"
        } else if (isGroup) {
            "${accountId}_grp_${sanitizedTitle}"
        } else {
            "${accountId}_dm_${sanitizedTitle}"
        }

        var hasRemoteInput = false
        var replyPendingIntent: PendingIntent? = null
        var remoteInputResultKey: String? = null
        var remoteInputLabel: String? = null

        notification.actions?.forEach { action ->
            val remoteInputs = action.remoteInputs
            if (!remoteInputs.isNullOrEmpty()) {
                hasRemoteInput = true
                val firstInput = remoteInputs[0]
                replyPendingIntent = action.actionIntent
                remoteInputResultKey = firstInput.resultKey
                remoteInputLabel = firstInput.label?.toString()
            }
        }

        val isRetraction = isRetractNotification(rawText)

        return ParsedChatInfo(
            accountId = accountId,
            chatKey = chatKey,
            chatTitle = chatTitle,
            senderName = senderName,
            content = rawText,
            isGroup = isGroup,
            timestamp = sbn.postTime,
            contentIntent = notification.contentIntent,
            hasRemoteInput = hasRemoteInput,
            replyPendingIntent = replyPendingIntent,
            remoteInputResultKey = remoteInputResultKey,
            remoteInputLabel = remoteInputLabel,
            isRetraction = isRetraction,
            isGroupSummary = isGroupSummary(sbn)
        )
    }

    /**
     * 判定是否為 LINE 收回訊息通知
     */
    fun isRetractNotification(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains("收回了一則訊息") ||
                text.contains("收回訊息") ||
                text.contains("已收回訊息") ||
                text.contains("unsent a message") ||
                text.contains("unsent")
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
    val contentIntent: PendingIntent?,
    val hasRemoteInput: Boolean,
    val replyPendingIntent: PendingIntent? = null,
    val remoteInputResultKey: String? = null,
    val remoteInputLabel: String? = null,
    val isRetraction: Boolean = false,
    val isGroupSummary: Boolean = false
)
