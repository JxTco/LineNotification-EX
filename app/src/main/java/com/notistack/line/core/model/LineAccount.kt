package com.notistack.line.core.model

/**
 * 代表偵測到的 LINE 執行個體 (支援三星 Dual Messenger、小米雙開、Work Profile 等)
 */
data class LineAccount(
    val accountKey: String, // e.g. "jp.naver.line.android_user_0"
    val userId: Int,
    val uid: Int,
    val packageName: String,
    val displayName: String,
    val isPrimary: Boolean = (userId == 0),
    val firstSeenTimestamp: Long = System.currentTimeMillis()
)
