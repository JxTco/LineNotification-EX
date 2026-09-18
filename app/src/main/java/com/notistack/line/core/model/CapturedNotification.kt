package com.notistack.line.core.model

/**
 * 封裝從 NotificationListenerService 捕獲的原生通知或移除事件資訊
 */
data class CapturedNotification(
    val id: String = java.util.UUID.randomUUID().toString(),
    val eventType: EventType,
    val packageName: String,
    val isLineApp: Boolean,
    val userId: Int,
    val uid: Int,
    val postTime: Long,
    val title: String?,
    val text: String?,
    val subText: String?,
    val conversationTitle: String?,
    val category: String?,
    val channelId: String?,
    val actionTitles: List<String> = emptyList(),
    val hasRemoteInputReply: Boolean = false,
    val removeReason: Int? = null,
    val rawKey: String
)

enum class EventType {
    POSTED,
    REMOVED
}
