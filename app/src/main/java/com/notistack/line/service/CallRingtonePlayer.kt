package com.notistack.line.service

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.roundToInt

/**
 * 專屬來電鈴聲持續循環播放引擎 (Phase 4.2.4 - 雙鈴聲消除強化版)
 *
 * 核心功能：
 * 1. 系統感知：精確識別靜音/震動/響鈴模式，靜音不響、震動僅震、響鈴發聲。
 * 2. 雙向音量鏡像：將 STREAM_RING 鈴聲音量等比鏡像至 STREAM_MUSIC 媒體通道。
 * 3. 底層靜音壓制：在具備勿擾權限 (ACCESS_NOTIFICATION_POLICY) 時，臨時將 STREAM_RING 設為 0，
 *    徹底靜音 LINE 原廠來電鈴聲，由本服務以 STREAM_MUSIC 獨立循環播放專屬音樂。
 * 4. 完美雙向還原：通話結束/拒接/超時後，100% 還原 STREAM_RING 與 STREAM_MUSIC 原有音量。
 * 5. 安全看門狗與崩潰保護：60 秒超時自動還原 + 本地持久化記錄，重啟自動偵測並還原殘留音量。
 */
object CallRingtonePlayer {

    private const val TAG = "CallRingtonePlayer"
    private const val PREF_NAME = "call_ringtone_recovery"
    private const val KEY_SAVED_RING_VOL = "saved_ring_vol"
    private const val KEY_SAVED_MUSIC_VOL = "saved_music_vol"
    private const val KEY_IS_RING_MUTED = "is_ring_muted"
    private const val WATCHDOG_TIMEOUT_MS = 60_000L // 60 秒來電最大超時

    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var currentChatKey: String? = null

    @Volatile
    private var savedRingVolume: Int? = null

    @Volatile
    private var savedMusicVolume: Int? = null

