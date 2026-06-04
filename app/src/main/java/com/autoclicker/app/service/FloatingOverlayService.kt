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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configRepository = ConfigRepository.getInstance(this)

        // Get screen dimensions
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val config = configRepository.config.value
        currentScaleX = config.gridScaleX
        currentScaleY = config.gridScaleY
        currentMarkers = config.layoutType.generateMarkers()

        createGridView()
        createResizeHandles()
        createBubbleView()

        Log.d(TAG, "FloatingOverlayService created with layout: ${config.layoutType.displayName}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop clicking if active
        if (isPlaying) {
            AutoClickerAccessibilityService.instance?.stopClicking()
        }
        removeBubbleView()
        removeGridView()
        removeResizeHandles()
        removePanelView()
        Log.d(TAG, "FloatingOverlayService destroyed")
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

        // Window params
        val params = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
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
    }

    private fun removeBubbleView() {
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    // ─── Grid View ───────────────────────────────────────────────────────

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
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

        // Interval info
        val intervalText = TextView(this).apply {
            text = "Interval: ${config.intervalMs}ms"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dpToPx(4))
        }
        panel.addView(intervalText)

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
                stopSelf()
            }
        }
        panel.addView(closeBtn)

        // Window params
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
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

    private fun toggleClicking(icon: ImageView) {
        val service = AutoClickerAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not connected!")
            return
        }

        if (isPlaying) {
            service.stopClicking()
            isPlaying = false
            icon.setImageResource(android.R.drawable.ic_media_play)
            gridView?.visibility = View.VISIBLE
            resizeHandleRight?.visibility = View.VISIBLE
            resizeHandleBottom?.visibility = View.VISIBLE
        } else {
            // Compute all click positions from marker screen coordinates
            val positions = getMarkerScreenPositions()
            if (positions.isNotEmpty()) {
                // Update the primary position to the first marker
                configRepository.updatePosition(positions[0].first, positions[0].second)
            }
            service.startClicking()
            isPlaying = true
            icon.setImageResource(android.R.drawable.ic_media_pause)
            // Hide grid during clicking so it doesn't interfere
            gridView?.visibility = View.GONE
            resizeHandleRight?.visibility = View.GONE
            resizeHandleBottom?.visibility = View.GONE
        }
    }

    /**
     * Get the absolute screen positions of all markers.
     */
    fun getMarkerScreenPositions(): List<Pair<Float, Float>> {
        val gParams = gridParams ?: return emptyList()
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

    companion object {
        private const val TAG = "FloatingOverlay"
        private const val CHANNEL_ID = "auto_clicker_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.autoclicker.app.STOP"

        // Marker appearance constants (FIXED size, not affected by scaling)
        private const val MARKER_SIZE_DP = 40
        private const val GRID_PADDING_DP = 8

        /**
         * Start the floating overlay service.
         */
        fun start(context: Context) {
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
