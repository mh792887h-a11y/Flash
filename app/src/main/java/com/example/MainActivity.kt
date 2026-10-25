package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var btnFlashToggle: FrameLayout
    private lateinit var ivFlashIcon: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvError: TextView

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            tvError.visibility = View.GONE
            requestNotificationPermissionAndToggle()
        } else {
            tvError.text = getString(R.string.permission_camera_required)
            tvError.visibility = View.VISIBLE
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Start service now that camera is granted
        FlashService.startService(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FlashManager.init(this)

        btnFlashToggle = findViewById(R.id.btn_flash_toggle)
        ivFlashIcon = findViewById(R.id.iv_flash_icon)
        tvTitle = findViewById(R.id.tv_title)
        tvError = findViewById(R.id.tv_error)

        btnFlashToggle.setOnClickListener {
            handleToggleClick()
        }

        observeState()
    }

    private fun handleToggleClick() {
        if (FlashManager.isFlashActive.value) {
            // Turning OFF: instantly stop without asking any permissions
            FlashService.stopService(this)
            return
        }

        // Turning ON: check Camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            requestNotificationPermissionAndToggle()
        }
    }

    private fun requestNotificationPermissionAndToggle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        FlashService.startService(this)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    FlashManager.isFlashActive.collectLatest { isActive ->
                        updateButtonUi(isActive)
                    }
                }
                launch {
                    FlashManager.lastError.collectLatest { errorMsg ->
                        if (!errorMsg.isNullOrBlank()) {
                            tvError.text = errorMsg
                            tvError.visibility = View.VISIBLE
                        } else {
                            tvError.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun updateButtonUi(isActive: Boolean) {
        if (isActive) {
            btnFlashToggle.setBackgroundResource(R.drawable.bg_button_on)
            ivFlashIcon.setImageResource(R.drawable.ic_flashlight_on)
            animateButton(1.06f)
        } else {
            btnFlashToggle.setBackgroundResource(R.drawable.bg_button_off)
            ivFlashIcon.setImageResource(R.drawable.ic_flashlight_off)
            animateButton(1.0f)
        }
    }

    private fun animateButton(scale: Float) {
        try {
            btnFlashToggle.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(2.0f))
                .start()
        } catch (e: Exception) {
            // Safe fallback
        }
    }
}
