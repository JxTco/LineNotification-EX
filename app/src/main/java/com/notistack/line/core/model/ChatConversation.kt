package com.notistack.line.core.model

/**
 * 聊天室對話實體
 */
data class ChatConversation(
    val chatKey: String,
    val accountId: String,
    val title: String,
    val isGroup: Boolean,
    val isStackEnabled: Boolean = true,
    val lastMessageTime: Long,
    val lastMessageContent: String,
    val unreadCount: Int = 1,
    val customRingtoneUri: String? = null,
    val isMuted: Boolean = false,
    val keepNativeWhenDisabled: Boolean = true
)

/**
 * 單則訊息實體
 */
data class ChatMessage(
    val id: Long = 0,
    val chatKey: String,
    val senderName: String,
    val content: String,
    val timestamp: Long,
    val isRetracted: Boolean = false,
    val retractedTimestamp: Long? = null,
    val rawNotificationKey: String? = null
)

/**
 * 自訂分類標籤群組實體 (Phase 4.2.1)
 */
data class ChatTagGroup(
    val groupId: Long = 0,
    val name: String,
    val colorHex: String = "#4CAF50",
    val memberCount: Int = 0
)
