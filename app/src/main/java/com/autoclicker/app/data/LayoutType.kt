package com.autoclicker.app.data

/**
 * A single marker position within a layout grid.
 *
 * @param relativeX Horizontal position (0.0 = left edge, 1.0 = right edge of grid)
 * @param relativeY Vertical position (0.0 = top edge, 1.0 = bottom edge of grid)
 * @param label Display label for the marker (e.g., "1\nDO")
 * @param isSharp Whether this is a sharp/flat (dark) key
 */
data class MarkerPosition(
    val relativeX: Float,
    val relativeY: Float,
    val label: String,
    val isSharp: Boolean = false
)

/**
 * Enum defining the three piano button layout presets.
 * Each layout generates a list of [MarkerPosition] representing the
 * normalized positions of all buttons within the grid.
 */
enum class LayoutType(val displayName: String, val description: String) {
    LAYOUT_5COL(
        displayName = "5-Column",
        description = "15 keys — 3 rows × 5 columns"
    ) {
        override fun generateMarkers(): List<MarkerPosition> {
            val markers = mutableListOf<MarkerPosition>()
            val noteNames = arrayOf("DO", "RE", "MI", "FA", "SOL", "LA", "SI")

            // 3 rows × 5 columns = 15 buttons
            // Octave progression: lower → middle → upper
            val cols = 5
            val rows = 3

            // Row 0: 1(DO) 2(RE) 3(MI) 4(FA) 5(SOL)
            // Row 1: 6(LA) 7(SI) 1̇(DO) 2̇(RE) 3̇(MI)
            // Row 2: 4̇(FA) 5̇(SOL) 6̇(LA) 7̇(SI) 1̈(DO)
            val labels = listOf(
                "1\nDO", "2\nRE", "3\nMI", "4\nFA", "5\nSOL",
                "6\nLA", "7\nSI", "1̇\nDO", "2̇\nRE", "3̇\nMI",
                "4̇\nFA", "5̇\nSOL", "6̇\nLA", "7̇\nSI", "1̈\nDO"
            )

            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val index = row * cols + col
                    val x = col.toFloat() / (cols - 1).toFloat()
                    val y = row.toFloat() / (rows - 1).toFloat()
                    markers.add(MarkerPosition(x, y, labels[index]))
                }
            }
            return markers
        }
    },

    LAYOUT_7COL(
        displayName = "7-Column",
        description = "22 keys — 3 rows (8+7+7)"
    ) {
        override fun generateMarkers(): List<MarkerPosition> {
            return generateWhiteKeys()
        }
    },

    LAYOUT_7COL_SHARPS(
        displayName = "7-Col + Sharps",
        description = "22 white + 15 black keys"
    ) {
        override fun generateMarkers(): List<MarkerPosition> {
            val markers = generateWhiteKeys().toMutableList()

            // Add sharp/flat (dark) keys between certain white keys.
            // Standard piano pattern: sharps between 1-2, 2-3, 4-5, 5-6, 6-7
            // That's between note indices: 0-1, 1-2, 3-4, 4-5, 5-6 (0-based)
            val sharpOffsets = listOf(0, 1, 3, 4, 5) // which white key gaps get a sharp

            // Row 0: 8 white keys — sharps between specific pairs
            val row0SharpPositions = listOf(0, 1, 3, 4, 5) // between white keys 0-1, 1-2, 3-4, 4-5, 5-6
            for (si in row0SharpPositions) {
                val x1 = si.toFloat() / 7f
                val x2 = (si + 1).toFloat() / 7f
                val x = (x1 + x2) / 2f
                val y = 0f - 0.08f // slightly above the row
                markers.add(MarkerPosition(x, y, "#", isSharp = true))
            }

            // Row 1: 7 white keys — sharps in standard positions
            for (si in sharpOffsets) {
                val x1 = si.toFloat() / 6f
                val x2 = (si + 1).toFloat() / 6f
                val x = (x1 + x2) / 2f
                val y = 0.5f - 0.08f
                markers.add(MarkerPosition(x, y, "#", isSharp = true))
            }

            // Row 2: 7 white keys — sharps in standard positions
            for (si in sharpOffsets) {
                val x1 = si.toFloat() / 6f
                val x2 = (si + 1).toFloat() / 6f
                val x = (x1 + x2) / 2f
                val y = 1.0f - 0.08f
                markers.add(MarkerPosition(x, y, "#", isSharp = true))
            }

            return markers
        }
    };

    abstract fun generateMarkers(): List<MarkerPosition>

    companion object {
        fun fromOrdinal(ordinal: Int): LayoutType {
            return entries.getOrElse(ordinal) { LAYOUT_5COL }
        }

        /**
         * Generate the white keys shared by LAYOUT_7COL and LAYOUT_7COL_SHARPS.
         * Row 0: 8 keys (higher octave), Row 1: 7 keys (middle), Row 2: 7 keys (lower)
         */
        fun generateWhiteKeys(): List<MarkerPosition> {
            val markers = mutableListOf<MarkerPosition>()

            // Row 0: 8 white keys (higher octave with dots)
            val row0Labels = listOf(
                "1̇\nDO", "2̇\nRE", "3̇\nMI", "4̇\nFA",
                "5̇\nSOL", "6̇\nLA", "7\nSI", "1̈\nDO"
            )
            for (col in 0 until 8) {
                val x = col.toFloat() / 7f
                markers.add(MarkerPosition(x, 0f, row0Labels[col]))
            }

            // Row 1: 7 white keys (middle octave)
            val row1Labels = listOf(
                "1\nDO", "2\nRE", "3\nMI", "4\nFA",
                "5\nSOL", "6\nLA", "7\nSI"
            )
            for (col in 0 until 7) {
                val x = col.toFloat() / 6f
                markers.add(MarkerPosition(x, 0.5f, row1Labels[col]))
            }

            // Row 2: 7 white keys (lower octave)
            val row2Labels = listOf(
                "1\nDO", "2\nRE", "3\nMI", "4\nFA",
                "5\nSOL", "6\nLA", "7\nSI"
            )
            for (col in 0 until 7) {
                val x = col.toFloat() / 6f
                markers.add(MarkerPosition(x, 1f, row2Labels[col]))
            }

            return markers
        }
    }
}
