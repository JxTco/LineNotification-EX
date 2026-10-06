package com.notistack.line.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 專屬來電鈴聲持續循環播放引擎 (Phase 4.2.3 & Phase 4.2.6)
 *
 * 核心升級 (Phase 4.2.6):
 * 1. AudioFocus 焦點遺失與通話模式偵測：
 *    - 接聽電話時，LINE 或系統索取音訊焦點或切換至 MODE_IN_COMMUNICATION，即刻自動停止鈴聲與震動。
 * 2. MediaSession + VolumeProvider 硬體音量鍵安全綁定：
 *    - 響鈴時自動啟用 MediaSession，硬體音量鍵 (Volume Down / Up) 直接由 VolumeProvider 接管。
 *    - 音量鍵減可漸進降低音量或觸發完全靜音；音量鍵加可調大音量。
 *    - 完全無需竄改系統全域 STREAM_RING，絕不造成手機靜音後遺症。
 * 3. 獨立 Vibrator 震動循環與一鍵靜音：
 *    - 整合系統 Vibrator 循環震動，當靜音或停鈴時，即刻調用 vibrator.cancel()，杜絕接聽後持續震動。
 */
object CallRingtonePlayer {

    private const val TAG = "CallRingtonePlayer"

    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSession? = null
    private var vibrator: Vibrator? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var modeListener: Any? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var isMuted = false

    @Volatile
    private var currentVolumePercent = 100

