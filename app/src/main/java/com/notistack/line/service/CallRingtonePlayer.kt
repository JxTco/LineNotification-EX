package com.notistack.line.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * 專屬來電鈴聲持續循環播放引擎 (Phase 4.2.3)
 *
 * 徹底解決 Android 通知頻道僅發出單次短音 (One-shot) 無法持續響鈴的問題。
 * 藉由主動請求音訊焦點 (Audio Focus) 壓制系統與其他背景聲音，並以 MediaPlayer 循環播放專屬來電音樂。
 */
object CallRingtonePlayer {

    private const val TAG = "CallRingtonePlayer"

    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var isPlaying = false

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

        currentChatKey = chatKey
        val appContext = context.applicationContext
        val soundUri = try {
            Uri.parse(ringtoneUriStr)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid ringtone URI: $ringtoneUriStr", e)
            return
        }

        requestAudioFocus(appContext)

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

        // 方案 2: 若 MediaPlayer 失敗 (例如某些受保護的系統內容 URI)，回退使用 RingtoneManager
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
    }

    /**
     * 停止來電鈴聲播放並釋放音訊焦點與資源
     */
    @Synchronized
    fun stopRingtone(context: Context? = null) {
        if (!isPlaying && mediaPlayer == null && ringtone == null) {
            return
        }

        Log.i(TAG, "Stopping call ringtone for chat: $currentChatKey")

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

        context?.let { abandonAudioFocus(it.applicationContext) }

        isPlaying = false
        currentChatKey = null
    }

    fun isPlaying(): Boolean = isPlaying

    fun getCurrentChatKey(): String? = currentChatKey

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
                    .setOnAudioFocusChangeListener { /* No-op */ }
                    .build()

                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
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
