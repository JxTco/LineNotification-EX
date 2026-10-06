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

        // 判定 LINE 通話狀態 (Phase 4.2.3 & 4.2.6)
        val callState = extractCallState(sbn)
        val isCall = (callState == CallState.INCOMING)

        // 智慧通話名稱提取 (Phase 4.2.3 & 4.2.6):
        // 在通話通知中，LINE 常將標題設為「LINE語音通話」或「語音通話」，而將發話人名稱置於內文或 EXTRA_CALL_PERSON
        if (callState != CallState.NONE) {
            val callPerson = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val person = extras.getParcelable<android.app.Person>("android.callPerson")
                    person?.name?.toString()?.trim()
                } else null
            } catch (e: Exception) {
                null
            }

            val genericCallTitles = listOf("LINE", "LINE語音通話", "語音通話", "語音通話來電", "LINE 語音通話", "視訊通話", "LINE視訊通話", "來電")
            val isTitleGeneric = genericCallTitles.any { rawTitle.equals(it, ignoreCase = true) || rawTitle.startsWith(it, ignoreCase = true) }

            if (!callPerson.isNullOrBlank()) {
                chatTitle = callPerson
                senderName = callPerson
            } else if (isTitleGeneric && rawText.isNotBlank() && !genericCallTitles.any { rawText.equals(it, ignoreCase = true) }) {
                // Title 是 "LINE語音通話"，Text 是 "小明" -> 提取真正人名
                chatTitle = rawText
                senderName = rawText
            } else if (!isTitleGeneric) {
                // Title 是 "小明"，Text 是 "LINE語音通話" -> Title 即人名
                chatTitle = rawTitle
                senderName = rawTitle
            }

            if (rawText.isBlank() || isTitleGeneric) {
                rawText = when (callState) {
                    CallState.INCOMING -> "語音通話來電"
                    CallState.ONGOING -> "通話進行中"
                    CallState.ENDED -> "通話已結束"
                    CallState.NONE -> rawText
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

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val tickerText = notification.tickerText?.toString()

        val isRetraction = isRetractNotification(rawText, bigText, subText, tickerText)

        // 若為收回事件，嘗試從內文萃取真正的收回發言人 (例如群組中「小明已收回訊息」)
        if (isRetraction) {
            val retractSender = extractRetractSender(rawText) 
                ?: extractRetractSender(bigText) 
                ?: extractRetractSender(tickerText)
            if (!retractSender.isNullOrBlank()) {
                senderName = retractSender
            }
        }

        val callActions = if (callState != CallState.NONE && notification.actions != null) {
            notification.actions.toList()
        } else {
            emptyList()
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
            hasRemoteInput = hasRemoteInput,
            replyPendingIntent = replyPendingIntent,
            remoteInputResultKey = remoteInputResultKey,
            remoteInputLabel = remoteInputLabel,
            isRetraction = isRetraction,
            isGroupSummary = isGroupSummary(sbn),
            isCall = isCall,
            callState = callState,
            callActions = callActions,
            fullScreenIntent = notification.fullScreenIntent
        )
    }

    /**
     * 判定是否為 LINE 收回訊息通知 (綜合比對多個欄位來源與不同版本之繁中/英文用詞)
     */
    fun isRetractNotification(vararg texts: CharSequence?): Boolean {
        val combined = texts.filterNotNull().joinToString(" ")
        if (combined.isBlank()) return false
        return combined.contains("收回了一則訊息") ||
                combined.contains("已收回了一則訊息") ||
                combined.contains("收回訊息") ||
                combined.contains("已收回訊息") ||
                combined.contains("已收回") ||
                combined.contains("unsent a message") ||
                combined.contains("unsent")
    }

    /**
     * 從收回文字中提取發言人名稱 (例如「小明已收回訊息」、「小明: 已收回訊息」或「小明收回了一則訊息」)
     */
    fun extractRetractSender(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""^([^:\n]{1,30})[:：]?\s*(?:已收回|收回了)""").find(text.trim())
        val extracted = match?.groupValues?.getOrNull(1)?.trim()
        return if (!extracted.isNullOrBlank() && extracted != text.trim()) extracted else null
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
     * 判定是否為 LINE 來電通話通知 (Phase 4.2.3 & 4.2.6)
     */
    fun isCallNotification(sbn: StatusBarNotification): Boolean {
        return extractCallState(sbn) == CallState.INCOMING
    }

    /**
     * 精準判定 LINE 通話狀態 (來電中 / 通話進行中 / 通話結束) (Phase 4.2.6)
     */
    fun extractCallState(sbn: StatusBarNotification): CallState {
        return try {
            val notification = sbn.notification ?: return CallState.NONE

            val category = notification.category
            // 1. 標準 CATEGORY_MISSED_CALL 直接判定為通話已結束
            if (category == Notification.CATEGORY_MISSED_CALL) {
                return CallState.ENDED
            }

            val extras = notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val tickerText = notification.tickerText?.toString() ?: ""
            val combined = "$title $text $subText $tickerText"

            // 2. 結束關鍵字判定
            val endedKeywords = listOf("未接來電", "Missed call", "通話已結束", "取消通話", "已取消", "Call ended", "通話結束")
            if (endedKeywords.any { combined.contains(it, ignoreCase = true) }) {
                return CallState.ENDED
            }

            // 3. Android 12+ CallStyle 特徵 (CALL_TYPE_INCOMING = 1, CALL_TYPE_ONGOING = 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && extras != null) {
                val callType = extras.getInt("android.callType", -1)
                if (callType == 2) return CallState.ONGOING
                if (callType == 1) return CallState.INCOMING
            }

            // 4. 分析 Notification Actions 意圖 (接聽、拒絕、掛斷)
            val actions = notification.actions
            val hasAnswerAction = actions != null && actions.any { act ->
                val actionTitle = act.title?.toString() ?: ""
                actionTitle.contains("接聽") || actionTitle.contains("Answer", ignoreCase = true)
            }
            val hasDeclineAction = actions != null && actions.any { act ->
                val actionTitle = act.title?.toString() ?: ""
                actionTitle.contains("拒絕") || actionTitle.contains("Decline", ignoreCase = true)
            }
            val hasHangupAction = actions != null && actions.any { act ->
                val actionTitle = act.title?.toString() ?: ""
                actionTitle.contains("掛斷") || actionTitle.contains("切斷") || actionTitle.contains("Hang", ignoreCase = true) || actionTitle.contains("End call", ignoreCase = true)
            }

            // 5. 判斷是否為通話進行中 (ONGOING)
            // 特徵：只有掛斷按鈕而無接聽按鈕，或者文字包含「通話中」、「00:XX」等計時模式
            val ongoingKeywords = listOf("通話中", "通話進行中", "LINE通話中", "語音通話中", "視訊通話中", "Ongoing call", "In call", "通話時間")
            val hasOngoingKeyword = ongoingKeywords.any { combined.contains(it, ignoreCase = true) }
            val hasTimerPattern = Regex("""\b\d{2}:\d{2}\b""").containsMatchIn(combined)

            if ((hasHangupAction && !hasAnswerAction) || hasOngoingKeyword || hasTimerPattern) {
                return CallState.ONGOING
            }

            // 6. 判斷是否為來電響鈴中 (INCOMING)
            if (hasAnswerAction || (hasDeclineAction && !hasHangupAction)) {
                return CallState.INCOMING
            }

            val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.channelId else null
            val isCallChannel = channelId != null && (
                channelId.lowercase().contains("call") || channelId.lowercase().contains("voip") ||
                channelId.contains("通話") || channelId.contains("來電")
            )
            val isCallCategory = category == Notification.CATEGORY_CALL
            val hasIncomingKeyword = combined.contains("來電") || combined.contains("Incoming call", ignoreCase = true) ||
                    combined.contains("語音通話") || combined.contains("視訊通話")

            if ((isCallCategory || isCallChannel || (extras != null && extras.containsKey("android.callPerson"))) && hasIncomingKeyword) {
                return CallState.INCOMING
            }

            CallState.NONE
        } catch (e: Exception) {
            CallState.NONE
        }
    }
}

/**
 * LINE 通話狀態 (Phase 4.2.6)
 */
enum class CallState {
    NONE,       // 非通話事件
    INCOMING,   // 來電響鈴中 (待接聽)
    ONGOING,    // 通話進行中 (已接聽/進行中)
    ENDED       // 未接來電或通話已結束
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
    val isGroupSummary: Boolean = false,
    val isCall: Boolean = false,
    val callState: CallState = CallState.NONE,
    val callActions: List<Notification.Action> = emptyList(),
    val fullScreenIntent: PendingIntent? = null
)
