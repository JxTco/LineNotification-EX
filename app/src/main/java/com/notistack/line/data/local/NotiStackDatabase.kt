package com.notistack.line.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.notistack.line.core.model.ChatConversation
import com.notistack.line.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SaveMessageResult(
    val chat: ChatConversation,
    val isNewMessage: Boolean
)

class NotiStackDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _chatsFlow = MutableStateFlow<List<ChatConversation>>(emptyList())
    val chatsFlow: StateFlow<List<ChatConversation>> = _chatsFlow.asStateFlow()

    init {
        refreshChats()
    }

    companion object {
        private const val DB_NAME = "notistack.db"
        private const val DB_VERSION = 2

        private const val TABLE_CHATS = "chats"
        private const val TABLE_MESSAGES = "messages"

        @Volatile
        private var INSTANCE: NotiStackDatabase? = null

        fun getInstance(context: Context): NotiStackDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotiStackDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CHATS (
                chat_key TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                title TEXT NOT NULL,
                is_group INTEGER NOT NULL DEFAULT 0,
                is_stack_enabled INTEGER NOT NULL DEFAULT 1,
                last_message_time INTEGER NOT NULL,
                last_message_content TEXT NOT NULL,
                unread_count INTEGER NOT NULL DEFAULT 1,
                custom_ringtone_uri TEXT,
                is_muted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_key TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                is_retracted INTEGER NOT NULL DEFAULT 0,
                retracted_timestamp INTEGER,
                raw_key TEXT,
                FOREIGN KEY (chat_key) REFERENCES $TABLE_CHATS(chat_key) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_key ON $TABLE_MESSAGES(chat_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_CHATS ADD COLUMN custom_ringtone_uri TEXT")
                db.execSQL("ALTER TABLE $TABLE_CHATS ADD COLUMN is_muted INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                // Ignore if already added
            }
        }
    }

    /**
     * 儲存收到的訊息並更新聊天室狀態 (系統事件層級防重，文字相同不誤殺)
     */
    suspend fun saveIncomingMessage(
        chatKey: String,
        accountId: String,
        chatTitle: String,
        isGroup: Boolean,
        senderName: String,
        content: String,
        timestamp: Long,
        rawKey: String?
    ): SaveMessageResult = mutex.withLock {
        val db = writableDatabase

        // 1. 去重檢查：僅針對完全相同的系統通知事件 (相同的 rawKey、相同的 timestamp 與相同的內容)
        var isDuplicate = false
        if (!rawKey.isNullOrBlank()) {
            val dedupQuery = "SELECT id FROM $TABLE_MESSAGES WHERE raw_key = ? AND timestamp = ? AND content = ? LIMIT 1"
            db.rawQuery(dedupQuery, arrayOf(rawKey, timestamp.toString(), content)).use { cursor ->
                if (cursor.moveToFirst()) {
                    isDuplicate = true
                }
            }
        }

        // 2. 查詢現有聊天室狀態
        var isStackEnabled = true
        var currentUnread = 0
        var customRingtoneUri: String? = null
        var isMuted = false

        db.rawQuery(
            "SELECT is_stack_enabled, unread_count, custom_ringtone_uri, is_muted FROM $TABLE_CHATS WHERE chat_key = ?",
            arrayOf(chatKey)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                isStackEnabled = cursor.getInt(0) == 1
                currentUnread = cursor.getInt(1)
                customRingtoneUri = if (cursor.isNull(2)) null else cursor.getString(2)
                isMuted = cursor.getInt(3) == 1
            }
        }

        if (isDuplicate) {
            val existingChat = ChatConversation(
                chatKey = chatKey,
                accountId = accountId,
                title = chatTitle,
                isGroup = isGroup,
                isStackEnabled = isStackEnabled,
                lastMessageTime = timestamp,
                lastMessageContent = content,
                unreadCount = currentUnread,
                customRingtoneUri = customRingtoneUri,
                isMuted = isMuted
            )
            return@withLock SaveMessageResult(existingChat, isNewMessage = false)
        }

        // 3. 真正的新訊息：插入訊息表記錄
        val msgValues = ContentValues().apply {
            put("chat_key", chatKey)
            put("sender_name", senderName)
            put("content", content)
            put("timestamp", timestamp)
            put("is_retracted", 0)
            put("raw_key", rawKey)
        }
        db.insert(TABLE_MESSAGES, null, msgValues)

        // 4. 更新聊天室表
        val newUnread = currentUnread + 1
        val chatValues = ContentValues().apply {
            put("chat_key", chatKey)
            put("account_id", accountId)
            put("title", chatTitle)
            put("is_group", if (isGroup) 1 else 0)
            put("is_stack_enabled", if (isStackEnabled) 1 else 0)
            put("last_message_time", timestamp)
            put("last_message_content", content)
            put("unread_count", newUnread)
            put("custom_ringtone_uri", customRingtoneUri)
            put("is_muted", if (isMuted) 1 else 0)
        }
        db.insertWithOnConflict(TABLE_CHATS, null, chatValues, SQLiteDatabase.CONFLICT_REPLACE)

        refreshChatsInternal(db)

        val updatedChat = ChatConversation(
            chatKey = chatKey,
            accountId = accountId,
            title = chatTitle,
            isGroup = isGroup,
            isStackEnabled = isStackEnabled,
            lastMessageTime = timestamp,
            lastMessageContent = content,
            unreadCount = newUnread,
            customRingtoneUri = customRingtoneUri,
            isMuted = isMuted
        )
        SaveMessageResult(updatedChat, isNewMessage = true)
    }

    /**
     * 取得該聊天室最近未清除之訊息清單 (依時間遞增排序，用於 MessagingStyle)
     */
    suspend fun getRecentMessages(chatKey: String, limit: Int = 15): List<ChatMessage> = mutex.withLock {
        val list = mutableListOf<ChatMessage>()
        val db = readableDatabase

        val query = """
            SELECT id, chat_key, sender_name, content, timestamp, is_retracted, retracted_timestamp, raw_key
            FROM $TABLE_MESSAGES
            WHERE chat_key = ?
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent()

        db.rawQuery(query, arrayOf(chatKey, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    ChatMessage(
                        id = cursor.getLong(0),
                        chatKey = cursor.getString(1),
                        senderName = cursor.getString(2),
                        content = cursor.getString(3),
                        timestamp = cursor.getLong(4),
                        isRetracted = cursor.getInt(5) == 1,
                        retractedTimestamp = if (cursor.isNull(6)) null else cursor.getLong(6),
                        rawNotificationKey = cursor.getString(7)
                    )
                )
            }
        }
        list.reversed()
    }

    /**
     * 標記該聊天室最近一筆訊息為已收回 (若保留開關開啟)
     */
    suspend fun markLatestMessageRetracted(chatKey: String, senderName: String): Boolean = mutex.withLock {
        val db = writableDatabase
        // 尋找該發言人在該聊天室最近一筆尚未標記收回的訊息 ID
        val findQuery = """
            SELECT id FROM $TABLE_MESSAGES 
            WHERE chat_key = ? AND (sender_name = ? OR ? = '') AND is_retracted = 0
            ORDER BY timestamp DESC LIMIT 1
        """.trimIndent()

        var targetId: Long? = null
        db.rawQuery(findQuery, arrayOf(chatKey, senderName, senderName)).use { cursor ->
            if (cursor.moveToFirst()) {
                targetId = cursor.getLong(0)
            }
        }

        if (targetId != null) {
            val values = ContentValues().apply {
                put("is_retracted", 1)
                put("retracted_timestamp", System.currentTimeMillis())
            }
            db.update(TABLE_MESSAGES, values, "id = ?", arrayOf(targetId.toString()))
            refreshChatsInternal(db)
            return@withLock true
        }
        return@withLock false
    }

    /**
     * 刪除該聊天室最近一筆訊息 (若保留開關關閉，比照官方移除訊息)
     */
    suspend fun deleteLatestMessage(chatKey: String, senderName: String): Boolean = mutex.withLock {
        val db = writableDatabase
        val findQuery = """
            SELECT id FROM $TABLE_MESSAGES 
            WHERE chat_key = ? AND (sender_name = ? OR ? = '')
            ORDER BY timestamp DESC LIMIT 1
        """.trimIndent()

        var targetId: Long? = null
        db.rawQuery(findQuery, arrayOf(chatKey, senderName, senderName)).use { cursor ->
            if (cursor.moveToFirst()) {
                targetId = cursor.getLong(0)
            }
        }

        if (targetId != null) {
            db.delete(TABLE_MESSAGES, "id = ?", arrayOf(targetId.toString()))
            refreshChatsInternal(db)
            return@withLock true
        }
        return@withLock false
    }

    /**
     * 清除特定聊天室未讀與訊息 (已讀或手動清除時調用)
     */
    suspend fun clearChatMessages(chatKey: String) = mutex.withLock {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, "chat_key = ?", arrayOf(chatKey))
        val values = ContentValues().apply {
            put("unread_count", 0)
        }
        db.update(TABLE_CHATS, values, "chat_key = ?", arrayOf(chatKey))
        refreshChatsInternal(db)
    }

    /**
     * 切換特定聊天室是否啟用堆疊通知
     */
    suspend fun setChatStackEnabled(chatKey: String, enabled: Boolean) = mutex.withLock {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("is_stack_enabled", if (enabled) 1 else 0)
        }
        db.update(TABLE_CHATS, values, "chat_key = ?", arrayOf(chatKey))
        refreshChatsInternal(db)
    }

    /**
     * 設定特定聊天室之自訂鈴聲 URI
     */
    suspend fun setChatRingtone(chatKey: String, ringtoneUri: String?) = mutex.withLock {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("custom_ringtone_uri", ringtoneUri)
        }
        db.update(TABLE_CHATS, values, "chat_key = ?", arrayOf(chatKey))
        refreshChatsInternal(db)
    }

    /**
     * 設定特定聊天室靜音狀態
     */
    suspend fun setChatMuted(chatKey: String, isMuted: Boolean) = mutex.withLock {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("is_muted", if (isMuted) 1 else 0)
        }
        db.update(TABLE_CHATS, values, "chat_key = ?", arrayOf(chatKey))
        refreshChatsInternal(db)
    }

    /**
     * 查詢指定聊天室
     */
    suspend fun getChat(chatKey: String): ChatConversation? = mutex.withLock {
        val db = readableDatabase
        val query = """
            SELECT chat_key, account_id, title, is_group, is_stack_enabled, last_message_time, last_message_content, unread_count, custom_ringtone_uri, is_muted 
            FROM $TABLE_CHATS WHERE chat_key = ?
        """.trimIndent()

        db.rawQuery(query, arrayOf(chatKey)).use { cursor ->
            if (cursor.moveToFirst()) {
                return@withLock ChatConversation(
                    chatKey = cursor.getString(0),
                    accountId = cursor.getString(1),
                    title = cursor.getString(2),
                    isGroup = cursor.getInt(3) == 1,
                    isStackEnabled = cursor.getInt(4) == 1,
                    lastMessageTime = cursor.getLong(5),
                    lastMessageContent = cursor.getString(6),
                    unreadCount = cursor.getInt(7),
                    customRingtoneUri = if (cursor.isNull(8)) null else cursor.getString(8),
                    isMuted = cursor.getInt(9) == 1
                )
            }
        }
        return@withLock null
    }

    private fun refreshChats() {
        scope.launch {
            mutex.withLock {
                refreshChatsInternal(readableDatabase)
            }
        }
    }

    private fun refreshChatsInternal(db: SQLiteDatabase) {
        val list = mutableListOf<ChatConversation>()
        val query = """
            SELECT chat_key, account_id, title, is_group, is_stack_enabled, last_message_time, last_message_content, unread_count, custom_ringtone_uri, is_muted 
            FROM $TABLE_CHATS ORDER BY last_message_time DESC
        """.trimIndent()

        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    ChatConversation(
                        chatKey = cursor.getString(0),
                        accountId = cursor.getString(1),
                        title = cursor.getString(2),
                        isGroup = cursor.getInt(3) == 1,
                        isStackEnabled = cursor.getInt(4) == 1,
                        lastMessageTime = cursor.getLong(5),
                        lastMessageContent = cursor.getString(6),
                        unreadCount = cursor.getInt(7),
                        customRingtoneUri = if (cursor.isNull(8)) null else cursor.getString(8),
                        isMuted = cursor.getInt(9) == 1
                    )
                )
            }
        }
        _chatsFlow.value = list
    }
}
