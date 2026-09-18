package com.notistack.line.parser

/**
 * 訊息去重引擎：在時間窗內過濾重複觸發的 Android Notification 事件
 * 解決同一則 LINE 訊息因 Group Summary、Notification Update、Heads-up 更新等產生的多次重複通知
 */
object MessageDeduplicator {

    private val recentFingerprints = mutableMapOf<String, Long>()
    private val lock = Any()

    // 8 秒內相同發送者、相同聊天室、相同內容的通知視為重複事件
    private const val DEDUP_WINDOW_MS = 8000L

    fun isDuplicate(
        accountId: String,
        chatKey: String,
        senderName: String,
        content: String
    ): Boolean {
        val trimmed = content.trim()
        val fingerprint = "$accountId|$chatKey|$senderName|$trimmed"
        val now = System.currentTimeMillis()

        synchronized(lock) {
            // 清理超過 30 秒的過期快取
            val iterator = recentFingerprints.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 30000L) {
                    iterator.remove()
                }
            }

            val lastSeen = recentFingerprints[fingerprint]
            if (lastSeen != null && (now - lastSeen < DEDUP_WINDOW_MS)) {
                return true
            }

            recentFingerprints[fingerprint] = now
            return false
        }
    }

    fun clear() {
        synchronized(lock) {
            recentFingerprints.clear()
        }
    }
}
