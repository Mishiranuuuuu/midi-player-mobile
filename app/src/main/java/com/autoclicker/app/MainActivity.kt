package com.autoclicker.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoclicker.app.data.ClickConfig
import com.autoclicker.app.data.ConfigRepository
import com.autoclicker.app.data.LayoutType
import com.autoclicker.app.service.AutoClickerAccessibilityService
import com.autoclicker.app.service.FloatingOverlayService
import com.autoclicker.app.ui.theme.AutoClickerTheme
import com.autoclicker.app.ui.theme.CyanAccent
import com.autoclicker.app.ui.theme.DarkCard
import com.autoclicker.app.ui.theme.DarkSurface
import com.autoclicker.app.ui.theme.DarkSurfaceVariant
import com.autoclicker.app.ui.theme.GreenAccent
import com.autoclicker.app.ui.theme.RedAccent
import com.autoclicker.app.ui.theme.TextMuted
import com.autoclicker.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var configRepository: ConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configRepository = ConfigRepository.getInstance(this)

        setContent {
            AutoClickerTheme {
                AutoClickerApp(
                    configRepository = configRepository,
                    context = this
                )
            }
        }
    }
}

@Composable
fun AutoClickerApp(
    configRepository: ConfigRepository,
    context: Context
) {
    val config by configRepository.config.collectAsState()
    val isClickerRunning by AutoClickerAccessibilityService.isRunning.collectAsState()

    // Permission states (re-check periodically when the activity is visible)
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasAccessibilityPermission by remember { mutableStateOf(false) }

    // Re-check permissions every second (handles returning from Settings)
    LaunchedEffect(Unit) {
        while (true) {
            hasOverlayPermission = Settings.canDrawOverlays(context)
            hasAccessibilityPermission = isAccessibilityServiceEnabled(context)
            delay(1000)
        }
    }

    val allPermissionsGranted = hasOverlayPermission && hasAccessibilityPermission

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D0E1A),
                        Color(0xFF111328),
                        Color(0xFF0D0E1A)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // ─── Header ──────────────────────────────────────────────
            HeaderSection(isClickerRunning)

            Spacer(Modifier.height(24.dp))

            // ─── Permission Cards ────────────────────────────────────
            Text(
                text = "PERMISSIONS",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            PermissionCard(
                title = "Overlay Permission",
                description = "Draw floating controls over other apps",
                icon = Icons.Filled.Layers,
                isGranted = hasOverlayPermission,
                onGrant = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )

            Spacer(Modifier.height(12.dp))

            PermissionCard(
                title = "Accessibility Service",
                description = "Perform automated screen taps",
                icon = Icons.Filled.Accessibility,
                isGranted = hasAccessibilityPermission,
                onGrant = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )

            Spacer(Modifier.height(28.dp))

            // ─── Click Configuration ─────────────────────────────────
            AnimatedVisibility(
                visible = allPermissionsGranted,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(300))
            ) {
                Column {
                    Text(
                        text = "CONFIGURATION",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )

                    // ─── Layout Preset Selector ──────────────────────
                    LayoutPresetCard(
                        selectedLayout = config.layoutType,
                        onLayoutChange = { configRepository.updateLayoutType(it) }
                    )

                    Spacer(Modifier.height(12.dp))

                    // ─── Grid Spacing ─────────────────────────────────
                    GridScaleCard(
                        scaleX = config.gridScaleX,
                        scaleY = config.gridScaleY,
                        onScaleXChange = { configRepository.updateGridScaleX(it) },
                        onScaleYChange = { configRepository.updateGridScaleY(it) }
                    )

                    Spacer(Modifier.height(12.dp))

                    IntervalCard(
                        intervalMs = config.intervalMs,
                        onIntervalChange = { configRepository.updateInterval(it) }
                    )

                    Spacer(Modifier.height(12.dp))

                    RepeatCard(
                        repeatCount = config.repeatCount,
                        onRepeatChange = { configRepository.updateRepeatCount(it) }
                    )

                    Spacer(Modifier.height(28.dp))

                    // ─── Start / Stop Button ─────────────────────────
                    StartStopButton(
                        isRunning = isClickerRunning,
                        isEnabled = allPermissionsGranted,
                        onStart = { FloatingOverlayService.start(context) },
                        onStop = { FloatingOverlayService.stop(context) }
                    )

                    Spacer(Modifier.height(24.dp))
                }
            }

            // Show hint when permissions are not yet granted
            AnimatedVisibility(
                visible = !allPermissionsGranted,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(300))
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = DarkSurfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.TouchApp,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Grant both permissions above to configure and start the auto clicker.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Components
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun HeaderSection(isRunning: Boolean) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Animated icon
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulse by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size((48 * if (isRunning) pulse else 1f).dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = if (isRunning) {
                                listOf(Color(0xFF00BCD4), Color(0xFF00E5FF))
                            } else {
                                listOf(Color(0xFF1A1B2E), Color(0xFF252842))
                            }
                        )
                    )
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(CyanAccent.copy(alpha = 0.6f), CyanAccent.copy(alpha = 0.2f))
                        ),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = "Auto Clicker",
                    tint = if (isRunning) Color.White else CyanAccent,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column {
                Text(
                    text = "Auto Clicker",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor by animateColorAsState(
                        targetValue = if (isRunning) GreenAccent else TextMuted,
                        animationSpec = tween(300),
                        label = "statusColor"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isRunning) "Active" else "Idle",
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isGranted) GreenAccent.copy(alpha = 0.4f) else CyanAccent.copy(alpha = 0.15f),
        animationSpec = tween(300),
        label = "borderColor"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isGranted) GreenAccent.copy(alpha = 0.15f)
                        else DarkSurfaceVariant
                    )
            ) {
                Icon(
                    if (isGranted) Icons.Filled.Check else icon,
                    contentDescription = null,
                    tint = if (isGranted) GreenAccent else CyanAccent,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            if (!isGranted) {
                OutlinedButton(
                    onClick = onGrant,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = CyanAccent
                    )
                ) {
                    Text("Grant")
                }
            }
        }
    }
}

