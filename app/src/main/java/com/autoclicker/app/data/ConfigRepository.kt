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
            .putInt(KEY_LAYOUT_TYPE, newConfig.layoutType.ordinal)
            .putFloat(KEY_GRID_SCALE_X, newConfig.gridScaleX)
            .putFloat(KEY_GRID_SCALE_Y, newConfig.gridScaleY)
            .putFloat(KEY_GRID_OFFSET_X, newConfig.gridOffsetX)
            .putFloat(KEY_GRID_OFFSET_Y, newConfig.gridOffsetY)
            .putFloat(KEY_MIDI_SPEED, newConfig.midiSpeedMultiplier)
            .putInt(KEY_PITCH_TRANSPOSITION, newConfig.pitchTransposition)
            .apply {
                if (newConfig.midiFileUri != null) {
                    putString(KEY_MIDI_URI, newConfig.midiFileUri)
                    putString(KEY_MIDI_NAME, newConfig.midiFileName)
                } else {
                    remove(KEY_MIDI_URI)
                    remove(KEY_MIDI_NAME)
                }
            }
            .apply()
        _config.value = newConfig
    }

    fun updatePosition(x: Float, y: Float) {
        val current = _config.value
        updateConfig(current.copy(x = x, y = y))
    }


    fun updateLayoutType(layoutType: LayoutType) {
        val current = _config.value
        updateConfig(current.copy(layoutType = layoutType))
    }

    fun updateGridScaleX(scaleX: Float) {
        val current = _config.value
        updateConfig(current.copy(gridScaleX = scaleX.coerceIn(
            ClickConfig.MIN_GRID_SCALE,
            ClickConfig.MAX_GRID_SCALE
        )))
    }

    fun updateGridScaleY(scaleY: Float) {
        val current = _config.value
        updateConfig(current.copy(gridScaleY = scaleY.coerceIn(
            ClickConfig.MIN_GRID_SCALE,
            ClickConfig.MAX_GRID_SCALE
        )))
    }

    fun updateGridOffset(offsetX: Float, offsetY: Float) {
        val current = _config.value
        updateConfig(current.copy(gridOffsetX = offsetX, gridOffsetY = offsetY))
    }

    fun updateMidiFile(uri: String?, fileName: String?) {
        val current = _config.value
        updateConfig(current.copy(midiFileUri = uri, midiFileName = fileName))
    }

    fun updateMidiSpeed(speed: Float) {
        val current = _config.value
        updateConfig(current.copy(midiSpeedMultiplier = speed.coerceIn(
            ClickConfig.MIN_MIDI_SPEED,
            ClickConfig.MAX_MIDI_SPEED
        )))
    }

    fun updatePitchTransposition(amount: Int) {
        val current = _config.value
        updateConfig(current.copy(pitchTransposition = amount.coerceIn(-24, 24)))
    }

    /**
     * Load the persisted configuration from SharedPreferences.
     * Called once during construction to hydrate the initial [_config] state.
     */
    private fun loadConfig(): ClickConfig {
        return ClickConfig(
            x = prefs.getFloat(KEY_X, 540f),
            y = prefs.getFloat(KEY_Y, 960f),
            layoutType = LayoutType.fromOrdinal(prefs.getInt(KEY_LAYOUT_TYPE, 0)),
            gridScaleX = prefs.getFloat(KEY_GRID_SCALE_X, 1.0f),
            gridScaleY = prefs.getFloat(KEY_GRID_SCALE_Y, 1.0f),
            gridOffsetX = prefs.getFloat(KEY_GRID_OFFSET_X, 0f),
            gridOffsetY = prefs.getFloat(KEY_GRID_OFFSET_Y, 400f),
            midiFileUri = prefs.getString(KEY_MIDI_URI, null),
            midiFileName = prefs.getString(KEY_MIDI_NAME, null),
            midiSpeedMultiplier = prefs.getFloat(KEY_MIDI_SPEED, 1.0f),
            pitchTransposition = prefs.getInt(KEY_PITCH_TRANSPOSITION, 0)
        )
    }

    companion object {
        // ─── SharedPreferences file name and key constants ────────────
        private const val PREFS_NAME = "auto_clicker_config"
        private const val KEY_X = "click_x"
        private const val KEY_Y = "click_y"
        private const val KEY_LAYOUT_TYPE = "layout_type"
        private const val KEY_GRID_SCALE_X = "grid_scale_x"
        private const val KEY_GRID_SCALE_Y = "grid_scale_y"
        private const val KEY_GRID_OFFSET_X = "grid_offset_x"
        private const val KEY_GRID_OFFSET_Y = "grid_offset_y"
        private const val KEY_MIDI_URI = "midi_file_uri"
        private const val KEY_MIDI_NAME = "midi_file_name"
        private const val KEY_MIDI_SPEED = "midi_speed"
        private const val KEY_PITCH_TRANSPOSITION = "pitch_transposition"

        /**
         * Thread-safe singleton instance.
         * Uses double-checked locking to ensure only one repository is created.
         */
        @Volatile
        private var INSTANCE: ConfigRepository? = null

        /**
         * Get (or lazily create) the singleton [ConfigRepository].
         * Always uses [Context.getApplicationContext] to avoid leaking Activities.
         */
        fun getInstance(context: Context): ConfigRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}


