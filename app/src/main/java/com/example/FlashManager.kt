package com.example

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FlashManager {
    private const val TAG = "FlashManager"
    private const val WAKE_LOCK_TAG = "FlashNote4:TorchWakeLock"

    private val _isFlashActive = MutableStateFlow(false)
    val isFlashActive: StateFlow<Boolean> = _isFlashActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeCameraId: String? = null
    private var torchCallbackRegistered = false

    // List of the 12 funny audio clip resources
    private val funnyAudioResources = intArrayOf(
        R.raw.funny_sound_1,
        R.raw.funny_sound_2,
        R.raw.funny_sound_3,
        R.raw.funny_sound_4,
        R.raw.funny_sound_5,
        R.raw.funny_sound_6,
        R.raw.funny_sound_7,
        R.raw.funny_sound_8,
        R.raw.funny_sound_9,
        R.raw.funny_sound_10,
        R.raw.funny_sound_11,
        R.raw.funny_sound_12
    )

    private var currentSoundIndex = 0

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            super.onTorchModeChanged(cameraId, enabled)
            if (activeCameraId == null || cameraId == activeCameraId) {
                if (!enabled && _isFlashActive.value) {
                    // Flash was disabled externally, sync state
                    Log.d(TAG, "Torch disabled externally on camera $cameraId")
                }
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            super.onTorchModeUnavailable(cameraId)
            if (activeCameraId == null || cameraId == activeCameraId) {
                Log.w(TAG, "Torch became unavailable on camera $cameraId")
            }
        }
    }

    /**
     * Initializes CameraManager listener and detects suitable camera with flash
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        if (!torchCallbackRegistered) {
            try {
                cameraManager.registerTorchCallback(torchCallback, null)
                torchCallbackRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register torch callback: ${e.message}")
            }
        }

        if (activeCameraId == null) {
            activeCameraId = findCameraWithFlash(cameraManager)
        }
    }

    private fun findCameraWithFlash(cameraManager: CameraManager): String? {
        try {
            var fallbackId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)

                if (hasFlash) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        return id
                    }
                    if (fallbackId == null) {
                        fallbackId = id
                    }
                }
            }
            return fallbackId ?: (if (cameraManager.cameraIdList.isNotEmpty()) cameraManager.cameraIdList[0] else null)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding camera with flash: ${e.message}")
            return null
        }
    }

    /**
     * Turns on the camera flash and starts looping the funny sounds at max volume
     */
    @Synchronized
    fun startFlash(context: Context): Boolean {
        if (_isFlashActive.value) return true

        val appContext = context.applicationContext
        init(appContext)

        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            _lastError.value = "CameraManager not available"
            return false
        }

        val targetId = activeCameraId ?: findCameraWithFlash(cameraManager)
        if (targetId == null) {
            _lastError.value = appContext.getString(R.string.error_no_camera_flash)
            return false
        }

        try {
            // Turn on camera torch
            cameraManager.setTorchMode(targetId, true)
            activeCameraId = targetId

            // Acquire wake lock to keep audio and torch active on screen lock
            acquireWakeLock(appContext)

            // Maximize volume and start audio playback
            maximizeAudioVolume(appContext)
            startAudioLoop(appContext)

            // Trigger haptic vibration
            vibrate(appContext)

            _isFlashActive.value = true
            _lastError.value = null
            return true
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException while turning on torch: ${e.message}")
            _lastError.value = "تعذر تشغيل الفلاش: الكاميرا قيد الاستخدام أو غير متاحة"
            stopFlash(appContext)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception while turning on torch: ${e.message}")
            _lastError.value = "حدث خطأ أثناء تشغيل الفلاش: ${e.localizedMessage}"
            stopFlash(appContext)
            return false
        }
    }

    /**
     * Turns off camera flash and stops audio playback completely
     */
    @Synchronized
    fun stopFlash(context: Context) {
        val appContext = context.applicationContext
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

        // Stop torch
        try {
            activeCameraId?.let { id ->
                cameraManager?.setTorchMode(id, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping torch: ${e.message}")
        }

        // Stop and release audio
        stopAudio()

        // Release wake lock
        releaseWakeLock()

        // Trigger haptic vibration
        vibrate(appContext)

        _isFlashActive.value = false
    }

    /**
     * Toggles the flash state
     */
    @Synchronized
    fun toggleFlash(context: Context): Boolean {
        return if (_isFlashActive.value) {
            stopFlash(context)
            false
        } else {
            startFlash(context)
        }
    }

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours safety timeout
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        }
    }

    private fun maximizeAudioVolume(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set max system volume: ${e.message}")
        }
    }

    private fun startAudioLoop(context: Context) {
        stopAudio()
        currentSoundIndex = 0
        playNextTrack(context)
    }

    private fun playNextTrack(context: Context) {
        if (!_isFlashActive.value && mediaPlayer == null && currentSoundIndex != 0) {
            return
        }

        try {
            val soundResId = funnyAudioResources[currentSoundIndex % funnyAudioResources.size]
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                val afd = context.resources.openRawResourceFd(soundResId)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()

                setVolume(1.0f, 1.0f)
                prepare()
                setOnCompletionListener {
                    // When clip finishes, advance to next sound in the list and keep looping continuously
                    currentSoundIndex = (currentSoundIndex + 1) % funnyAudioResources.size
                    it.reset()
                    it.release()
                    mediaPlayer = null
                    if (_isFlashActive.value) {
                        playNextTrack(context)
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    true
                }
                start()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing/playing audio: ${e.message}")
            // Fallback: try raw/funny_sound.m4a if exists
            tryFallbackAudio(context)
        }
    }

    private fun tryFallbackAudio(context: Context) {
        try {
            val player = MediaPlayer.create(context, R.raw.funny_sound)
            if (player != null) {
                player.isLooping = true
                player.setVolume(1.0f, 1.0f)
                player.start()
                mediaPlayer = player
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback audio failed: ${e.message}")
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
        } finally {
            mediaPlayer = null
        }
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate error: ${e.message}")
        }
    }
}
