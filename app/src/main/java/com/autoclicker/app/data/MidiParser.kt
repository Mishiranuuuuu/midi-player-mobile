package com.autoclicker.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream

/**
 * A single MIDI note event (note-on or note-off) with absolute time.
 */
data class MidiNote(
    val note: Int,          // MIDI note number (0-127)
    val velocity: Int,      // 0 = note off
    val timeMs: Long,       // Absolute time from start in milliseconds
    val isNoteOn: Boolean   // true = press, false = release
)

/**
 * A parsed MIDI song ready for playback.
 */
data class MidiSong(
    val name: String,
    val notes: List<MidiNote>,
    val durationMs: Long,
    val noteOnCount: Int
)

/**
 * Pure-Kotlin parser for Standard MIDI Files (SMF).
 *
 * Supports Format 0 (single track) and Format 1 (multi-track).
 * Extracts note-on / note-off events with absolute timing in milliseconds.
 * No external dependencies required — MIDI is a simple binary format.
 */
object MidiParser {

    private const val TAG = "MidiParser"

    /**
     * Parse a MIDI file from a content URI.
     *
     * @param context Android context for content resolver
     * @param uri Content URI of the MIDI file
     * @param fileName Display name for the song
     * @return Parsed MidiSong, or null on failure
     */
    fun parse(context: Context, uri: Uri, fileName: String): MidiSong? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parse(stream, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open MIDI file: $e")
            null
        }
    }

    /**
     * Parse a MIDI file from an InputStream.
     */
    fun parse(inputStream: InputStream, fileName: String): MidiSong? {
        return try {
            val data = inputStream.readBytes()
            parseBytes(data, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse MIDI: $e")
            null
        }
    }

    /**
     * Parse MIDI file bytes into a MidiSong.
     */
    private fun parseBytes(data: ByteArray, fileName: String): MidiSong? {
        if (data.size < 14) {
            Log.e(TAG, "File too small to be a valid MIDI file")
            return null
        }

        var pos = 0

        // ─── Header Chunk ────────────────────────────────────────────
        val headerTag = String(data, pos, 4)
        if (headerTag != "MThd") {
            Log.e(TAG, "Invalid MIDI header: $headerTag")
            return null
        }
        pos += 4

        val headerLength = readInt32(data, pos)
        pos += 4

        val format = readInt16(data, pos)
        pos += 2
        val trackCount = readInt16(data, pos)
        pos += 2
        val division = readInt16(data, pos)
        pos += 2

        // Skip any extra header bytes
        pos += (headerLength - 6).coerceAtLeast(0)

        Log.d(TAG, "MIDI Format=$format, Tracks=$trackCount, Division=$division")

        if (division and 0x8000 != 0) {
            // SMPTE-based timing — rarely used, not supported
            Log.e(TAG, "SMPTE timing not supported")
            return null
        }

        val ticksPerBeat = division

        // ─── Track Chunks ────────────────────────────────────────────
        // Collect raw events (tick-based) from all tracks
        val allEvents = mutableListOf<RawMidiEvent>()

        for (trackIdx in 0 until trackCount) {
            if (pos + 8 > data.size) break

            val trackTag = String(data, pos, 4)
            pos += 4
            val trackLength = readInt32(data, pos)
            pos += 4

            if (trackTag != "MTrk") {
                Log.w(TAG, "Skipping non-track chunk: $trackTag")
                pos += trackLength
                continue
            }

            val trackEnd = pos + trackLength
            var absoluteTick: Long = 0
            var runningStatus = 0

            while (pos < trackEnd && pos < data.size) {
                // Read delta time (variable-length quantity)
                val (deltaTime, bytesRead) = readVLQ(data, pos)
                pos += bytesRead
                absoluteTick += deltaTime

                if (pos >= data.size) break

                var statusByte = data[pos].toInt() and 0xFF

                if (statusByte < 0x80) {
                    // Running status: reuse previous status byte
                    statusByte = runningStatus
                } else {
                    pos++
                    if (statusByte < 0xF0) {
                        runningStatus = statusByte
                    }
                }

                val eventType = statusByte and 0xF0

                when {
                    // Note Off (0x8n)
                    eventType == 0x80 -> {
                        if (pos + 1 >= data.size) break
                        val note = data[pos].toInt() and 0x7F
                        pos++
                        pos++ // velocity (unused for note-off)
                        allEvents.add(RawMidiEvent(absoluteTick, note, 0, false, trackIdx))
                    }

                    // Note On (0x9n)
                    eventType == 0x90 -> {
                        if (pos + 1 >= data.size) break
                        val note = data[pos].toInt() and 0x7F
                        pos++
                        val velocity = data[pos].toInt() and 0x7F
                        pos++
                        // velocity == 0 is treated as note-off
                        val isOn = velocity > 0
                        allEvents.add(RawMidiEvent(absoluteTick, note, velocity, isOn, trackIdx))
                    }

                    // Polyphonic Aftertouch (0xAn) — 2 data bytes, skip
                    eventType == 0xA0 -> { pos += 2 }

                    // Control Change (0xBn) — 2 data bytes, skip
                    eventType == 0xB0 -> { pos += 2 }

                    // Program Change (0xCn) — 1 data byte, skip
                    eventType == 0xC0 -> { pos += 1 }

                    // Channel Aftertouch (0xDn) — 1 data byte, skip
                    eventType == 0xD0 -> { pos += 1 }

                    // Pitch Bend (0xEn) — 2 data bytes, skip
                    eventType == 0xE0 -> { pos += 2 }

                    // Meta Event (0xFF)
                    statusByte == 0xFF -> {
                        if (pos >= data.size) break
                        val metaType = data[pos].toInt() and 0xFF
                        pos++
                        val (metaLength, metaLenBytes) = readVLQ(data, pos)
                        pos += metaLenBytes

                        if (metaType == 0x51 && metaLength == 3L) {
                            // Tempo change: 3 bytes = microseconds per beat
                            if (pos + 2 < data.size) {
                                val usPerBeat = ((data[pos].toInt() and 0xFF) shl 16) or
                                        ((data[pos + 1].toInt() and 0xFF) shl 8) or
                                        (data[pos + 2].toInt() and 0xFF)
                                allEvents.add(RawMidiEvent(
                                    absoluteTick, 0, 0, false, trackIdx,
                                    isTempo = true, tempo = usPerBeat
                                ))
                            }
                        }
                        pos += metaLength.toInt()
                    }

                    // SysEx (0xF0, 0xF7)
                    statusByte == 0xF0 || statusByte == 0xF7 -> {
                        val (sysexLen, sysexLenBytes) = readVLQ(data, pos)
                        pos += sysexLenBytes + sysexLen.toInt()
                    }

                    else -> {
                        // Unknown event — try to skip gracefully
                        Log.w(TAG, "Unknown MIDI event: 0x${statusByte.toString(16)} at pos $pos")
                    }
                }
            }

            // Make sure we advance to the end of the track chunk
            pos = trackEnd.coerceAtMost(data.size)
        }

        // ─── Convert ticks → milliseconds ────────────────────────────
        // Sort all events by absolute tick
        allEvents.sortWith(compareBy({ it.absoluteTick }, { if (it.isTempo) 0 else 1 }))

        val midiNotes = mutableListOf<MidiNote>()
        var currentTempo = 500_000 // Default: 120 BPM = 500,000 µs/beat
        var lastTick: Long = 0
        var lastTimeMs: Double = 0.0

        for (event in allEvents) {
            // Calculate time elapsed since last event
            val deltaTicks = event.absoluteTick - lastTick
            if (deltaTicks > 0) {
                val deltaMs = ticksToMs(deltaTicks, currentTempo, ticksPerBeat)
                lastTimeMs += deltaMs
            }
            lastTick = event.absoluteTick

            if (event.isTempo) {
                currentTempo = event.tempo
            } else {
                midiNotes.add(MidiNote(
                    note = event.note,
                    velocity = event.velocity,
                    timeMs = lastTimeMs.toLong(),
                    isNoteOn = event.isNoteOn
                ))
            }
        }

        val noteOnCount = midiNotes.count { it.isNoteOn }
        val durationMs = if (midiNotes.isNotEmpty()) midiNotes.last().timeMs else 0L

        Log.d(TAG, "Parsed $noteOnCount note-on events, duration=${durationMs}ms")

        return MidiSong(
            name = fileName,
            notes = midiNotes,
            durationMs = durationMs,
            noteOnCount = noteOnCount
        )
    }

    // ─── Internal helpers ────────────────────────────────────────────────

    private data class RawMidiEvent(
        val absoluteTick: Long,
        val note: Int,
        val velocity: Int,
        val isNoteOn: Boolean,
        val track: Int,
        val isTempo: Boolean = false,
        val tempo: Int = 0
    )

    /**
     * Read a variable-length quantity (VLQ) from the byte array.
     * Returns (value, number of bytes consumed).
     */
    private fun readVLQ(data: ByteArray, offset: Int): Pair<Long, Int> {
        var value: Long = 0
        var bytesRead = 0
        var pos = offset

        while (pos < data.size) {
            val byte = data[pos].toInt() and 0xFF
            pos++
            bytesRead++
            value = (value shl 7) or (byte.toLong() and 0x7F)

            if (byte and 0x80 == 0) break  // Last byte of VLQ
            if (bytesRead >= 4) break       // Safety limit
        }

        return Pair(value, bytesRead)
    }

    /**
     * Read a big-endian 32-bit integer.
     */
    private fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    /**
     * Read a big-endian 16-bit integer.
     */
    private fun readInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
    }

    /**
     * Convert MIDI ticks to milliseconds given a tempo and resolution.
     */
    private fun ticksToMs(ticks: Long, usPerBeat: Int, ticksPerBeat: Int): Double {
        if (ticksPerBeat == 0) return 0.0
        return (ticks.toDouble() * usPerBeat.toDouble()) / (ticksPerBeat.toDouble() * 1000.0)
    }
}
