package com.autoclicker.app.data

import android.util.Log
import com.autoclicker.app.service.AutoClickerAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Current playback state exposed to the UI and overlay.
 */
data class MidiPlaybackState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val progress: Float = 0f,         // 0.0–1.0
    val currentNoteIndex: Int = 0,
    val totalNotes: Int = 0,
    val songName: String = ""
)

/**
 * Coroutine-based engine that plays a [MidiSong] by dispatching taps
 * at the correct screen positions with proper MIDI timing.
 *
 * Flow:
 * 1. The song's note-on events are filtered and sorted by time.
 * 2. For each note, the engine resolves:
 *    MIDI note → marker index (via [NoteMapper]) → screen position (from marker positions)
 * 3. It waits the correct delta time (divided by speed multiplier), then dispatches a click
 *    via the accessibility service.
 *
 * The engine does NOT use the service's own click loop — it drives timing independently.
 */
class MidiPlaybackEngine {

    companion object {
        private const val TAG = "MidiPlayback"

        @Volatile
        private var INSTANCE: MidiPlaybackEngine? = null

        fun getInstance(): MidiPlaybackEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MidiPlaybackEngine().also { INSTANCE = it }
            }
        }
    }

    private val _state = MutableStateFlow(MidiPlaybackState())
    val state: StateFlow<MidiPlaybackState> = _state.asStateFlow()

    private var playbackJob: Job? = null
    private var pauseResumeJob: Job? = null

    // Stored for pause/resume
    private var currentSong: MidiSong? = null
    private var currentLayoutType: LayoutType? = null
    private var currentPositions: List<Pair<Float, Float>>? = null
    private var currentSpeed: Float = 1.0f
    private var currentPitchTransposition: Int = 0
    private var currentOnMarkerClicked: ((Int) -> Unit)? = null
    private var pausedAtIndex: Int = 0

    /**
     * Start playing a MIDI song.
     *
     * @param song The parsed MIDI song to play
     * @param layoutType The current piano layout (for note mapping)
     * @param markerScreenPositions Absolute screen (x, y) for each marker index
     * @param speedMultiplier Playback speed (1.0 = normal, 2.0 = double speed, etc.)
     */
    fun play(
        song: MidiSong,
        layoutType: LayoutType,
        markerScreenPositions: List<Pair<Float, Float>>,
        speedMultiplier: Float = 1.0f,
        pitchTransposition: Int = 0,
        onMarkerClicked: ((Int) -> Unit)? = null
    ) {
        stop() // Stop any existing playback

        currentSong = song
        currentLayoutType = layoutType
        currentPositions = markerScreenPositions
        currentSpeed = speedMultiplier
        currentPitchTransposition = pitchTransposition
        currentOnMarkerClicked = onMarkerClicked

        startPlaybackFrom(0, song, layoutType, markerScreenPositions, speedMultiplier, pitchTransposition, onMarkerClicked)
    }

    /**
     * Pause the current playback.
     */
    fun pause() {
        if (_state.value.isPlaying && !_state.value.isPaused) {
            playbackJob?.cancel()
            _state.value = _state.value.copy(isPaused = true, isPlaying = false)
            Log.d(TAG, "Paused at note index ${_state.value.currentNoteIndex}")
        }
    }

    /**
     * Resume from paused state.
     */
    fun resume() {
        val song = currentSong ?: return
        val layout = currentLayoutType ?: return
        val positions = currentPositions ?: return

        if (_state.value.isPaused) {
            startPlaybackFrom(
                pausedAtIndex,
                song, layout, positions, currentSpeed, currentPitchTransposition, currentOnMarkerClicked
            )
        }
    }

    /**
     * Stop playback completely.
     */
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        pausedAtIndex = 0
        _state.value = MidiPlaybackState()
        Log.d(TAG, "Stopped")
    }

    /**
     * Check if a MIDI song is loaded and playback is active or paused.
     */
    val isActive: Boolean
        get() = _state.value.isPlaying || _state.value.isPaused

    private fun startPlaybackFrom(
        startIndex: Int,
        song: MidiSong,
        layoutType: LayoutType,
        markerScreenPositions: List<Pair<Float, Float>>,
        speedMultiplier: Float,
        pitchTransposition: Int,
        onMarkerClicked: ((Int) -> Unit)?
    ) {
        // Filter to note-on events only (we tap on note-on)
        val noteOnEvents = song.notes.filter { it.isNoteOn }

        if (noteOnEvents.isEmpty()) {
            Log.w(TAG, "No note-on events in song")
            return
        }

        // Pre-calculate durations by finding the corresponding note-off events
        val noteDurations = LongArray(noteOnEvents.size) { 50L }
        for (i in noteOnEvents.indices) {
            val onEvent = noteOnEvents[i]
            // Find the first matching note-off event that happens after this note-on
            val offEvent = song.notes.find { !it.isNoteOn && it.note == onEvent.note && it.timeMs > onEvent.timeMs }
            if (offEvent != null) {
                noteDurations[i] = (offEvent.timeMs - onEvent.timeMs).coerceAtLeast(10L)
            }
        }


        val totalNotes = noteOnEvents.size

        _state.value = MidiPlaybackState(
            isPlaying = true,
            isPaused = false,
            progress = startIndex.toFloat() / totalNotes.toFloat(),
            currentNoteIndex = startIndex,
            totalNotes = totalNotes,
            songName = song.name
        )

        playbackJob = CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "Playing '${song.name}' from note $startIndex/$totalNotes at ${speedMultiplier}x")

            var i = startIndex
            var lastPlayedTimeMs = -1L

            while (i < totalNotes) {
                if (!isActive) break

                val baseNote = noteOnEvents[i]
                val baseNoteDuration = noteDurations[i]
                val chordNotes = mutableListOf(baseNote)
                val chordDurations = mutableListOf(baseNoteDuration)

                // Look ahead for chord notes (notes happening at the exact same time)
                var j = i + 1
                while (j < totalNotes && noteOnEvents[j].timeMs == baseNote.timeMs) {
                    chordNotes.add(noteOnEvents[j])
                    chordDurations.add(noteDurations[j])
                    j++
                }

                // Wait for the correct time delta
                if (lastPlayedTimeMs != -1L) {
                    val deltaMs = baseNote.timeMs - lastPlayedTimeMs
                    if (deltaMs > 0) {
                        val adjustedDelay = (deltaMs / speedMultiplier).toLong()
                        delay(adjustedDelay)
                    }
                } else if (i == 0) {
                    // First note: wait from song start
                    val initialDelay = (baseNote.timeMs / speedMultiplier).toLong()
                    if (initialDelay > 0 && initialDelay < 5000) {
                        delay(initialDelay)
                    }
                }

                if (!isActive) break

                // Collect points for all mapped notes in the chord
                val pointsToClick = mutableListOf<Triple<Float, Float, Long>>()
                for (k in chordNotes.indices) {
                    val note = chordNotes[k]
                    val duration = chordDurations[k]
                    val transposedNote = note.note + pitchTransposition
                    val markerIndex = NoteMapper.getMappedMarkerIndex(transposedNote, layoutType)
                    if (markerIndex >= 0 && markerIndex < markerScreenPositions.size) {
                        val (x, y) = markerScreenPositions[markerIndex]
                        pointsToClick.add(Triple(x, y, (duration / speedMultiplier).toLong()))
                        onMarkerClicked?.invoke(markerIndex)
                    }
                }

                // Dispatch the click(s)
                if (pointsToClick.size == 1) {
                    val (x, y, dur) = pointsToClick[0]
                    AutoClickerAccessibilityService.instance?.performSingleClick(x, y, dur)
                } else if (pointsToClick.size > 1) {
                    AutoClickerAccessibilityService.instance?.performMultiClick(pointsToClick)
                }

                // Update state for the next iteration
                lastPlayedTimeMs = baseNote.timeMs
                pausedAtIndex = j
                i = j

                _state.value = _state.value.copy(
                    currentNoteIndex = i,
                    progress = i.toFloat() / totalNotes.toFloat()
                )
            }

            // Playback finished
            Log.d(TAG, "Finished playing '${song.name}'")
            _state.value = MidiPlaybackState(
                isPlaying = false,
                isPaused = false,
                progress = 1f,
                currentNoteIndex = totalNotes,
                totalNotes = totalNotes,
                songName = song.name
            )
        }
    }
}