    @Volatile
    private var currentChatKey: String? = null

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
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // 安全防護：若目前已在通話模式中，絕不觸發響鈴
        if (audioManager != null) {
            val mode = audioManager.mode
            if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                Log.w(TAG, "Device is already in communication mode ($mode), skipping ringtone start.")
                return
            }
        }

        currentChatKey = chatKey
        isMuted = false
        currentVolumePercent = 100

        val soundUri = try {
            Uri.parse(ringtoneUriStr)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid ringtone URI: $ringtoneUriStr", e)
            return
        }

        requestAudioFocus(appContext)

        // 啟動獨立循環震動
        startVibration(appContext)

        // 方案 1: 優先採用 MediaPlayer 進行無縫循環播放 (Looping)
        var playerStarted = false
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(appContext, soundUri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            playerStarted = true
            isPlaying = true
            Log.i(TAG, "Started looping call ringtone via MediaPlayer for chat: $chatKey")
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed to play ringtone, falling back to RingtoneManager: ${e.message}")
            mediaPlayer?.release()
            mediaPlayer = null
        }

        // 方案 2: 若 MediaPlayer 失敗 (例如受保護之系統內容 URI)，回退使用 RingtoneManager
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
                Log.i(TAG, "Started looping call ringtone via RingtoneManager for chat: $chatKey")
            } catch (e: Exception) {
                Log.e(TAG, "RingtoneManager also failed to play ringtone", e)
            }
        }

        // 啟用 MediaSession + VolumeProvider 安全攔截硬體音量鍵
        setupMediaSession(appContext)

        // 監聽通話模式切換 (Android 12+)
        setupAudioModeListener(appContext)

        // 註冊系統音量變化備援廣播
        setupVolumeReceiver(appContext)
    }

    /**
     * 調整自訂來電鈴聲音量 (響應硬體音量鍵上下按壓)
     */
    @Synchronized
    fun adjustVolume(direction: Int, context: Context? = null) {
        if (!isPlaying) return

        if (direction < 0) {
            // 音量減按鍵
            currentVolumePercent = (currentVolumePercent - 25).coerceAtLeast(0)
            Log.d(TAG, "Hardware volume DOWN: new volume is $currentVolumePercent%")
            if (currentVolumePercent == 0) {
                muteRingtone(context)
            } else {
                applyVolume(currentVolumePercent / 100f)
            }
        } else if (direction > 0) {
            // 音量加按鍵
            isMuted = false
            currentVolumePercent = (currentVolumePercent + 25).coerceAtMost(100)
            Log.d(TAG, "Hardware volume UP: new volume is $currentVolumePercent%")
            applyVolume(currentVolumePercent / 100f)
        }
    }

    /**
     * 一鍵靜音 (停止震動並將音量設為 0，通話保持等待接聽狀態)
     */
    @Synchronized
    fun muteRingtone(context: Context? = null) {
        if (!isPlaying) return
        isMuted = true
        currentVolumePercent = 0
        Log.i(TAG, "Muting call ringtone and stopping vibration for chat: $currentChatKey")
        applyVolume(0f)
        stopVibration()
    }

    private fun applyVolume(vol: Float) {
        try {
            mediaPlayer?.setVolume(vol, vol)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting MediaPlayer volume", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ringtone?.volume = vol
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * 停止來電鈴聲播放並徹底釋放焦點、震動與資源
     */
    @Synchronized
    fun stopRingtone(context: Context? = null) {
        if (!isPlaying && mediaPlayer == null && ringtone == null && vibrator == null) {
            return
        }

        val targetChatKey = currentChatKey
        Log.i(TAG, "Stopping call ringtone for chat: $targetChatKey")

        stopVibration()

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

        releaseMediaSession()
        releaseAudioModeListener(context)
        releaseVolumeReceiver(context)

        context?.let { abandonAudioFocus(it.applicationContext) }

        isPlaying = false
        isMuted = false
        currentVolumePercent = 100
        currentChatKey = null
    }

    fun isPlaying(): Boolean = isPlaying

    fun isMuted(): Boolean = isMuted

    fun getCurrentChatKey(): String? = currentChatKey

    private fun startVibration(context: Context) {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                vibrator?.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start vibrator", e)
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling vibrator", e)
        } finally {
            vibrator = null
        }
    }

    private fun setupMediaSession(context: Context) {
        try {
            mediaSession = MediaSession(context, "LineCallRingtoneSession").apply {
                val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, currentVolumePercent) {
                    override fun onAdjustVolume(direction: Int) {
                        Log.d(TAG, "VolumeProvider onAdjustVolume: direction=$direction")
                        adjustVolume(direction, context)
                    }
                }
                setPlaybackToRemote(volumeProvider)
                isActive = true
            }
            Log.d(TAG, "MediaSession activated with VolumeProvider for hardware volume key routing.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup MediaSession", e)
        }
    }

    private fun releaseMediaSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaSession", e)
        } finally {
            mediaSession = null
        }
    }

    private fun setupAudioModeListener(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val listener = AudioManager.OnModeChangedListener { newMode ->
                Log.i(TAG, "AudioManager mode changed to: $newMode")
                if (newMode == AudioManager.MODE_IN_COMMUNICATION || newMode == AudioManager.MODE_IN_CALL) {
                    Log.i(TAG, "VoIP / Phone call connected, stopping ringtone immediately.")
                    val chatKeyToCancel = currentChatKey
                    stopRingtone(context)
                    if (!chatKeyToCancel.isNullOrBlank()) {
                        NotificationDispatcher.getInstance(context).cancelCallNotification(chatKeyToCancel)
                    }
                }
            }
            modeListener = listener
            try {
                audioManager.addOnModeChangedListener(context.mainExecutor, listener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register OnModeChangedListener", e)
            }
        }
    }

    private fun releaseAudioModeListener(context: Context?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener != null) {
            val audioManager = context?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            try {
                audioManager?.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing OnModeChangedListener", e)
            } finally {
                modeListener = null
            }
        }
    }

    private fun setupVolumeReceiver(context: Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                        if (isPlaying && !isMuted) {
                            Log.d(TAG, "VOLUME_CHANGED_ACTION detected during ringing.")
                        }
                    }
                }
            }
            volumeReceiver = receiver
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register volume receiver", e)
        }
    }

    private fun releaseVolumeReceiver(context: Context?) {
        try {
            volumeReceiver?.let {
                context?.applicationContext?.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering volume receiver", e)
        } finally {
            volumeReceiver = null
        }
    }

    private fun requestAudioFocus(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.i(TAG, "AudioFocus changed: $focusChange")
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                        ) {
                            Log.i(TAG, "Audio focus lost during call ringing, stopping ringtone immediately.")
                            val chatKeyToCancel = currentChatKey
                            stopRingtone(context)
                            if (!chatKeyToCancel.isNullOrBlank()) {
                                NotificationDispatcher.getInstance(context).cancelCallNotification(chatKeyToCancel)
                            }
                        }
                    }
                    .build()

                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            val chatKeyToCancel = currentChatKey
                            stopRingtone(context)
                            if (!chatKeyToCancel.isNullOrBlank()) {
                                NotificationDispatcher.getInstance(context).cancelCallNotification(chatKeyToCancel)
                            }
                        }
                    },
                    AudioManager.STREAM_RING,
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