@Composable
fun LayoutPresetCard(
    selectedLayout: LayoutType,
    onLayoutChange: (LayoutType) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyanAccent.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.GridView,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Piano Layout",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            Spacer(Modifier.height(12.dp))

            LayoutType.entries.forEach { layout ->
                val isSelected = layout == selectedLayout
                val borderColor by animateColorAsState(
                    targetValue = if (isSelected) CyanAccent.copy(alpha = 0.6f)
                    else Color.Transparent,
                    animationSpec = tween(200),
                    label = "layoutBorder"
                )
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) CyanAccent.copy(alpha = 0.1f)
                    else DarkSurfaceVariant.copy(alpha = 0.5f),
                    animationSpec = tween(200),
                    label = "layoutBg"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                        .clickable { onLayoutChange(layout) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isSelected) Icons.Filled.RadioButtonChecked
                        else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) CyanAccent else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = layout.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) Color.White else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            text = layout.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GridScaleCard(
    scaleX: Float,
    scaleY: Float,
    onScaleXChange: (Float) -> Unit,
    onScaleYChange: (Float) -> Unit
) {
    var sliderX by remember(scaleX) { mutableFloatStateOf(scaleX) }
    var sliderY by remember(scaleY) { mutableFloatStateOf(scaleY) }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyanAccent.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.ZoomOutMap,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Grid Spacing",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Horizontal spacing ───────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Horizontal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(80.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${"%.1f".format(sliderX)}×",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = sliderX,
                onValueChange = { sliderX = it },
                onValueChangeFinished = { onScaleXChange(sliderX) },
                valueRange = ClickConfig.MIN_GRID_SCALE..ClickConfig.MAX_GRID_SCALE,
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = CyanAccent,
                    activeTrackColor = CyanAccent,
                    inactiveTrackColor = DarkSurfaceVariant
                )
            )

            Spacer(Modifier.height(8.dp))

            // ── Vertical spacing ─────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Vertical",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(80.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${"%.1f".format(sliderY)}×",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = sliderY,
                onValueChange = { sliderY = it },
                onValueChangeFinished = { onScaleYChange(sliderY) },
                valueRange = ClickConfig.MIN_GRID_SCALE..ClickConfig.MAX_GRID_SCALE,
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = CyanAccent,
                    activeTrackColor = CyanAccent,
                    inactiveTrackColor = DarkSurfaceVariant
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0.3×", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("1.0×", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("3.0×", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}


