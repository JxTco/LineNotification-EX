package com.notistack.line.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notistack.line.data.repository.NotificationLogRepository
import com.notistack.line.parser.NotificationParser

class LineNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "LineNotifyService"
        var instance: LineNotificationListenerService? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NotificationLogRepository.setServiceConnected(true)
        Log.i(TAG, "NotificationListenerService connected successfully.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        NotificationLogRepository.setServiceConnected(false)
        Log.w(TAG, "NotificationListenerService disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val captured = NotificationParser.parsePosted(sbn)
        // 在 Phase 1 優先記錄目標 App (LINE 或本 App 測試通知)
        if (captured.isLineApp) {
            Log.d(TAG, "Line Notification Posted: [User ${captured.userId}] ${captured.title} - ${captured.text}")
            NotificationLogRepository.addNotification(captured)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return

        val captured = NotificationParser.parseRemoved(sbn, reason)
        if (captured.isLineApp) {
            val reasonDesc = NotificationParser.getRemovalReasonDescription(reason)
            Log.d(TAG, "Line Notification Removed: [Reason $reasonDesc] ${captured.title}")
            NotificationLogRepository.addNotification(captured)
        }
    }
}
