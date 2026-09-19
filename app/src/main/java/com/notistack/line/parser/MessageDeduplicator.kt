package com.notistack.line.parser

/**
 * 系統通知事件去重引擎
 *
 * 核心原則：
 * 僅過濾「同一個 Notification 被 Android 系統多次回調 onNotificationPosted」的冗餘事件
 * (例如：Heads-up 懸浮超時轉為常駐、通知優先級重新計算、排名更新等)。
 *
 * 比對維度：
 * notificationKey + content + postTime
 *
 * 確保：
 * 1. 相同 Key 但不同內容（新訊息）絕對不被過濾！
 * 2. 相同文字但不同 postTime（連續發送相同文字）絕對不被過濾！
 * 3. 只有完全相同的通知實體且內容時間皆相符時，才判定為系統重複回調。
 */
object MessageDeduplicator {

    private val processedEventKeys = mutableMapOf<String, Long>()
    private val lock = Any()

    /**
     * 檢查是否為同一個 Notification 的重複系統回調事件
     */
    fun isDuplicateSystemEvent(notificationKey: String, content: String, postTime: Long): Boolean {
        val trimmed = content.trim()
        val eventId = "$notificationKey|$trimmed|$postTime"
        val now = System.currentTimeMillis()

        synchronized(lock) {
            // 清理超過 30 秒的過期快取
            val iterator = processedEventKeys.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 30000L) {
                    iterator.remove()
                }
            }

            if (processedEventKeys.containsKey(eventId)) {
                return true // 相同 Key、相同內文、相同發布時間戳，判定為系統重複回調
            }

            processedEventKeys[eventId] = now
            return false
        }
    }

    fun clear() {
        synchronized(lock) {
            processedEventKeys.clear()
        }
    }
}
