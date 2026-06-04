package com.autoclicker.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.autoclicker.app.data.ClickConfig
import com.autoclicker.app.data.ConfigRepository

/**
 * Accessibility Service that performs automated screen taps using dispatchGesture().
 *
 * This service runs in the background and dispatches touch gestures at the configured
 * position and interval. It must be enabled manually by the user in device Settings.
 *
 * Supports both single-position clicking and multi-position sequential clicking
 * (for piano layout grids).
 */
class AutoClickerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isClicking = false
    private var clickCount = 0
    private var currentConfig = ClickConfig()

    // Multi-position support
    private var clickPositions = listOf<Pair<Float, Float>>()
    private var currentPositionIndex = 0

    private val clickRunnable = object : Runnable {
        override fun run() {
            if (!isClicking) return

            val config = currentConfig

            if (clickPositions.isNotEmpty()) {
                // Multi-position mode: click all positions in sequence
                val pos = clickPositions[currentPositionIndex]
                performClick(pos.first, pos.second)
                currentPositionIndex = (currentPositionIndex + 1) % clickPositions.size

                // Only count a full cycle as one "click" for repeat tracking
                if (currentPositionIndex == 0) {
                    clickCount++
                    if (config.repeatCount != ClickConfig.INFINITE && clickCount >= config.repeatCount) {
                        stopClicking()
                        return
                    }
                }
            } else {
                // Single-position mode (legacy)
                performClick(config.x, config.y)
                clickCount++
                if (config.repeatCount != ClickConfig.INFINITE && clickCount >= config.repeatCount) {
                    stopClicking()
                    return
                }
            }

            // Schedule next click
            handler.postDelayed(this, config.intervalMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutoClicker Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events for auto clicking.
        // The service just needs to be active to use dispatchGesture().
    }

    override fun onInterrupt() {
        Log.d(TAG, "AutoClicker Accessibility Service interrupted")
        stopClicking()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopClicking()
        instance = null
        Log.d(TAG, "AutoClicker Accessibility Service destroyed")
    }

    /**
     * Start the auto-click loop with the current configuration (single position).
     */
    fun startClicking() {
        if (isClicking) return

        // Load latest config
        currentConfig = ConfigRepository.getInstance(this).config.value
        clickPositions = emptyList()

        isClicking = true
        clickCount = 0
        currentPositionIndex = 0
        _isRunning.value = true

        Log.d(TAG, "Started clicking at (${currentConfig.x}, ${currentConfig.y}) every ${currentConfig.intervalMs}ms")

        // Start immediately
        handler.post(clickRunnable)
    }

    /**
     * Start the auto-click loop with multiple positions (for grid layouts).
     */
    fun startClickingMultiple(positions: List<Pair<Float, Float>>) {
        if (isClicking) return

        // Load latest config
        currentConfig = ConfigRepository.getInstance(this).config.value
        clickPositions = positions

        isClicking = true
        clickCount = 0
        currentPositionIndex = 0
        _isRunning.value = true

        Log.d(TAG, "Started multi-click with ${positions.size} positions every ${currentConfig.intervalMs}ms")

        // Start immediately
        handler.post(clickRunnable)
    }

    /**
     * Stop the auto-click loop.
     */
    fun stopClicking() {
        isClicking = false
        handler.removeCallbacks(clickRunnable)
        _isRunning.value = false
        Log.d(TAG, "Stopped clicking after $clickCount clicks")
    }

    /**
     * Perform a single tap at the given screen coordinates.
     */
    private fun performClick(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires API 24+")
            return
        }

        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0,   // startTime: begin immediately
            50   // duration: 50ms tap (short press)
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.v(TAG, "Click #$clickCount completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Click #$clickCount cancelled at ($x, $y)")
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "Failed to dispatch gesture at ($x, $y)")
        }
    }

    companion object {
        private const val TAG = "AutoClickerService"

        /**
         * Singleton reference to the running service instance.
         * Null when the service is not connected.
         */
        @Volatile
        var instance: AutoClickerAccessibilityService? = null
            private set

        /**
         * Observable state of whether the click loop is active.
         */
        private val _isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRunning

        /**
         * Check if the accessibility service is currently enabled and connected.
         */
        val isServiceConnected: Boolean
            get() = instance != null
    }
}
