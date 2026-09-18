package com.notistack.line.parser

/**
 * 系統通知事件去重引擎
 *
 * 核心原則：
 * 僅過濾「同一個 Notification 被 Android 系統多次回調 onNotificationPosted」的冗餘事件
 * (例如：Heads-up 懸浮超時轉為常駐、通知優先級重新計算、排名更新等)。
 *
 * 絕不以「文字內容相同」過濾！
 * 若使用者真的在短時間內連續發送兩次相同文字 (如「吃飯了嗎？」、「吃飯了嗎？」)，
 * 由於每次發送都會產生新的 postTime 或獨立的通知實體，本去重引擎保證兩則都會被正常保留！
 */
object MessageDeduplicator {

    private val processedEventKeys = mutableMapOf<String, Long>()
    private val lock = Any()

    /**
     * 檢查是否為同一個 Notification 的重複系統回調事件
     * 依據：sbn.key (系統唯一通知鍵) + sbn.postTime (通知發布時間戳)
     */
    fun isDuplicateSystemEvent(notificationKey: String, postTime: Long): Boolean {
        val eventId = "$notificationKey|$postTime"
        val now = System.currentTimeMillis()

        synchronized(lock) {
            // 清理超過 60 秒的過期快取
            val iterator = processedEventKeys.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 60000L) {
                    iterator.remove()
                }
            }

            if (processedEventKeys.containsKey(eventId)) {
                return true // 完全相同的通知 Key 與發布時間戳，確定為系統重推事件
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
