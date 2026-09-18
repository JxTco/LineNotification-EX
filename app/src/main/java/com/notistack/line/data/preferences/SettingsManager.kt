package com.notistack.line.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NotificationMode {
    MODE_A_KEEP_NATIVE, // 保留 LINE 原生通知 + 顯示自訂堆疊
    MODE_B_HIDE_NATIVE  // 隱藏 LINE 原生通知 + 僅顯示自訂堆疊
}

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _notificationMode = MutableStateFlow(loadNotificationMode())
    val notificationMode: StateFlow<NotificationMode> = _notificationMode.asStateFlow()

    private val _isGlobalStackEnabled = MutableStateFlow(loadGlobalStackEnabled())
    val isGlobalStackEnabled: StateFlow<Boolean> = _isGlobalStackEnabled.asStateFlow()

    companion object {
        private const val PREFS_NAME = "notistack_settings"
        private const val KEY_MODE = "key_notification_mode"
        private const val KEY_GLOBAL_STACK = "key_global_stack_enabled"

        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun loadNotificationMode(): NotificationMode {
        val name = prefs.getString(KEY_MODE, NotificationMode.MODE_B_HIDE_NATIVE.name)
        return try {
            NotificationMode.valueOf(name ?: NotificationMode.MODE_B_HIDE_NATIVE.name)
        } catch (e: Exception) {
            NotificationMode.MODE_B_HIDE_NATIVE
        }
    }

    private fun loadGlobalStackEnabled(): Boolean {
        return prefs.getBoolean(KEY_GLOBAL_STACK, true)
    }

    fun setNotificationMode(mode: NotificationMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _notificationMode.value = mode
    }

    fun setGlobalStackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLOBAL_STACK, enabled).apply()
        _isGlobalStackEnabled.value = enabled
    }
}