@Composable
fun IntervalCard(
    intervalMs: Long,
    onIntervalChange: (Long) -> Unit
) {
    var sliderValue by remember(intervalMs) {
        mutableFloatStateOf(intervalMs.toFloat())
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyanAccent.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Click Interval",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatInterval(sliderValue.toLong()),
                    style = MaterialTheme.typography.titleMedium,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(12.dp))

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onIntervalChange(sliderValue.toLong()) },
                valueRange = ClickConfig.MIN_INTERVAL_MS.toFloat()..ClickConfig.MAX_INTERVAL_MS.toFloat(),
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = CyanAccent,
                    activeTrackColor = CyanAccent,
                    inactiveTrackColor = DarkSurfaceVariant
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("50ms", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("10s", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}

@Composable
fun RepeatCard(
    repeatCount: Int,
    onRepeatChange: (Int) -> Unit
) {
    val isInfinite = repeatCount == ClickConfig.INFINITE
    var count by remember(repeatCount) {
        mutableIntStateOf(if (isInfinite) 100 else repeatCount)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyanAccent.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Repeat",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))

                Text(
                    text = "Infinite",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isInfinite) CyanAccent else TextMuted
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = isInfinite,
                    onCheckedChange = { checked ->
                        if (checked) {
                            onRepeatChange(ClickConfig.INFINITE)
                        } else {
                            onRepeatChange(count)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyanAccent,
                        checkedTrackColor = CyanAccent.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DarkSurfaceVariant
                    )
                )
            }

            AnimatedVisibility(
                visible = !isInfinite,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(200))
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Slider(
                            value = count.toFloat(),
                            onValueChange = { count = it.toInt() },
                            onValueChangeFinished = { onRepeatChange(count) },
                            valueRange = 1f..1000f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = CyanAccent,
                                activeTrackColor = CyanAccent,
                                inactiveTrackColor = DarkSurfaceVariant
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "$count×",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StartStopButton(
    isRunning: Boolean,
    isEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = if (isRunning) RedAccent else CyanAccent,
        animationSpec = tween(300),
        label = "buttonColor"
    )

    Button(
        onClick = {
            if (isRunning) onStop() else onStart()
        },
        enabled = isEnabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            contentColor = Color.White,
            disabledContainerColor = DarkSurfaceVariant,
            disabledContentColor = TextMuted
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                elevation = if (isEnabled) 8.dp else 0.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = buttonColor.copy(alpha = 0.3f),
                spotColor = buttonColor.copy(alpha = 0.3f)
            )
    ) {
        Icon(
            if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isRunning) "Stop Auto Clicker" else "Start Auto Clicker",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Utilities
// ═══════════════════════════════════════════════════════════════════════════

private fun formatInterval(ms: Long): String {
    return when {
        ms >= 1000 -> "${ms / 1000f}s"
        else -> "${ms}ms"
    }
}

/**
 * Check if our accessibility service is enabled in device settings.
 */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName = "${context.packageName}/${AutoClickerAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(serviceName, ignoreCase = true)) {
            return true
        }
    }
    return false
}
