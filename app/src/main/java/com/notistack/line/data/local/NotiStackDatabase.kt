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

    private val _tagGroupsFlow = MutableStateFlow<List<com.notistack.line.core.model.ChatTagGroup>>(emptyList())
    val tagGroupsFlow: StateFlow<List<com.notistack.line.core.model.ChatTagGroup>> = _tagGroupsFlow.asStateFlow()

    init {
        refreshChats()
        refreshTagGroups()
    }

    companion object {
        private const val DB_NAME = "notistack.db"
        private const val DB_VERSION = 5

        private const val TABLE_CHATS = "chats"
        private const val TABLE_MESSAGES = "messages"
        private const val TABLE_TAG_GROUPS = "chat_tag_groups"
        private const val TABLE_TAG_MAPPING = "chat_tag_mapping"

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
                custom_call_ringtone_uri TEXT,
                is_muted INTEGER NOT NULL DEFAULT 0,
                keep_native_when_disabled INTEGER NOT NULL DEFAULT 1
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

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TAG_GROUPS (
                group_id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                color_hex TEXT NOT NULL DEFAULT '#4CAF50'
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TAG_MAPPING (
                chat_key TEXT NOT NULL,
                group_id INTEGER NOT NULL,
                PRIMARY KEY (chat_key, group_id),
                FOREIGN KEY (chat_key) REFERENCES $TABLE_CHATS(chat_key) ON DELETE CASCADE,
                FOREIGN KEY (group_id) REFERENCES $TABLE_TAG_GROUPS(group_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
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
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE $TABLE_CHATS ADD COLUMN keep_native_when_disabled INTEGER NOT NULL DEFAULT 1")
            } catch (e: Exception) {
                // Ignore if already added
            }
        }
        if (oldVersion < 4) {
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_TAG_GROUPS (
                        group_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        color_hex TEXT NOT NULL DEFAULT '#4CAF50'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_TAG_MAPPING (
                        chat_key TEXT NOT NULL,
                        group_id INTEGER NOT NULL,
                        PRIMARY KEY (chat_key, group_id),
                        FOREIGN KEY (chat_key) REFERENCES $TABLE_CHATS(chat_key) ON DELETE CASCADE,
                        FOREIGN KEY (group_id) REFERENCES $TABLE_TAG_GROUPS(group_id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            } catch (e: Exception) {
                // Ignore if already added
            }
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE $TABLE_CHATS ADD COLUMN custom_call_ringtone_uri TEXT")
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
        var customCallRingtoneUri: String? = null
        var isMuted = false
        var keepNativeWhenDisabled = true

        db.rawQuery(
            "SELECT is_stack_enabled, unread_count, custom_ringtone_uri, custom_call_ringtone_uri, is_muted, keep_native_when_disabled FROM $TABLE_CHATS WHERE chat_key = ?",
            arrayOf(chatKey)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                isStackEnabled = cursor.getInt(0) == 1
                currentUnread = cursor.getInt(1)
                customRingtoneUri = if (cursor.isNull(2)) null else cursor.getString(2)
                customCallRingtoneUri = if (cursor.isNull(3)) null else cursor.getString(3)
                isMuted = cursor.getInt(4) == 1
                keepNativeWhenDisabled = cursor.getInt(5) == 1
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
                customCallRingtoneUri = customCallRingtoneUri,
                isMuted = isMuted,
                keepNativeWhenDisabled = keepNativeWhenDisabled
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
            put("custom_call_ringtone_uri", customCallRingtoneUri)
            put("is_muted", if (isMuted) 1 else 0)
            put("keep_native_when_disabled", if (keepNativeWhenDisabled) 1 else 0)
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
            customCallRingtoneUri = customCallRingtoneUri,
            isMuted = isMuted,
            keepNativeWhenDisabled = keepNativeWhenDisabled
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
     * 採用兩階段智能匹配：先比對發言人，若未命中 (例如群組名稱覆蓋或字串格式差異) 則備援回退匹配該聊天室最近一筆尚未收回之訊息
     */
    suspend fun markLatestMessageRetracted(chatKey: String, senderName: String): Boolean = mutex.withLock {
        val db = writableDatabase
        var targetId: Long? = null

        // 階段 1: 優先尋找該發言人在該聊天室最近一筆尚未標記收回的訊息
        if (senderName.isNotBlank()) {
            val findQuery = """
                SELECT id FROM $TABLE_MESSAGES 
                WHERE chat_key = ? AND sender_name = ? AND is_retracted = 0
                ORDER BY timestamp DESC LIMIT 1
            """.trimIndent()
            db.rawQuery(findQuery, arrayOf(chatKey, senderName)).use { cursor ->
                if (cursor.moveToFirst()) {
                    targetId = cursor.getLong(0)
                }
            }
        }

        // 階段 2 (防禦備援): 若階段 1 未能命中，回退匹配該聊天室最近一筆尚未收回之訊息
        if (targetId == null) {
            val fallbackQuery = """
                SELECT id FROM $TABLE_MESSAGES 
                WHERE chat_key = ? AND is_retracted = 0
                ORDER BY timestamp DESC LIMIT 1
            """.trimIndent()
            db.rawQuery(fallbackQuery, arrayOf(chatKey)).use { cursor ->
                if (cursor.moveToFirst()) {
                    targetId = cursor.getLong(0)
                }
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
     * 同樣採用兩階段智能匹配
     */
    suspend fun deleteLatestMessage(chatKey: String, senderName: String): Boolean = mutex.withLock {
        val db = writableDatabase
        var targetId: Long? = null

        // 階段 1: 優先尋找該發言人在該聊天室最近一筆訊息
        if (senderName.isNotBlank()) {
            val findQuery = """
                SELECT id FROM $TABLE_MESSAGES 
                WHERE chat_key = ? AND sender_name = ?
                ORDER BY timestamp DESC LIMIT 1
            """.trimIndent()
            db.rawQuery(findQuery, arrayOf(chatKey, senderName)).use { cursor ->
                if (cursor.moveToFirst()) {
                    targetId = cursor.getLong(0)
                }
            }
        }

        // 階段 2: 備援回退匹配該聊天室最近一筆訊息
        if (targetId == null) {
            val fallbackQuery = """
                SELECT id FROM $TABLE_MESSAGES 
                WHERE chat_key = ?
                ORDER BY timestamp DESC LIMIT 1
            """.trimIndent()
            db.rawQuery(fallbackQuery, arrayOf(chatKey)).use { cursor ->
                if (cursor.moveToFirst()) {
                    targetId = cursor.getLong(0)
                }
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
     * 設定特定聊天室關閉堆疊時，是否保留 LINE 原生通知
     */
    suspend fun setChatKeepNativeWhenDisabled(chatKey: String, keep: Boolean) = mutex.withLock {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("keep_native_when_disabled", if (keep) 1 else 0)
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
            SELECT chat_key, account_id, title, is_group, is_stack_enabled, last_message_time, last_message_content, unread_count, custom_ringtone_uri, custom_call_ringtone_uri, is_muted, keep_native_when_disabled 
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
                    customCallRingtoneUri = if (cursor.isNull(9)) null else cursor.getString(9),
                    isMuted = cursor.getInt(10) == 1,
                    keepNativeWhenDisabled = cursor.getInt(11) == 1
                )
            }
        }
        return@withLock null
    }

    /**
     * 專門為來電通話設計的智慧雙重降級聊天室查詢 (Phase 4.2.3)
     *
     * 解決文字訊息聊天室使用 shortcutId (例如 user_0_sc_XXX) 儲存，而語音通話通知缺乏 shortcutId (例如 user_0_dm_小明)
     * 導致兩者 Key 不一致查不到自訂來電鈴聲的斷層問題。
     */
    suspend fun findChatForCaller(accountId: String, chatKey: String, callerName: String): ChatConversation? = mutex.withLock {
        val db = readableDatabase
        val cols = "chat_key, account_id, title, is_group, is_stack_enabled, last_message_time, last_message_content, unread_count, custom_ringtone_uri, custom_call_ringtone_uri, is_muted, keep_native_when_disabled"

        // 第 1 步: 嘗試以精準 chatKey 查詢
        val exactQuery = "SELECT $cols FROM $TABLE_CHATS WHERE chat_key = ? LIMIT 1"
        var directChat: ChatConversation? = null
        db.rawQuery(exactQuery, arrayOf(chatKey)).use { cursor ->
            if (cursor.moveToFirst()) {
                directChat = ChatConversation(
                    chatKey = cursor.getString(0),
                    accountId = cursor.getString(1),
                    title = cursor.getString(2),
                    isGroup = cursor.getInt(3) == 1,
                    isStackEnabled = cursor.getInt(4) == 1,
                    lastMessageTime = cursor.getLong(5),
                    lastMessageContent = cursor.getString(6),
                    unreadCount = cursor.getInt(7),
                    customRingtoneUri = if (cursor.isNull(8)) null else cursor.getString(8),
                    customCallRingtoneUri = if (cursor.isNull(9)) null else cursor.getString(9),
                    isMuted = cursor.getInt(10) == 1,
                    keepNativeWhenDisabled = cursor.getInt(11) == 1
                )
            }
        }

        // 若精確命中且已設定專屬來電鈴聲，直接採用
        if (directChat != null && !directChat?.customCallRingtoneUri.isNullOrBlank()) {
            return@withLock directChat
        }

        // 第 2 步 (跨 Key 降級比對): 若精確 chatKey 未找到或未設鈴聲，以 accountId + 聯絡人姓名查詢已設定鈴聲的聊天室
        val cleanName = callerName.trim()
        if (cleanName.isNotBlank()) {
            val fallbackQuery = """
                SELECT $cols FROM $TABLE_CHATS 
                WHERE account_id = ? AND (title = ? OR title LIKE ? OR ? LIKE '%' || title || '%')
                ORDER BY CASE WHEN custom_call_ringtone_uri IS NOT NULL THEN 0 ELSE 1 END, last_message_time DESC
                LIMIT 1
            """.trimIndent()

            db.rawQuery(fallbackQuery, arrayOf(accountId, cleanName, "%$cleanName%", cleanName)).use { cursor ->
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
                        customCallRingtoneUri = if (cursor.isNull(9)) null else cursor.getString(9),
                        isMuted = cursor.getInt(10) == 1,
                        keepNativeWhenDisabled = cursor.getInt(11) == 1
                    )
                }
            }
        }

        return@withLock directChat
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
            SELECT chat_key, account_id, title, is_group, is_stack_enabled, last_message_time, last_message_content, unread_count, custom_ringtone_uri, custom_call_ringtone_uri, is_muted, keep_native_when_disabled 
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
                        customCallRingtoneUri = if (cursor.isNull(9)) null else cursor.getString(9),
                        isMuted = cursor.getInt(10) == 1,
                        keepNativeWhenDisabled = cursor.getInt(11) == 1
                    )
                )
            }
        }
        _chatsFlow.value = list
    }

    // ==========================================
    // Phase 4.2.1 / 4.2.2 鈴聲多層級重設 API (Reset Ringtone APIs)
    // ==========================================

    /**
     * 設定特定聊天室來電鈴聲 (Phase 4.2.2)
     */
    suspend fun setChatCallRingtone(chatKey: String, ringtoneUri: String?) = mutex.withLock {
        val db = writableDatabase
        val values = ContentValues().apply {
            if (ringtoneUri != null) {
                put("custom_call_ringtone_uri", ringtoneUri)
            } else {
                putNull("custom_call_ringtone_uri")
            }
        }
        db.update(TABLE_CHATS, values, "chat_key = ?", arrayOf(chatKey))
        refreshChatsInternal(db)
    }

    /**
     * 重設個別聊天室來電鈴聲為預設 (custom_call_ringtone_uri = NULL) (Phase 4.2.2)
     */
    suspend fun resetChatCallRingtone(chatKey: String) = setChatCallRingtone(chatKey, null)

    /**
     * 1. 個別聊天室重設訊息鈴聲為預設 (custom_ringtone_uri = NULL)
     */
    suspend fun resetChatRingtone(chatKey: String) = mutex.withLock {
        val db = writableDatabase
        val values = ContentValues().apply {
            putNull("custom_ringtone_uri")
        }
        db.update(TABLE_CHATS, values, "chat_key = ?", arrayOf(chatKey))
        refreshChatsInternal(db)
    }

    /**
     * 2. 該帳號所有聊天室一鍵重設鈴聲為預設 (同時重設訊息與來電鈴聲)
     */
    suspend fun resetAccountChatRingtones(accountId: String, resetCallRingtone: Boolean = true) = mutex.withLock {
        val db = writableDatabase
        val values = ContentValues().apply {
            putNull("custom_ringtone_uri")
            if (resetCallRingtone) {
                putNull("custom_call_ringtone_uri")
            }
        }
        db.update(TABLE_CHATS, values, "account_id = ?", arrayOf(accountId))
        refreshChatsInternal(db)
    }

    // ==========================================
    // Phase 4.2.1 自訂標籤群組與批量設定 (Tag Groups & Batch Settings)
    // ==========================================

    /**
     * 取得所有自訂分類標籤群組 (含成員數)
     */
    suspend fun getAllTagGroups(): List<com.notistack.line.core.model.ChatTagGroup> = mutex.withLock {
        return@withLock getTagGroupsInternal(readableDatabase)
    }

    private fun getTagGroupsInternal(db: SQLiteDatabase): List<com.notistack.line.core.model.ChatTagGroup> {
        val list = mutableListOf<com.notistack.line.core.model.ChatTagGroup>()
        val sql = """
            SELECT g.group_id, g.name, g.color_hex, COUNT(m.chat_key) AS member_count
            FROM $TABLE_TAG_GROUPS g
            LEFT JOIN $TABLE_TAG_MAPPING m ON g.group_id = m.group_id
            GROUP BY g.group_id, g.name, g.color_hex
            ORDER BY g.group_id ASC
        """.trimIndent()
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    com.notistack.line.core.model.ChatTagGroup(
                        groupId = cursor.getLong(0),
                        name = cursor.getString(1),
                        colorHex = cursor.getString(2),
                        memberCount = cursor.getInt(3)
                    )
                )
            }
        }
        return list
    }

    /**
     * 建立自訂標籤群組
     */
    suspend fun createTagGroup(name: String, colorHex: String = "#4CAF50"): Long = mutex.withLock {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name.trim())
            put("color_hex", colorHex)
        }
        val id = db.insert(TABLE_TAG_GROUPS, null, values)
        refreshTagGroupsInternal(db)
        return@withLock id
    }

    /**
     * 刪除自訂標籤群組
     */
    suspend fun deleteTagGroup(groupId: Long) = mutex.withLock {
        val db = writableDatabase
        db.delete(TABLE_TAG_GROUPS, "group_id = ?", arrayOf(groupId.toString()))
        db.delete(TABLE_TAG_MAPPING, "group_id = ?", arrayOf(groupId.toString()))
        refreshTagGroupsInternal(db)
    }

    /**
     * 將多個聊天室加入指定群組
     */
    suspend fun addChatsToGroup(groupId: Long, chatKeys: List<String>) = mutex.withLock {
        val db = writableDatabase
        db.beginTransaction()
        try {
            chatKeys.forEach { chatKey ->
                val values = ContentValues().apply {
                    put("chat_key", chatKey)
                    put("group_id", groupId)
                }
                db.insertWithOnConflict(TABLE_TAG_MAPPING, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshTagGroupsInternal(db)
    }

    /**
     * 將特定聊天室自群組移除
     */
    suspend fun removeChatFromGroup(groupId: Long, chatKey: String) = mutex.withLock {
        val db = writableDatabase
        db.delete(TABLE_TAG_MAPPING, "group_id = ? AND chat_key = ?", arrayOf(groupId.toString(), chatKey))
        refreshTagGroupsInternal(db)
    }

    /**
     * 查詢指定群組內的所有 chatKey 清單
     */
    suspend fun getChatKeysInGroup(groupId: Long): List<String> = mutex.withLock {
        val list = mutableListOf<String>()
        val db = readableDatabase
        db.rawQuery("SELECT chat_key FROM $TABLE_TAG_MAPPING WHERE group_id = ?", arrayOf(groupId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0))
            }
        }
        return@withLock list
    }

    /**
     * 批量設定群組內所有聊天室的鈴聲 (若 uri == null 則重設回預設)
     */
    suspend fun batchSetGroupRingtone(groupId: Long, ringtoneUri: String?) = mutex.withLock {
        val db = writableDatabase
        val sql = if (ringtoneUri != null) {
            "UPDATE $TABLE_CHATS SET custom_ringtone_uri = ? WHERE chat_key IN (SELECT chat_key FROM $TABLE_TAG_MAPPING WHERE group_id = ?)"
        } else {
            "UPDATE $TABLE_CHATS SET custom_ringtone_uri = NULL WHERE chat_key IN (SELECT chat_key FROM $TABLE_TAG_MAPPING WHERE group_id = ?)"
        }
        val args = if (ringtoneUri != null) arrayOf(ringtoneUri, groupId.toString()) else arrayOf(groupId.toString())
        db.execSQL(sql, args)
        refreshChatsInternal(db)
    }

    /**
     * 批量設定群組內所有聊天室的來電鈴聲 (Phase 4.2.2)
     */
    suspend fun batchSetGroupCallRingtone(groupId: Long, ringtoneUri: String?) = mutex.withLock {
        val db = writableDatabase
        val sql = if (ringtoneUri != null) {
            "UPDATE $TABLE_CHATS SET custom_call_ringtone_uri = ? WHERE chat_key IN (SELECT chat_key FROM $TABLE_TAG_MAPPING WHERE group_id = ?)"
        } else {
            "UPDATE $TABLE_CHATS SET custom_call_ringtone_uri = NULL WHERE chat_key IN (SELECT chat_key FROM $TABLE_TAG_MAPPING WHERE group_id = ?)"
        }
        val args = if (ringtoneUri != null) arrayOf(ringtoneUri, groupId.toString()) else arrayOf(groupId.toString())
        db.execSQL(sql, args)
        refreshChatsInternal(db)
    }

    /**
     * 批量重設群組內所有聊天室鈴聲為預設 (訊息鈴聲與來電鈴聲)
     */
    suspend fun resetGroupRingtones(groupId: Long, resetCallRingtone: Boolean = true) = mutex.withLock {
        val db = writableDatabase
        db.execSQL("UPDATE $TABLE_CHATS SET custom_ringtone_uri = NULL WHERE chat_key IN (SELECT chat_key FROM $TABLE_TAG_MAPPING WHERE group_id = ?)", arrayOf(groupId.toString()))
        if (resetCallRingtone) {
            db.execSQL("UPDATE $TABLE_CHATS SET custom_call_ringtone_uri = NULL WHERE chat_key IN (SELECT chat_key FROM $TABLE_TAG_MAPPING WHERE group_id = ?)", arrayOf(groupId.toString()))
        }
        refreshChatsInternal(db)
    }

    /**
     * 批量設定群組內所有聊天室的靜音狀態
     */
    suspend fun batchSetGroupMuted(groupId: Long, isMuted: Boolean) = mutex.withLock {
        val db = writableDatabase
        val sql = "UPDATE $TABLE_CHATS SET is_muted = ? WHERE chat_key IN (SELECT chat_key FROM $TABLE_TAG_MAPPING WHERE group_id = ?)"
        db.execSQL(sql, arrayOf(if (isMuted) "1" else "0", groupId.toString()))
        refreshChatsInternal(db)
    }

    private fun refreshTagGroups() {
        scope.launch {
            mutex.withLock {
                refreshTagGroupsInternal(readableDatabase)
            }
        }
    }

    private fun refreshTagGroupsInternal(db: SQLiteDatabase) {
        _tagGroupsFlow.value = getTagGroupsInternal(db)
    }
}
