package com.autoclicker.app.data

/**
 * Immutable data class holding all user-configurable settings for the auto clicker.
 *
 * This is the single source of truth for the overlay's state. It is persisted
 * to SharedPreferences via [ConfigRepository] and observed as a [StateFlow]
 * so both the Compose UI and the floating overlay service stay in sync.
 *
 * @param x              Default click x-coordinate in screen pixels (used in legacy single-click mode).
 * @param y              Default click y-coordinate in screen pixels (used in legacy single-click mode).
 * @param layoutType     The active piano key layout preset (5-col, 7-col, or 7-col + sharps).
 * @param gridScaleX     Horizontal spacing multiplier for the marker grid (1.0 = default).
 * @param gridScaleY     Vertical spacing multiplier for the marker grid (1.0 = default).
 * @param gridOffsetX    Pixel offset of the grid's left edge from the screen's left edge.
 * @param gridOffsetY    Pixel offset of the grid's top edge from the screen's top edge.
 * @param midiFileUri    Content URI string of the loaded MIDI file, or null if none loaded.
 * @param midiFileName   Display name of the loaded MIDI file, or null if none loaded.
 * @param midiSpeedMultiplier  Playback speed multiplier for MIDI (1.0 = normal tempo).
 * @param pitchTransposition   Pitch transposition in semitones (-24 to +24).
 */
data class ClickConfig(
    val x: Float = 540f,
    val y: Float = 960f,
    val layoutType: LayoutType = LayoutType.LAYOUT_5COL,
    val gridScaleX: Float = 1.0f,
    val gridScaleY: Float = 1.0f,
    val gridOffsetX: Float = 0f,
    val gridOffsetY: Float = 400f,
    val midiFileUri: String? = null,
    val midiFileName: String? = null,
    val midiSpeedMultiplier: Float = 1.0f,
    val pitchTransposition: Int = 0
) {
    companion object {
        /** Minimum allowed value for [gridScaleX] and [gridScaleY]. */
        const val MIN_GRID_SCALE = 0.3f
        /** Maximum allowed value for [gridScaleX] and [gridScaleY]. */
        const val MAX_GRID_SCALE = 3.0f
        /** Minimum allowed value for [midiSpeedMultiplier] (quarter speed). */
        const val MIN_MIDI_SPEED = 0.25f
        /** Maximum allowed value for [midiSpeedMultiplier] (triple speed). */
        const val MAX_MIDI_SPEED = 3.0f
    }
}
