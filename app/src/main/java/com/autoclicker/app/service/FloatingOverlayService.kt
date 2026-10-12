package com.autoclicker.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.app.Activity
import android.hardware.display.DisplayManager
import android.view.Display
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclicker.app.MainActivity
import com.autoclicker.app.R
import com.autoclicker.app.data.ClickConfig
import com.autoclicker.app.data.ConfigRepository
import com.autoclicker.app.data.LayoutType
import com.autoclicker.app.data.MarkerPosition
import com.autoclicker.app.data.MidiParser
import com.autoclicker.app.data.MidiPlaybackEngine
import android.net.Uri
import kotlin.math.abs

/**
 * Foreground service that renders a floating overlay with:
 * 1. A draggable floating bubble (play/pause button)
 * 2. A grid of click markers matching the selected piano button layout
 * 3. Two resize handles — right edge (horizontal) and bottom edge (vertical)
 * 4. Control panel for start/stop/close
 *
 * Marker size stays constant; only spacing adjusts when resizing.
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var configRepository: ConfigRepository

    // Views
    private var bubbleView: View? = null
    private var gridView: View? = null
    private var panelView: View? = null
    private var resizeHandleRight: View? = null    // horizontal resize
    private var resizeHandleBottom: View? = null    // vertical resize

    // Layout params
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var gridParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var resizeHandleRightParams: WindowManager.LayoutParams? = null
    private var resizeHandleBottomParams: WindowManager.LayoutParams? = null

    // State
    private var isPlaying = false
    private var isPanelVisible = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var shouldKillProcess = false

    // Display the overlay windows are currently attached to (see ActiveDisplayTracker)
    private var currentDisplayId = Display.DEFAULT_DISPLAY
    private val activeDisplayListener: (Int) -> Unit = { moveOverlayToDisplay(it) }

    // Window type matching the current [windowManager] (accessibility or app overlay)
    private var overlayWindowType = defaultOverlayWindowType()

    // Play/pause icon inside the bubble (kept so state survives a display move)
    private var bubbleIcon: ImageView? = null

    // Grid dimensions (base, before scaling)
    private var baseGridWidth = 0
    private var baseGridHeight = 0
    private var currentScaleX = 1.0f
    private var currentScaleY = 1.0f

    // Fixed marker size (does NOT change with scaling)
    private val markerSizePx: Int by lazy { dpToPx(MARKER_SIZE_DP) }

    // Marker views for updating their screen positions
    private val markerViews = mutableListOf<View>()
    private var currentMarkers = listOf<MarkerPosition>()

    /** Not a bound service — always returns null. */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Initialise the service: grab screen dimensions, start as a foreground
     * service with a persistent notification, and create all overlay views
     * (grid, resize handles, bubble) in that z-order.
     */
    override fun onCreate() {
        super.onCreate()
        // Attach to the display the user is currently looking at. On normal phones this
        // is display 0; on environments that put each app on its own virtual display
        // (e.g. MuMu Android 15) it is the app's display, and we follow it as it changes.
        currentDisplayId = ActiveDisplayTracker.displayId
        windowManager = createWindowManagerFor(currentDisplayId)
        configRepository = ConfigRepository.getInstance(this)

        updateScreenSize()

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val config = configRepository.config.value
        currentScaleX = config.gridScaleX
        currentScaleY = config.gridScaleY
        currentMarkers = config.layoutType.generateMarkers()

        attachOverlayViews()
        ActiveDisplayTracker.addListener(activeDisplayListener)

        Log.d(TAG, "FloatingOverlayService created on display $currentDisplayId (type $overlayWindowType) with layout: ${config.layoutType.displayName}")
    }

    /**
     * Handle incoming intents — currently only [ACTION_STOP] to tear down the service.
     * Returns [START_STICKY] so the system restarts the overlay if the process is killed.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shouldKillProcess = true
                stopSelf()
            }
            ACTION_TOGGLE_CLICK -> {
                toggleClicking()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ActiveDisplayTracker.removeListener(activeDisplayListener)
        // Stop clicking and playback if active
        val engine = MidiPlaybackEngine.getInstance()
        if (engine.isActive) {
            engine.stop()
        }
        AutoClickerAccessibilityService.instance?.stopClicking()

        removeBubbleView()
        removeGridView()
        removeResizeHandles()
        removePanelView()
        Log.d(TAG, "FloatingOverlayService destroyed")

        // Do NOT call Process.killProcess() here, as it kills AutoClickerAccessibilityService
        // and causes Android to mark it as a 'crashed service', permanently disabling dispatchGesture().
    }

    // ─── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_click)
                .setContentIntent(pendingIntent)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        getString(R.string.stop_clicker),
                        stopPendingIntent
                    ).build()
                )
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_click)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    // ─── Bubble View ─────────────────────────────────────────────────────

    /**
     * Create the draggable floating bubble (the circular play/pause FAB).
     *
     * The bubble has three interactive elements:
     * - **Body** — drag to reposition; tap to toggle play/pause.
     * - **Close badge** (red ✕, top-right) — tap to shut down the entire overlay.
     * - **Settings badge** (orange ⚙, bottom-right) — tap to show/hide the control panel.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        val bubbleSize = dpToPx(56)

        // Create the bubble container
        val bubble = FrameLayout(this).apply {
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00BCD4"))
                setStroke(dpToPx(2), Color.parseColor("#00E5FF"))
            }
            background = bgDrawable
            elevation = dpToPx(8).toFloat()
        }

        // Add play/pause icon
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            val padding = dpToPx(14)
            setPadding(padding, padding, padding, padding)
        }
        bubble.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Small Close Badge
        val closeBadgeSize = dpToPx(20)
        val closeBadge = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF5252"))
            }
            background = bg
            elevation = dpToPx(9).toFloat()

            val xIcon = TextView(this@FloatingOverlayService).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
            }
            addView(xIcon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            setOnClickListener {
                shouldKillProcess = true
                stopSelf()
            }
        }
        val closeParams = FrameLayout.LayoutParams(closeBadgeSize, closeBadgeSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dpToPx(2)
            marginEnd = dpToPx(2)
        }
        bubble.addView(closeBadge, closeParams)

        // Small Settings Badge
        val settingsBadge = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF9800"))
            }
            background = bg
            elevation = dpToPx(9).toFloat()

            val gearIcon = TextView(this@FloatingOverlayService).apply {
                text = "⚙"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
            }
            addView(gearIcon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            setOnClickListener {
                if (isPanelVisible) {
                    removePanelView()
                } else {
                    createPanelView()
                }
            }
        }
        val settingsParams = FrameLayout.LayoutParams(closeBadgeSize, closeBadgeSize).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dpToPx(2)
            marginEnd = dpToPx(2)
        }
        bubble.addView(settingsBadge, settingsParams)

        // Window params
        val params = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - bubbleSize - dpToPx(16)
            y = screenHeight / 3
        }

        // Touch handling (drag + tap)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(bubble, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap: toggle play/pause
                        toggleClicking(icon)
                    }
                    true
                }
                else -> false
            }
        }

        // Long press to show/hide panel
        bubble.setOnLongClickListener {
            if (isPanelVisible) {
                removePanelView()
            } else {
                createPanelView()
            }
            true
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
        bubbleIcon = icon
    }

    private fun removeBubbleView() {
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    // ─── Grid View ───────────────────────────────────────────────────────

    /**
     * Build and display the marker grid overlay.
     *
     * Reads the current layout's markers, calculates the container size
     * (base size × scale factors), places all marker views inside, and
     * makes the whole grid draggable. Grid position is persisted when
     * the user finishes dragging.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createGridView() {
        val config = configRepository.config.value
        val markers = currentMarkers

        // Determine the base grid size from marker positions
        calculateBaseGridSize(markers)

        val scaledWidth = (baseGridWidth * currentScaleX).toInt()
        val scaledHeight = (baseGridHeight * currentScaleY).toInt()

        // Container FrameLayout for all markers
        val container = FrameLayout(this).apply {
            // Semi-transparent background so user can see the grid boundary
            val bgDrawable = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#1A000000"))
                setStroke(dpToPx(1), Color.parseColor("#3300E5FF"))
            }
            background = bgDrawable
        }

        // Add marker views (fixed size, only spacing changes)
        markerViews.clear()
        for (marker in markers) {
            val markerView = createMarkerView(marker)
            markerViews.add(markerView)
            container.addView(markerView)
        }

        // Position markers within the container
        layoutMarkersInContainer(markers, scaledWidth, scaledHeight)

        // Window params
        val params = WindowManager.LayoutParams(
            scaledWidth, scaledHeight,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = config.gridOffsetX.toInt()
            y = config.gridOffsetY.toInt()
        }

        // Drag the entire grid
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 5 || abs(dy) > 5) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(container, params)
                            updateResizeHandlePositions()
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        configRepository.updateGridOffset(params.x.toFloat(), params.y.toFloat())
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(container, params)
        gridView = container
        gridParams = params
    }

    /**
     * Create a single circular marker view for a note button.
     * Marker size is FIXED — it does not change when the grid is resized.
     */
    private fun createMarkerView(marker: MarkerPosition): View {
        val size = if (marker.isSharp) (markerSizePx * 0.7f).toInt() else markerSizePx

        val frame = FrameLayout(this)

        // Circle background
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (marker.isSharp) {
                setColor(Color.parseColor("#CC555555"))
                setStroke(dpToPx(1), Color.parseColor("#888888"))
            } else {
                setColor(Color.parseColor("#CCFFFFFF"))
                setStroke(dpToPx(1), Color.parseColor("#AAAAAA"))
            }
        }
        frame.background = bgDrawable

        // Center dot (click target indicator)
        val dot = View(this).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#66FF5252"))
            }
            background = dotBg
        }
        val dotSize = dpToPx(4)
        val dotParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
            gravity = Gravity.CENTER
        }
        frame.addView(dot, dotParams)

        // Label text
        if (!marker.isSharp) {
            val label = TextView(this).apply {
                text = marker.label
                setTextColor(Color.parseColor("#333333"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setLineSpacing(0f, 0.85f)
            }
            val labelParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
            frame.addView(label, labelParams)
        }

        frame.layoutParams = FrameLayout.LayoutParams(size, size)
        return frame
    }

    /**
     * Calculate the base grid size from marker positions.
     * Base size = just enough room for markers at scale 1.0 with minimal spacing.
     */
    private fun calculateBaseGridSize(markers: List<MarkerPosition>) {
        if (markers.isEmpty()) {
            baseGridWidth = markerSizePx * 5
            baseGridHeight = markerSizePx * 3
            return
        }

        val padding = dpToPx(GRID_PADDING_DP)
        val uniqueYs = markers.map { it.relativeY }.distinct().sorted()

        // Max columns across all rows (white keys only for sizing)
        val maxCols = markers.filter { !it.isSharp }.groupBy { it.relativeY }
            .maxOf { it.value.size }

        baseGridWidth = (maxCols * (markerSizePx + dpToPx(4))) + padding * 2
        baseGridHeight = (uniqueYs.size * (markerSizePx + dpToPx(8))) + padding * 2

        // Ensure minimum size
        baseGridWidth = baseGridWidth.coerceAtLeast(markerSizePx * 3)
        baseGridHeight = baseGridHeight.coerceAtLeast(markerSizePx * 2)
    }

    /**
     * Position all markers within the container based on their relative positions.
     * Marker size stays constant — only spacing (the container dimensions) changes.
     */
    private fun layoutMarkersInContainer(
        markers: List<MarkerPosition>,
        containerWidth: Int,
        containerHeight: Int
    ) {
        val padding = dpToPx(GRID_PADDING_DP)
        val halfMarker = markerSizePx / 2

        // Usable space for distributing marker CENTERS
        val usableWidth = containerWidth - padding * 2
        val usableHeight = containerHeight - padding * 2

        for (i in markers.indices) {
            if (i >= markerViews.size) break
            val marker = markers[i]
            val view = markerViews[i]

            val isSharp = marker.isSharp
            val thisSize = if (isSharp) (markerSizePx * 0.7f).toInt() else markerSizePx
            val halfThis = thisSize / 2

            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.width = thisSize
            lp.height = thisSize

            // Map relative position → pixel center, then offset by half-marker
            val clampedX = marker.relativeX.coerceIn(0f, 1f)
            val clampedY = marker.relativeY.coerceIn(0f, 1f)

            val centerX = padding + (clampedX * usableWidth).toInt()
            val centerY = padding + (clampedY * usableHeight).toInt()

            lp.leftMargin = centerX - halfThis
            lp.topMargin = centerY - halfThis

            // Sharp keys: offset up slightly to sit between white keys
            if (isSharp) {
                lp.topMargin -= (markerSizePx * 0.15f).toInt()
            }

            lp.gravity = Gravity.TOP or Gravity.START
            view.layoutParams = lp
        }
    }

    private fun removeGridView() {
        gridView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        gridView = null
        markerViews.clear()
    }

    // ─── Resize Handles (separate H and V) ──────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createResizeHandles() {
        val gp = gridParams ?: return
        createRightResizeHandle(gp)
        createBottomResizeHandle(gp)
    }

    /**
     * Right-edge handle: drag left/right to resize width (horizontal spacing).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createRightResizeHandle(gp: WindowManager.LayoutParams) {
        val handleW = dpToPx(24)
        val handleH = dpToPx(48)

        val handle = FrameLayout(this).apply {
            val bgDrawable = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor("#CC00BCD4"))
                setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
            }
            background = bgDrawable
            elevation = dpToPx(6).toFloat()
        }

        // Horizontal arrow icon
        val icon = TextView(this).apply {
            text = "↔"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        handle.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val params = WindowManager.LayoutParams(
            handleW, handleH,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = gp.x + gp.width - handleW / 2
            y = gp.y + gp.height / 2 - handleH / 2
        }

        // Drag to resize horizontally
        var initialTouchX = 0f
        var initialWidth = 0

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialWidth = gridParams?.width ?: baseGridWidth
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val newWidth = (initialWidth + dx).toInt()
                        .coerceAtLeast((baseGridWidth * ClickConfig.MIN_GRID_SCALE).toInt())
                        .coerceAtMost((baseGridWidth * ClickConfig.MAX_GRID_SCALE).toInt())
                    applyGridSize(newWidth, gridParams?.height ?: baseGridHeight)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Persist the new scaleX
                    val gParams = gridParams ?: return@setOnTouchListener false
                    currentScaleX = gParams.width.toFloat() / baseGridWidth.toFloat()
                    configRepository.updateGridScaleX(currentScaleX)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(handle, params)
        resizeHandleRight = handle
        resizeHandleRightParams = params
    }

    /**
     * Bottom-edge handle: drag up/down to resize height (vertical spacing).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createBottomResizeHandle(gp: WindowManager.LayoutParams) {
        val handleW = dpToPx(48)
        val handleH = dpToPx(24)

        val handle = FrameLayout(this).apply {
            val bgDrawable = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor("#CC00BCD4"))
                setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
            }
            background = bgDrawable
            elevation = dpToPx(6).toFloat()
        }

        // Vertical arrow icon
        val icon = TextView(this).apply {
            text = "↕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        handle.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val params = WindowManager.LayoutParams(
            handleW, handleH,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = gp.x + gp.width / 2 - handleW / 2
            y = gp.y + gp.height - handleH / 2
        }

        // Drag to resize vertically
        var initialTouchY = 0f
        var initialHeight = 0

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchY = event.rawY
                    initialHeight = gridParams?.height ?: baseGridHeight
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - initialTouchY
                    val newHeight = (initialHeight + dy).toInt()
                        .coerceAtLeast((baseGridHeight * ClickConfig.MIN_GRID_SCALE).toInt())
                        .coerceAtMost((baseGridHeight * ClickConfig.MAX_GRID_SCALE).toInt())
                    applyGridSize(gridParams?.width ?: baseGridWidth, newHeight)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Persist the new scaleY
                    val gParams = gridParams ?: return@setOnTouchListener false
                    currentScaleY = gParams.height.toFloat() / baseGridHeight.toFloat()
                    configRepository.updateGridScaleY(currentScaleY)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(handle, params)
        resizeHandleBottom = handle
        resizeHandleBottomParams = params
    }

    /**
     * Apply new width/height to the grid and re-layout markers dynamically.
     * Marker size stays constant — only the spacing between them changes.
     */
    private fun applyGridSize(newWidth: Int, newHeight: Int) {
        val gParams = gridParams ?: return

        gParams.width = newWidth
        gParams.height = newHeight

        try {
            windowManager.updateViewLayout(gridView, gParams)
        } catch (_: Exception) { return }

        // Re-layout markers with new spacing (marker size unchanged)
        layoutMarkersInContainer(currentMarkers, newWidth, newHeight)

        // Update both handle positions
        updateResizeHandlePositions()
    }

    /**
     * Reposition both resize handles so they stay anchored to the grid edges
     * after the grid is moved or resized.
     */
    private fun updateResizeHandlePositions() {
        val gParams = gridParams ?: return

        // Right handle: centered on right edge
        resizeHandleRightParams?.let { rp ->
            val handleW = dpToPx(24)
            val handleH = dpToPx(48)
            rp.x = gParams.x + gParams.width - handleW / 2
            rp.y = gParams.y + gParams.height / 2 - handleH / 2
            try { windowManager.updateViewLayout(resizeHandleRight, rp) } catch (_: Exception) {}
        }

        // Bottom handle: centered on bottom edge
        resizeHandleBottomParams?.let { bp ->
            val handleW = dpToPx(48)
            val handleH = dpToPx(24)
            bp.x = gParams.x + gParams.width / 2 - handleW / 2
            bp.y = gParams.y + gParams.height - handleH / 2
            try { windowManager.updateViewLayout(resizeHandleBottom, bp) } catch (_: Exception) {}
        }
    }

    private fun removeResizeHandles() {
        resizeHandleRight?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        resizeHandleRight = null
        resizeHandleBottom?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        resizeHandleBottom = null
    }

    // ─── Panel View ──────────────────────────────────────────────────────

    /**
     * Build and show the floating settings panel (layout info, change layout,
     * import MIDI, close overlay). Positioned at screen centre.
     */
    @SuppressLint("SetTextI18n")
    private fun createPanelView() {
        if (isPanelVisible) return

        val config = configRepository.config.value
        val panelWidth = dpToPx(220)
        val panelPadding = dpToPx(16)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bgDrawable = GradientDrawable().apply {
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#E61A1B2E"))
                setStroke(dpToPx(1), Color.parseColor("#3300E5FF"))
            }
            background = bgDrawable
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            elevation = dpToPx(12).toFloat()
        }

        // Title
        val title = TextView(this).apply {
            text = "Auto Clicker"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dpToPx(8))
        }
        panel.addView(title)

        // Layout info
        val layoutText = TextView(this).apply {
            text = "Layout: ${config.layoutType.displayName}"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dpToPx(4))
        }
        panel.addView(layoutText)

        // Scale info — now shows H×V
        val scaleText = TextView(this).apply {
            text = "Scale: ${"%.1f".format(currentScaleX)}× H  ·  ${"%.1f".format(currentScaleY)}× V"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dpToPx(4))
        }
        panel.addView(scaleText)

        // Markers count
        val markersText = TextView(this).apply {
            text = "Markers: ${currentMarkers.size}"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dpToPx(12))
        }
        panel.addView(markersText)

        // Change Layout Button
        val changeLayoutBtn = TextView(this).apply {
            text = "Change Layout"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val btnBg = GradientDrawable().apply {
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#3300E5FF"))
            }
            background = btnBg
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            gravity = Gravity.CENTER
            setOnClickListener {
                val types = LayoutType.entries
                val current = configRepository.config.value.layoutType
                val next = types[(types.indexOf(current) + 1) % types.size]
                configRepository.updateLayoutType(next)
                layoutText.text = "Layout: ${next.displayName}"
                
                // Update grid
                currentMarkers = next.generateMarkers()
                calculateBaseGridSize(currentMarkers)
                val newW = (baseGridWidth * currentScaleX).toInt()
                val newH = (baseGridHeight * currentScaleY).toInt()
                
                gridView?.let {
                    val container = it as FrameLayout
                    container.removeAllViews()
                    markerViews.clear()
                    for (marker in currentMarkers) {
                        val markerView = createMarkerView(marker)
                        markerViews.add(markerView)
                        container.addView(markerView)
                    }
                    applyGridSize(newW, newH)
                }
                markersText.text = "Markers: ${currentMarkers.size}"
            }
        }
        panel.addView(changeLayoutBtn)

        val spacer1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dpToPx(8)) }
        panel.addView(spacer1)

        // Import MIDI Button
        val importMidiBtn = TextView(this).apply {
            text = "Import MIDI File"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val btnBg = GradientDrawable().apply {
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#3300E5FF"))
            }
            background = btnBg
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            gravity = Gravity.CENTER
            setOnClickListener {
                val intent = Intent(this@FloatingOverlayService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_midi_picker", true)
                }
                startActivity(intent)
                removePanelView()
            }
        }
        panel.addView(importMidiBtn)

        val spacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dpToPx(8)) }
        panel.addView(spacer2)

        // Close button
        val closeBtn = TextView(this).apply {
            text = "✕ Close Overlay"
            setTextColor(Color.parseColor("#FF5252"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val btnBg = GradientDrawable().apply {
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#33FF5252"))
            }
            background = btnBg
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            gravity = Gravity.CENTER
            setOnClickListener {
                shouldKillProcess = true
                stopSelf()
            }
        }
        panel.addView(closeBtn)

        // Window params
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(panel, params)
        panelView = panel
        panelParams = params
        isPanelVisible = true
    }

    private fun removePanelView() {
        panelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        panelView = null
        isPanelVisible = false
    }

    // ─── Click Control ───────────────────────────────────────────────────

    /**
     * Toggle between playing and stopped states.
     *
     * On **play**:
     * - If a MIDI file is loaded → start [MidiPlaybackEngine]-driven playback.
     * - Otherwise → fall back to the legacy sequential click loop.
     * - Hides resize handles and dims the grid while playing.
     *
     * On **stop**:
     * - Stops MIDI playback or the legacy click loop.
     * - Restores grid interactivity and shows resize handles.
     *
     * @param icon The play/pause [ImageView] inside the bubble to update.
     */
    private fun toggleClicking(icon: ImageView? = null) {
        val iconView = icon ?: bubbleIcon
        val service = AutoClickerAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not connected!")
            return
        }

        val config = configRepository.config.value

        if (isPlaying) {
            // Stop: check if MIDI playback is active
            val engine = MidiPlaybackEngine.getInstance()
            if (engine.isActive) {
                engine.stop()
            } else {
                service.stopClicking()
            }
            isPlaying = false
            iconView?.setImageResource(android.R.drawable.ic_media_play)
            gridParams?.let { p ->
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                p.alpha = 1.0f
                try { windowManager.updateViewLayout(gridView, p) } catch (_: Exception) {}
            }
            gridView?.alpha = 1.0f
            gridView?.visibility = View.VISIBLE
            resizeHandleRight?.visibility = View.VISIBLE
            resizeHandleBottom?.visibility = View.VISIBLE
        } else {
            // Check if we have a MIDI file loaded
            val midiUri = config.midiFileUri
            if (midiUri != null) {
                // MIDI playback mode
                val song = MidiParser.parse(this, Uri.parse(midiUri), config.midiFileName ?: "MIDI")
                if (song != null && song.noteOnCount > 0) {
                    val positions = getMarkerScreenPositions()
                    val engine = MidiPlaybackEngine.getInstance()
                    engine.play(
                        song = song,
                        layoutType = config.layoutType,
                        markerScreenPositions = positions,
                        speedMultiplier = config.midiSpeedMultiplier,
                        pitchTransposition = config.pitchTransposition,
                        onMarkerClicked = { markerIndex ->
                            highlightMarker(markerIndex)
                        }
                    )
                    isPlaying = true
                    iconView?.setImageResource(android.R.drawable.ic_media_pause)
                    gridParams?.let { p ->
                        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        p.alpha = 1.0f // Never set window alpha < 1.0f, or Android BLOCK_UNTRUSTED blocks touches underneath!
                        try { windowManager.updateViewLayout(gridView, p) } catch (_: Exception) {}
                    }
                    gridView?.alpha = 0.5f // Dim view contents, not window params
                    gridView?.visibility = View.VISIBLE
                    resizeHandleRight?.visibility = View.GONE
                    resizeHandleBottom?.visibility = View.GONE
                    Log.d(TAG, "Started MIDI playback: ${song.name}, ${song.noteOnCount} notes")
                    // Log first few positions for debugging note accuracy
                    for (i in 0 until minOf(positions.size, 5)) {
                        val m = currentMarkers.getOrNull(i)
                        Log.d(TAG, "  Marker[$i] ${m?.label ?: "?"}: tap=(${positions[i].first.toInt()}, ${positions[i].second.toInt()}) sharp=${m?.isSharp}")
                    }
                } else {
                    Log.w(TAG, "Failed to parse MIDI file or no notes found")
                }
            } else {
                // Legacy mode: click all marker positions in sequence
                val positions = getMarkerScreenPositions()
                if (positions.isNotEmpty()) {
                    service.startClickingMultiple(positions)
                } else {
                    service.startClicking()
                }
                isPlaying = true
                iconView?.setImageResource(android.R.drawable.ic_media_pause)
                gridParams?.let { p ->
                    p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    p.alpha = 1.0f // Never set window alpha < 1.0f, or Android BLOCK_UNTRUSTED blocks touches underneath!
                    try { windowManager.updateViewLayout(gridView, p) } catch (_: Exception) {}
                }
                gridView?.alpha = 0.5f // Dim view contents, not window params
                gridView?.visibility = View.VISIBLE
                resizeHandleRight?.visibility = View.GONE
                resizeHandleBottom?.visibility = View.GONE
            }
        }
    }

    /**
     * Briefly shrink and bounce-back a marker view to give visual feedback
     * that a MIDI note was just played at that position.
     *
     * @param index Marker index within [markerViews].
     */
    private fun highlightMarker(index: Int) {
        if (index < 0 || index >= markerViews.size) return
        val markerFrame = markerViews[index]
        
        // Simulating a press: scale down quickly, then back to 1.0
        markerFrame.animate().cancel()
        markerFrame.scaleX = 0.7f
        markerFrame.scaleY = 0.7f
        
        markerFrame.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150)
            .start()
    }

    /**
     * Get the absolute screen positions of all markers.
     *
     * Uses the actual rendered view positions (via getLocationOnScreen) to
     * guarantee taps land exactly where the user sees the markers.
     * Falls back to calculation-based positioning if views aren't laid out yet.
     */
    fun getMarkerScreenPositions(): List<Pair<Float, Float>> {
        val gParams = gridParams ?: return emptyList()

        // Try view-based positions first (most accurate)
        if (markerViews.isNotEmpty() && markerViews.size == currentMarkers.size) {
            val viewPositions = mutableListOf<Pair<Float, Float>>()
            val screenLocation = IntArray(2)
            var allValid = true

            for (view in markerViews) {
                view.getLocationOnScreen(screenLocation)
                val centerX = screenLocation[0] + view.width / 2f
                val centerY = screenLocation[1] + view.height / 2f

                // Sanity check: view must have been laid out
                if (view.width == 0 && view.height == 0) {
                    allValid = false
                    break
                }
                viewPositions.add(Pair(centerX, centerY))
            }

            if (allValid && viewPositions.size == currentMarkers.size) {
                return viewPositions
            }
        }

        // Fallback: calculate from relative positions (same as before)
        val padding = dpToPx(GRID_PADDING_DP)
        val usableWidth = gParams.width - padding * 2
        val usableHeight = gParams.height - padding * 2

        return currentMarkers.map { marker ->
            val clampedX = marker.relativeX.coerceIn(0f, 1f)
            val clampedY = marker.relativeY.coerceIn(0f, 1f)
            val localX = padding + clampedX * usableWidth
            val localY = padding + clampedY * usableHeight
            Pair(gParams.x + localX, gParams.y + localY)
        }
    }

    // ─── Utilities ───────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    /** Window type to use for the current [windowManager]. */
    private fun getOverlayWindowType(): Int = overlayWindowType

    /**
     * Regular (non-accessibility) overlay type for the current Android version.
     *
     * - **Android 8+** (API 26+): [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
     * - **Android 7 and below**: the deprecated [WindowManager.LayoutParams.TYPE_PHONE].
     */
    private fun defaultOverlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // ─── Multi-display support ───────────────────────────────────────────

    /**
     * Build a [WindowManager] that adds windows to [displayId] and set
     * [overlayWindowType] to the matching window type.
     *
     * Preferred (Android 12+, accessibility service connected):
     * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] through a window context
     * created by the accessibility service. Unlike TYPE_APPLICATION_OVERLAY, this is
     * a system overlay, so it is NOT hidden when Settings or a game requests
     * "hide non-system overlay windows".
     *
     * Fallbacks: TYPE_APPLICATION_OVERLAY on [displayId] (Android 11+), then the
     * service's default WindowManager (display 0).
     */
    private fun createWindowManagerFor(displayId: Int, allowAccessibilityOverlay: Boolean = true): WindowManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val display = getSystemService(DisplayManager::class.java).getDisplay(displayId)
                if (display != null) {
                    val a11y = AutoClickerAccessibilityService.instance
                    if (allowAccessibilityOverlay && a11y != null &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        try {
                            val a11yContext = a11y.createWindowContext(
                                display,
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                null
                            )
                            overlayWindowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            return a11yContext.getSystemService(WindowManager::class.java)
                        } catch (e: Exception) {
                            Log.w(TAG, "Accessibility overlay unavailable on display $displayId", e)
                        }
                    }
                    overlayWindowType = defaultOverlayWindowType()
                    val windowContext = createDisplayContext(display)
                        .createWindowContext(overlayWindowType, null)
                    return windowContext.getSystemService(WindowManager::class.java)
                }
                Log.w(TAG, "Display $displayId not found, using default display")
            } catch (e: Exception) {
                Log.w(TAG, "Could not create window context for display $displayId", e)
            }
        }
        overlayWindowType = defaultOverlayWindowType()
        return getSystemService(WINDOW_SERVICE) as WindowManager
    }

    /**
     * Add grid, resize handles and bubble to [windowManager]. If the accessibility
     * overlay token is rejected, retry once with a regular app overlay instead of
     * crashing the service.
     */
    private fun attachOverlayViews() {
        try {
            createGridView()
            createResizeHandles()
            createBubbleView()
        } catch (e: WindowManager.BadTokenException) {
            if (overlayWindowType != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) throw e
            Log.w(TAG, "Accessibility overlay rejected, falling back to app overlay", e)
            removeResizeHandles()
            removeGridView()
            removeBubbleView()
            windowManager = createWindowManagerFor(currentDisplayId, allowAccessibilityOverlay = false)
            createGridView()
            createResizeHandles()
            createBubbleView()
        }
    }

    /** Refresh [screenWidth]/[screenHeight] from the current [windowManager]'s display. */
    private fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars()
                )
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width() - insets.left - insets.right
            screenHeight = bounds.height() - insets.top - insets.bottom
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    /**
     * Move every overlay window to [displayId] (called when the user switches to an
     * app on another display). Windows are removed via the old WindowManager and
     * re-created on the new one, keeping bubble position, panel and play state.
     */
    private fun moveOverlayToDisplay(displayId: Int) {
        if (displayId == currentDisplayId) return
        Log.d(TAG, "Moving overlay from display $currentDisplayId to $displayId")

        val bubbleX = bubbleParams?.x
        val bubbleY = bubbleParams?.y
        val panelWasVisible = isPanelVisible

        removePanelView()
        removeResizeHandles()
        removeGridView()
        removeBubbleView()

        windowManager = createWindowManagerFor(displayId)
        currentDisplayId = displayId
        updateScreenSize()

        try {
            attachOverlayViews()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-create overlay on display $displayId", e)
            return
        }

        // Restore bubble position
        bubbleParams?.let { p ->
            if (bubbleX != null && bubbleY != null) {
                p.x = bubbleX
                p.y = bubbleY
                try { windowManager.updateViewLayout(bubbleView, p) } catch (_: Exception) {}
            }
        }

        // Restore "playing" visuals (dimmed, untouchable grid, hidden handles)
        if (isPlaying) {
            bubbleIcon?.setImageResource(android.R.drawable.ic_media_pause)
            gridParams?.let { p ->
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                p.alpha = 1.0f
                try { windowManager.updateViewLayout(gridView, p) } catch (_: Exception) {}
            }
            gridView?.alpha = 0.5f
            resizeHandleRight?.visibility = View.GONE
            resizeHandleBottom?.visibility = View.GONE
        }

        if (panelWasVisible) createPanelView()
    }

    companion object {
        private const val TAG = "FloatingOverlay"

        /** Notification channel ID for the foreground service notification. */
        private const val CHANNEL_ID = "auto_clicker_channel"

        /** Unique notification ID for the foreground service. */
        private const val NOTIFICATION_ID = 1001

        /** Intent action used by the notification "Stop" button. */
        const val ACTION_STOP = "com.autoclicker.app.STOP"

        /** Intent action to toggle clicking (start/pause). */
        const val ACTION_TOGGLE_CLICK = "com.autoclicker.app.TOGGLE_CLICK"

        /** Fixed diameter of each marker circle, in dp. Not affected by grid scaling. */
        private const val MARKER_SIZE_DP = 40

        /** Inner padding of the grid container, in dp. */
        private const val GRID_PADDING_DP = 24

        /**
         * Start the floating overlay service.
         */
        fun start(context: Context) {
            // Start the overlay on the display the calling activity is on.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context is Activity) {
                try {
                    context.display?.displayId?.let { ActiveDisplayTracker.update(it) }
                } catch (_: Exception) {}
            }
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the floating overlay service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }
}