    @Volatile
    private var isRingStreamMuted = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        Log.w(TAG, "Watchdog timeout triggered (60s). Forcing stop and volume restoration.")
        stopRingtoneInternal(null)
    }

    /**
     * 開始循環播放指定聊天室的專屬來電鈴聲
     */
    @Synchronized
    fun startRingtone(context: Context, chatKey: String, ringtoneUriStr: String) {
        if (isPlaying && currentChatKey == chatKey) {
            Log.d(TAG, "Ringtone is already playing for chat: $chatKey")
            return
        }

        stopRingtone(context)

        val appContext = context.applicationContext
        currentChatKey = chatKey
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (audioManager == null) {
            Log.e(TAG, "AudioManager not available")
            return
        }

        // 1. 環境感知：檢查目前裝置的情境模式 (靜音 / 震動 / 響鈴)
        val ringerMode = audioManager.ringerMode
        Log.d(TAG, "Device ringer mode: $ringerMode")

        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.i(TAG, "Device is in SILENT mode. Respecting user intent: no sound or vibration.")
            isPlaying = true
            startWatchdog(appContext)
            return
        }

        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            Log.i(TAG, "Device is in VIBRATE mode. Triggering call vibration only.")
            isPlaying = true
            startVibration(appContext)
            startWatchdog(appContext)
            return
        }

        // 2. 響鈴模式：檢查當前鈴聲音量
        val currRingVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        if (currRingVol == 0) {
            Log.i(TAG, "STREAM_RING volume is 0. Triggering call vibration only.")
            isPlaying = true
            startVibration(appContext)
            startWatchdog(appContext)
            return
        }

        // 3. 計算鏡像音量 (將鈴聲音量等比例映射至媒體通道)
        val maxRingVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
        val origMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetMusicVol = ((currRingVol.toFloat() / maxRingVol) * maxMusicVol).roundToInt().coerceIn(1, maxMusicVol)

        savedRingVolume = currRingVol
        savedMusicVolume = origMusicVol
        persistVolumeState(appContext, currRingVol, origMusicVol, false)

        // 4. 底層音效壓制：若有勿擾存取權限，將 STREAM_RING 臨時設為 0 以消音 LINE 原廠鈴聲
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val hasDndPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm?.isNotificationPolicyAccessGranted == true
        } else {
            true
        }

        if (hasDndPermission) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                isRingStreamMuted = true
                persistVolumeState(appContext, currRingVol, origMusicVol, true)
                Log.i(TAG, "STREAM_RING muted to 0 to eliminate LINE native ringtone (orig: $currRingVol)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mute STREAM_RING (SecurityException or DND policy): ${e.message}")
            }
        } else {
            Log.w(TAG, "DND policy access not granted; unable to mute STREAM_RING directly.")
        }

        // 5. 設定媒體音量為鏡像音量
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusicVol, 0)
            Log.i(TAG, "STREAM_MUSIC set to mirrored volume $targetMusicVol (orig: $origMusicVol)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set STREAM_MUSIC volume: ${e.message}")
        }

        val soundUri = try {
            Uri.parse(ringtoneUriStr)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid ringtone URI: $ringtoneUriStr", e)
            restoreVolumes(appContext)
            return
        }

        requestAudioFocus(appContext)
        startVibration(appContext)

        // 6. 播放器啟動：優先採用 MediaPlayer 走 USAGE_MEDIA 通道播放
        var playerStarted = false
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(appContext, soundUri)
                isLooping = true
                prepare()
                start()
            }
            playerStarted = true
            isPlaying = true
            Log.i(TAG, "Started looping custom call ringtone on STREAM_MUSIC for chat: $chatKey")
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed on USAGE_MEDIA, falling back to RingtoneManager: ${e.message}")
            mediaPlayer?.release()
            mediaPlayer = null
        }

        // 備用方案：若 MediaPlayer 失敗，回退使用 RingtoneManager
        if (!playerStarted) {
            try {
                ringtone = RingtoneManager.getRingtone(appContext, soundUri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                    this.audioAttributes = audioAttributes
                    play()
                }
                isPlaying = true
                Log.i(TAG, "Started looping call ringtone via RingtoneManager fallback for chat: $chatKey")
            } catch (e: Exception) {
                Log.e(TAG, "RingtoneManager fallback also failed", e)
                restoreVolumes(appContext)
            }
        }

        startWatchdog(appContext)
    }

    /**
     * 停止來電鈴聲播放並完整還原音量、震動與音訊焦點
     */
    @Synchronized
    fun stopRingtone(context: Context? = null) {
        stopRingtoneInternal(context)
    }

    private fun stopRingtoneInternal(context: Context?) {
        cancelWatchdog()

        if (!isPlaying && mediaPlayer == null && ringtone == null && !isRingStreamMuted) {
            return
        }

        Log.i(TAG, "Stopping call ringtone and restoring audio state for chat: $currentChatKey")

        // 停止音樂播放器
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }

        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Ringtone", e)
        } finally {
            ringtone = null
        }

        // 停止震動
        stopVibration()

        // 還原音量
        context?.let {
            restoreVolumes(it.applicationContext)
            abandonAudioFocus(it.applicationContext)
        }

        isPlaying = false
        currentChatKey = null
    }

    fun isPlaying(): Boolean = isPlaying

    fun getCurrentChatKey(): String? = currentChatKey

    /**
     * 雙向音量精準還原
     */
    private fun restoreVolumes(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            // 還原 STREAM_RING
            if (isRingStreamMuted && savedRingVolume != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume!!, 0)
                Log.i(TAG, "STREAM_RING successfully restored to $savedRingVolume")
            }

            // 還原 STREAM_MUSIC
            if (savedMusicVolume != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume!!, 0)
                Log.i(TAG, "STREAM_MUSIC successfully restored to $savedMusicVolume")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore stream volumes", e)
        } finally {
            isRingStreamMuted = false
            savedRingVolume = null
            savedMusicVolume = null
            clearVolumeState(context)
        }
    }

    /**
     * 應急自動還原 (供服務重啟或崩潰復原呼叫)
     */
    fun emergencyRestoreVolumes(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val wasMuted = prefs.getBoolean(KEY_IS_RING_MUTED, false)
        val ringVol = if (prefs.contains(KEY_SAVED_RING_VOL)) prefs.getInt(KEY_SAVED_RING_VOL, -1) else -1
        val musicVol = if (prefs.contains(KEY_SAVED_MUSIC_VOL)) prefs.getInt(KEY_SAVED_MUSIC_VOL, -1) else -1

        if (wasMuted || ringVol != -1 || musicVol != -1) {
            Log.w(TAG, "Found un-restored audio volumes from previous session. Restoring now: ring=$ringVol, music=$musicVol")
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                try {
                    if (ringVol > 0) {
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, ringVol, 0)
                    }
                    if (musicVol >= 0) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicVol, 0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply emergency volume restoration", e)
                }
            }
            clearVolumeState(appContext)
        }
    }

    private fun persistVolumeState(context: Context, ringVol: Int, musicVol: Int, isMuted: Boolean) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SAVED_RING_VOL, ringVol)
                .putInt(KEY_SAVED_MUSIC_VOL, musicVol)
                .putBoolean(KEY_IS_RING_MUTED, isMuted)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist volume state", e)
        }
    }

    private fun clearVolumeState(context: Context) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear volume state pref", e)
        }
    }

    private fun startWatchdog(context: Context) {
        cancelWatchdog()
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private fun startVibration(context: Context) {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let { v ->
                if (v.hasVibrator()) {
                    val pattern = longArrayOf(0, 1000, 1000)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val effect = VibrationEffect.createWaveform(pattern, 0)
                        v.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(pattern, 0)
                    }
                    Log.d(TAG, "Call vibration started")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start call vibration", e)
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            Log.d(TAG, "Call vibration stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop vibration", e)
        } finally {
            vibrator = null
        }
    }

    private fun requestAudioFocus(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* No-op */ }
                    .build()

                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus", e)
        }
    }

    private fun abandonAudioFocus(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus", e)
        }
    }
}
