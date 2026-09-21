package com.notistack.line.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.notistack.line.core.model.ChatConversation
import com.notistack.line.core.model.ChatMessage
import com.notistack.line.data.preferences.SettingsManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class CachedReplyInfo(
    val lineReplyPendingIntent: PendingIntent,
    val resultKey: String,
    val label: String?
)

class NotificationDispatcher(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    // 快取個別聊天室原生有效的直達 Intent
    private val chatContentIntents = ConcurrentHashMap<String, PendingIntent>()

    // 快取個別聊天室的原生 RemoteInput 與 Reply PendingIntent
    private val chatReplyActions = ConcurrentHashMap<String, CachedReplyInfo>()

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
     * 快取原生 LINE 回覆 Action 資訊
     */
    fun cacheReplyAction(
        chatKey: String,
        lineReplyPendingIntent: PendingIntent?,
        resultKey: String?,
        label: String?
    ) {
        if (lineReplyPendingIntent != null && !resultKey.isNullOrBlank()) {
            chatReplyActions[chatKey] = CachedReplyInfo(
                lineReplyPendingIntent = lineReplyPendingIntent,
                resultKey = resultKey,
                label = label
            )
        }
    }

    /**
     * 派發或更新指定聊天室的自訂 MessagingStyle 堆疊通知
     *
     * @param chat 聊天室基本資訊
     * @param messages 該聊天室歷史未清除訊息清單 (時間由舊到新)
     * @param contentIntent 原生 LINE 的點擊 Intent (點擊可直接跳轉 LINE 聊天室)
     * @param isNewMessage 是否為新訊息 (若為既有訊息更新，則靜默更新不重複震動鈴響)
     */
    fun dispatchStackedNotification(
        chat: ChatConversation,
        messages: List<ChatMessage>,
        contentIntent: PendingIntent?,
        isNewMessage: Boolean = true
    ) {
        if (messages.isEmpty()) return

        val notificationId = getNotificationId(chat.chatKey)

        // 決定通道 (個別聊天室覆寫 -> 帳號預設 -> 全域預設)
        val targetChannelId = resolveNotificationChannel(chat)

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
            val senderPerson = if (msg.senderName == "我") {
                userPerson
            } else {
                Person.Builder()
                    .setName(msg.senderName)
                    .setKey(msg.senderName)
                    .build()
            }

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

        val builder = NotificationCompat.Builder(context, targetChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!isNewMessage)

        // 綁定直達該聊天室的原生 PendingIntent
        if (contentIntent != null) {
            chatContentIntents[chat.chatKey] = contentIntent
        }
        val intentToUse = contentIntent ?: chatContentIntents[chat.chatKey]

        // 綁定點擊 Intent：透過 NotificationClickReceiver 轉發至 LINE 並同步清空快取與消除卡片
        val clickIntent = Intent(context, NotificationClickReceiver::class.java).apply {
            action = NotificationClickReceiver.ACTION_STACK_CLICKED
            putExtra(NotificationClickReceiver.EXTRA_CHAT_KEY, chat.chatKey)
            if (intentToUse != null) {
                putExtra(NotificationClickReceiver.EXTRA_CONTENT_INTENT, intentToUse)
            }
        }
        val clickPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 30000,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        builder.setContentIntent(clickPendingIntent)

        // 綁定手動劃除 (Swipe-to-Dismiss) Intent：使用者手動滑除卡片時清空該聊天室訊息快取
        val deleteIntent = Intent(context, NotificationDismissedReceiver::class.java).apply {
            action = NotificationDismissedReceiver.ACTION_STACK_DISMISSED
            putExtra(NotificationDismissedReceiver.EXTRA_CHAT_KEY, chat.chatKey)
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 20000,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setDeleteIntent(deletePendingIntent)

        // 綁定直接回覆 (Direct Reply) Action
        val replyInfo = chatReplyActions[chat.chatKey]
        if (replyInfo != null) {
            val remoteInput = RemoteInput.Builder(replyInfo.resultKey)
                .setLabel(replyInfo.label ?: "回覆...")
                .build()

            val replyIntent = Intent(context, ReplyActionReceiver::class.java).apply {
                action = ReplyActionReceiver.ACTION_REPLY
                putExtra(ReplyActionReceiver.EXTRA_CHAT_KEY, chat.chatKey)
                putExtra(ReplyActionReceiver.EXTRA_ACCOUNT_ID, chat.accountId)
                putExtra(ReplyActionReceiver.EXTRA_CHAT_TITLE, chat.title)
                putExtra(ReplyActionReceiver.EXTRA_IS_GROUP, chat.isGroup)
                putExtra(ReplyActionReceiver.EXTRA_LINE_PENDING_INTENT, replyInfo.lineReplyPendingIntent)
                putExtra(ReplyActionReceiver.EXTRA_RESULT_KEY, replyInfo.resultKey)
            }

            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 10000,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                replyInfo.label ?: "回覆",
                replyPendingIntent
            ).addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build()

            builder.addAction(replyAction)
        }

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * 動態解析與建立指定聊天室/帳號的 NotificationChannel
     */
    private fun resolveNotificationChannel(chat: ChatConversation): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return STACK_CHANNEL_ID
        }

        val settingsManager = SettingsManager.getInstance(context)

        // 若聊天室設定靜音
        if (chat.isMuted) {
            val mutedChannelId = "notistack_channel_muted"
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(mutedChannelId) == null) {
                val channel = NotificationChannel(
                    mutedChannelId,
                    "靜音對話通知",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
                manager?.createNotificationChannel(channel)
            }
            return mutedChannelId
        }

        // 優先順序 1: 聊天室專屬自訂鈴聲
        val customSoundUri = chat.customRingtoneUri
        if (!customSoundUri.isNullOrBlank()) {
            return getOrCreateDynamicChannel("chat_${chat.chatKey}", "聊天室: ${chat.title}", customSoundUri)
        }

        // 優先順序 2: 帳號預設鈴聲
        val accountSoundUri = settingsManager.getAccountRingtoneUri(chat.accountId)
        if (!accountSoundUri.isNullOrBlank()) {
            return getOrCreateDynamicChannel("acc_${chat.accountId}", "帳號: ${chat.accountId}", accountSoundUri)
        }

        // 預設通道
        return STACK_CHANNEL_ID
    }

    private fun getOrCreateDynamicChannel(prefix: String, name: String, soundUriStr: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return STACK_CHANNEL_ID

        val soundUri = Uri.parse(soundUriStr)
        val channelId = "notistack_${prefix}_${abs(soundUriStr.hashCode())}"
        val manager = context.getSystemService(NotificationManager::class.java) ?: return STACK_CHANNEL_ID

        if (manager.getNotificationChannel(channelId) == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .build()

            val channel = NotificationChannel(
                channelId,
                name,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(soundUri, audioAttributes)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        return channelId
    }

    /**
     * 取消指定聊天室的自訂堆疊通知
     */
    fun cancelStackedNotification(chatKey: String) {
        val notificationId = getNotificationId(chatKey)
        notificationManager.cancel(notificationId)
        chatContentIntents.remove(chatKey)
    }

    fun getNotificationId(chatKey: String): Int {
        return abs(chatKey.hashCode())
    }
}
