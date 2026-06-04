package com.autoclicker.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository that persists click configuration using SharedPreferences
 * and exposes it as a reactive StateFlow.
 */
class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<ClickConfig> = _config.asStateFlow()

    /**
     * Update and persist the click configuration.
     */
    fun updateConfig(newConfig: ClickConfig) {
        prefs.edit()
            .putFloat(KEY_X, newConfig.x)
            .putFloat(KEY_Y, newConfig.y)
            .putLong(KEY_INTERVAL, newConfig.intervalMs)
            .putInt(KEY_REPEAT, newConfig.repeatCount)
            .putInt(KEY_LAYOUT_TYPE, newConfig.layoutType.ordinal)
            .putFloat(KEY_GRID_SCALE_X, newConfig.gridScaleX)
            .putFloat(KEY_GRID_SCALE_Y, newConfig.gridScaleY)
            .putFloat(KEY_GRID_OFFSET_X, newConfig.gridOffsetX)
            .putFloat(KEY_GRID_OFFSET_Y, newConfig.gridOffsetY)
            .apply()
        _config.value = newConfig
    }

    /**
     * Update only the click position.
     */
    fun updatePosition(x: Float, y: Float) {
        val current = _config.value
        updateConfig(current.copy(x = x, y = y))
    }

    /**
     * Update only the click interval.
     */
    fun updateInterval(intervalMs: Long) {
        val current = _config.value
        updateConfig(current.copy(intervalMs = intervalMs.coerceIn(
            ClickConfig.MIN_INTERVAL_MS,
            ClickConfig.MAX_INTERVAL_MS
        )))
    }

    /**
     * Update only the repeat count.
     */
    fun updateRepeatCount(count: Int) {
        val current = _config.value
        updateConfig(current.copy(repeatCount = count))
    }

    /**
     * Update the selected layout type.
     */
    fun updateLayoutType(layoutType: LayoutType) {
        val current = _config.value
        updateConfig(current.copy(layoutType = layoutType))
    }

    /**
     * Update the horizontal grid scale factor.
     */
    fun updateGridScaleX(scaleX: Float) {
        val current = _config.value
        updateConfig(current.copy(gridScaleX = scaleX.coerceIn(
            ClickConfig.MIN_GRID_SCALE,
            ClickConfig.MAX_GRID_SCALE
        )))
    }

    /**
     * Update the vertical grid scale factor.
     */
    fun updateGridScaleY(scaleY: Float) {
        val current = _config.value
        updateConfig(current.copy(gridScaleY = scaleY.coerceIn(
            ClickConfig.MIN_GRID_SCALE,
            ClickConfig.MAX_GRID_SCALE
        )))
    }

    /**
     * Update the grid offset position.
     */
    fun updateGridOffset(offsetX: Float, offsetY: Float) {
        val current = _config.value
        updateConfig(current.copy(gridOffsetX = offsetX, gridOffsetY = offsetY))
    }

    private fun loadConfig(): ClickConfig {
        return ClickConfig(
            x = prefs.getFloat(KEY_X, 540f),
            y = prefs.getFloat(KEY_Y, 960f),
            intervalMs = prefs.getLong(KEY_INTERVAL, 1000L),
            repeatCount = prefs.getInt(KEY_REPEAT, ClickConfig.INFINITE),
            layoutType = LayoutType.fromOrdinal(prefs.getInt(KEY_LAYOUT_TYPE, 0)),
            gridScaleX = prefs.getFloat(KEY_GRID_SCALE_X, 1.0f),
            gridScaleY = prefs.getFloat(KEY_GRID_SCALE_Y, 1.0f),
            gridOffsetX = prefs.getFloat(KEY_GRID_OFFSET_X, 0f),
            gridOffsetY = prefs.getFloat(KEY_GRID_OFFSET_Y, 400f)
        )
    }

    companion object {
        private const val PREFS_NAME = "auto_clicker_config"
        private const val KEY_X = "click_x"
        private const val KEY_Y = "click_y"
        private const val KEY_INTERVAL = "click_interval"
        private const val KEY_REPEAT = "click_repeat"
        private const val KEY_LAYOUT_TYPE = "layout_type"
        private const val KEY_GRID_SCALE_X = "grid_scale_x"
        private const val KEY_GRID_SCALE_Y = "grid_scale_y"
        private const val KEY_GRID_OFFSET_X = "grid_offset_x"
        private const val KEY_GRID_OFFSET_Y = "grid_offset_y"

        @Volatile
        private var INSTANCE: ConfigRepository? = null

        fun getInstance(context: Context): ConfigRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}


