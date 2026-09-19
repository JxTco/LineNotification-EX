package com.notistack.line.service

import java.util.concurrent.ConcurrentHashMap

/**
 * 追蹤通知列直接回覆 (Direct Reply) 狀態
 *
 * 用途：
 * 當使用者透過卡片回覆訊息後，LINE 原生會在背景發送訊息並主動調用 cancelNotification (觸發 REASON_APP_CANCEL == 8)。
 * 此追蹤器提供暫態保護窗 (8 秒)，防止該回呼誤將使用者剛剛回覆並期望保留的堆疊卡片清除。
 */
object ReplyStateTracker {

    private val replyTimestamps = ConcurrentHashMap<String, Long>()
    private const val PROTECTION_WINDOW_MS = 8000L

    fun markJustReplied(chatKey: String) {
        replyTimestamps[chatKey] = System.currentTimeMillis()
    }

    fun wasJustReplied(chatKey: String): Boolean {
        val lastTime = replyTimestamps[chatKey] ?: return false
        val elapsed = System.currentTimeMillis() - lastTime
        if (elapsed < PROTECTION_WINDOW_MS) {
            return true
        }
        replyTimestamps.remove(chatKey)
        return false
    }

    fun clear(chatKey: String) {
        replyTimestamps.remove(chatKey)
    }
}
