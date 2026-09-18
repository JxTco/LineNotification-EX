package com.notistack.line.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.notistack.line.core.model.ChatConversation
import com.notistack.line.core.model.ChatMessage
import kotlin.math.abs

class NotificationDispatcher(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    companion object {
        const val STACK_CHANNEL_ID = "notistack_stacked_channel"
        private const val STACK_CHANNEL_NAME = "LINE 自訂堆疊訊息通知"

        @Volatile
        private var INSTANCE: NotificationDispatcher? = null

        fun getInstance(context: Context): NotificationDispatcher {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationDispatcher(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        createChannelIfNeeded()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                STACK_CHANNEL_ID,
                STACK_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "將 LINE 相同聊天室的多則訊息整合為清晰對話框"
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * 派發或更新指定聊天室的自訂 MessagingStyle 堆疊通知
     *
     * @param chat 聊天室基本資訊
     * @param messages 該聊天室歷史未清除訊息清單 (時間由舊到新)
     * @param contentIntent 原生 LINE 的點擊 Intent (點擊可直接跳轉 LINE 聊天室)
     */
    fun dispatchStackedNotification(
        chat: ChatConversation,
        messages: List<ChatMessage>,
        contentIntent: PendingIntent?
    ) {
        if (messages.isEmpty()) return

        val notificationId = getNotificationId(chat.chatKey)

        // 建立 MessagingStyle，代表使用者本人
        val userPerson = Person.Builder()
            .setName("我")
            .setKey("current_user")
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)

        if (chat.isGroup) {
            messagingStyle.setConversationTitle(chat.title)
            messagingStyle.isGroupConversation = true
        }

        // 依序加入對話訊息
        messages.forEach { msg ->
            val senderPerson = Person.Builder()
                .setName(msg.senderName)
                .setKey(msg.senderName)
                .build()

            val textToDisplay = if (msg.isRetracted) {
                "~~${msg.content}~~ (已收回)"
            } else {
                msg.content
            }

            messagingStyle.addMessage(
                textToDisplay,
                msg.timestamp,
                senderPerson
            )
        }

        val builder = NotificationCompat.Builder(context, STACK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)

        // 綁定原生 LINE 的點擊行為 (直達聊天室)
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * 取消指定聊天室的自訂堆疊通知
     */
    fun cancelStackedNotification(chatKey: String) {
        val notificationId = getNotificationId(chatKey)
        notificationManager.cancel(notificationId)
    }

    fun getNotificationId(chatKey: String): Int {
        return abs(chatKey.hashCode())
    }
}
