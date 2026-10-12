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
                }
            } else {
                // Single-position mode (legacy)
                performClick(config.x, config.y)
                clickCount++
            }

            // Schedule next click
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.flags = info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
            detectActiveDisplay()
        }
        Log.d(TAG, "AutoClicker Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore system UI and our own app (overlay / main activity) so they never pull focus away
        if (pkg == "com.android.systemui" || pkg == packageName) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val dId = event.displayId
                    if (dId >= 0) {
                        ActiveDisplayTracker.update(dId)
                    } else {
                        detectActiveDisplay()
                    }
                }
            }
        }
    }

    /**
     * Inspect interactive windows across all displays to locate the active foreground application.
     */
    fun detectActiveDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val allWindows = windowsOnAllDisplays
                var fallbackDisplay = -1

                for (i in 0 until allWindows.size()) {
                    val dispId = allWindows.keyAt(i)
                    val windowList = allWindows.valueAt(i) ?: continue
                    for (w in windowList) {
                        // Skip our own windows and system windows
                        val rootPkg = try { w.root?.packageName?.toString() } catch (_: Exception) { null }
                        if (rootPkg == packageName || rootPkg == "com.android.systemui") continue

                        if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                            if (w.isActive && w.isFocused) {
                                ActiveDisplayTracker.update(dispId)
                                return
                            }
                            if (w.isFocused && fallbackDisplay == -1) {
                                fallbackDisplay = dispId
                            }
                        }
                    }
                }

                if (fallbackDisplay != -1) {
                    ActiveDisplayTracker.update(fallbackDisplay)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inspect windows across displays", e)
            }
        }
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

        Log.d(TAG, "Started clicking at (${currentConfig.x}, ${currentConfig.y})")

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

        Log.d(TAG, "Started multi-click with ${positions.size} positions")

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
    private fun performClick(x: Float, y: Float, durationMs: Long = 40L) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires API 24+")
            return
        }

        // Static tap: moveTo only (no lineTo).
        // In Android's StrokeDescription documentation:
        // "If the path contains only a single point (specified by a single moveTo), it represents a touch at that point with no movement."
        // Moving coordinates (lineTo) causes Chrome / web browsers to fire touchmove, cancelling click events!
        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0,   // startTime: begin immediately
            durationMs.coerceIn(25L, 60L)
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .apply { applyTargetDisplay(this) }
            .build()

        var dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.v(TAG, "Click #$clickCount completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Click #$clickCount cancelled at ($x, $y)")
            }
        }, null)

        // If secondary display gesture was rejected by system security policy, fallback to DEFAULT_DISPLAY
        if (!dispatched && ActiveDisplayTracker.displayId != android.view.Display.DEFAULT_DISPLAY) {
            val fallbackGesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatched = dispatchGesture(fallbackGesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.v(TAG, "Fallback click #$clickCount completed at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Fallback click #$clickCount cancelled at ($x, $y)")
                }
            }, null)
        }

        if (!dispatched) {
            Log.e(TAG, "Failed to dispatch gesture at ($x, $y)")
        }
    }

    /**
     * Perform a single tap at the given screen coordinates.
     * Public version for use by [MidiPlaybackEngine] which controls its own timing.
     */
    fun performSingleClick(x: Float, y: Float, durationMs: Long = 40L) {
        performClick(x, y, durationMs)
    }

    /**
     * Perform multiple simultaneous taps at the given screen coordinates.
     * Used for playing MIDI chords.
     */
    fun performMultiClick(points: List<Triple<Float, Float, Long>>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires API 24+")
            return
        }

        if (points.isEmpty()) return

        // Deduplicate touch positions so two simultaneous strokes never overlap on the same coordinate
        val uniquePoints = points.distinctBy { Pair(it.first, it.second) }

        // Android usually supports up to 10 simultaneous touches depending on hardware
        val maxPoints = minOf(uniquePoints.size, 10)

        // For simultaneous chord touches, all pointers MUST have identical duration
        // so all pointers release at the exact same millisecond and leave no dangling pointers in InputDispatcher.
        val chordDuration = (uniquePoints.firstOrNull()?.third ?: 40L).coerceIn(25L, 60L)

        val strokes = mutableListOf<GestureDescription.StrokeDescription>()
        for (i in 0 until maxPoints) {
            val (x, y, _) = uniquePoints[i]
            val path = Path().apply {
                moveTo(x, y)
            }
            strokes.add(GestureDescription.StrokeDescription(path, 0, chordDuration))
        }

        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }
        applyTargetDisplay(builder)
        val gesture = builder.build()

        var dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.v(TAG, "Multi-click #$clickCount completed with ${uniquePoints.size} points")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Multi-click #$clickCount cancelled")
            }
        }, null)

        if (!dispatched && ActiveDisplayTracker.displayId != android.view.Display.DEFAULT_DISPLAY) {
            val fallbackBuilder = GestureDescription.Builder()
            strokes.forEach { fallbackBuilder.addStroke(it) }
            dispatched = dispatchGesture(fallbackBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.v(TAG, "Fallback multi-click #$clickCount completed with ${uniquePoints.size} points")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Fallback multi-click #$clickCount cancelled")
                }
            }, null)
        }

        if (!dispatched) {
            Log.e(TAG, "Failed to dispatch multi-gesture")
        }
    }

    /**
     * Send the gesture to the display the foreground app is on. Without this,
     * gestures always go to display 0, which on per-app-display environments
     * (e.g. MuMu Android 15) is only the home screen.
     */
    private fun applyTargetDisplay(builder: GestureDescription.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(ActiveDisplayTracker.displayId)
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
