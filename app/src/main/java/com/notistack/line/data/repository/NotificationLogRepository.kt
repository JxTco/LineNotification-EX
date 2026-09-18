package com.notistack.line.data.repository

import com.notistack.line.core.model.CapturedNotification
import com.notistack.line.core.model.LineAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 記憶體即時通知日誌與帳號倉儲 (Phase 1 用於即時顯示與實機驗證)
 */
object NotificationLogRepository {

    private const val MAX_LOGS = 150

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _logs = MutableStateFlow<List<CapturedNotification>>(emptyList())
    val logs: StateFlow<List<CapturedNotification>> = _logs.asStateFlow()

    private val _detectedAccounts = MutableStateFlow<Map<String, LineAccount>>(emptyMap())
    val detectedAccounts: StateFlow<Map<String, LineAccount>> = _detectedAccounts.asStateFlow()

    private val logList = CopyOnWriteArrayList<CapturedNotification>()

    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
    }

    fun addNotification(notification: CapturedNotification) {
        // 如果是 LINE 帳號，記錄到偵測到的帳號清單
        if (notification.isLineApp) {
            val accountKey = "${notification.packageName}_user_${notification.userId}"
            val existing = _detectedAccounts.value
            if (!existing.containsKey(accountKey)) {
                val displayName = if (notification.userId == 0) "LINE 主帳號" else "LINE 分身帳號 (${notification.userId})"
                val account = LineAccount(
                    accountKey = accountKey,
                    userId = notification.userId,
                    uid = notification.uid,
                    packageName = notification.packageName,
                    displayName = displayName
                )
                _detectedAccounts.value = existing + (accountKey to account)
            }
        }

        // 新增日誌 (最新放最前面)
        logList.add(0, notification)
        while (logList.size > MAX_LOGS) {
            logList.removeAt(logList.size - 1)
        }
        _logs.value = logList.toList()
    }

    fun clearLogs() {
        logList.clear()
        _logs.value = emptyList()
    }
}
