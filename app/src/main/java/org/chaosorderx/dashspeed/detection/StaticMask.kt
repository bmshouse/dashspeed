package org.chaosorderx.dashspeed.detection

import kotlin.math.max
import kotlin.math.min

/**
 * Coarse grid over the frame marking cells occupied by static scenery (hood, phone mount).
 * Box coordinates are frame-normalised (`0..FRAME_SPACE`), as produced by the detector.
 */
class StaticMask(
    private val cells: BooleanArray = BooleanArray(COLS * ROWS),
) {
    init {
        require(cells.size == COLS * ROWS) { "mask must have ${COLS * ROWS} cells" }
    }

    /** Copy of the cells, row-major (`row * COLS + col`), for editing. */
    fun toCells(): BooleanArray = cells.copyOf()

    val coveredFraction: Float get() = cells.count { it }.toFloat() / cells.size

    fun isStatic(
        col: Int,
        row: Int,
    ): Boolean = cells[row * COLS + col]

    /** Fraction (0..1) of the given box's area that lies on static cells. */
    fun coverage(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Float {
        val area = (right - left) * (bottom - top)
        if (area <= 0f) return 0f
        val cellW = ModelConfig.FRAME_SPACE.toFloat() / COLS
        val cellH = ModelConfig.FRAME_SPACE.toFloat() / ROWS
        var covered = 0f
        val c0 = (left / cellW).toInt().coerceIn(0, COLS - 1)
        val c1 = (right / cellW).toInt().coerceIn(0, COLS - 1)
        val r0 = (top / cellH).toInt().coerceIn(0, ROWS - 1)
        val r1 = (bottom / cellH).toInt().coerceIn(0, ROWS - 1)
        for (row in r0..r1) {
            for (col in c0..c1) {
                if (!cells[row * COLS + col]) continue
                val w = min(right, (col + 1) * cellW) - max(left, col * cellW)
                val h = min(bottom, (row + 1) * cellH) - max(top, row * cellH)
                if (w > 0f && h > 0f) covered += w * h
            }
        }
        return covered / area
    }

    /** A copy with every cell whose centre lies inside the given box (frame-normalised) marked static. */
    fun withRegion(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): StaticMask {
        val cellW = ModelConfig.FRAME_SPACE.toFloat() / COLS
        val cellH = ModelConfig.FRAME_SPACE.toFloat() / ROWS
        val merged = cells.copyOf()
        for (row in 0 until ROWS) {
            val cy = (row + 0.5f) * cellH
            for (col in 0 until COLS) {
                val cx = (col + 0.5f) * cellW
                if (cx in left..right && cy in top..bottom) merged[row * COLS + col] = true
            }
        }
        return StaticMask(merged)
    }

    /** `'0'`/`'1'` per cell, row-major — the form persisted in DataStore. */
    fun serialize(): String = String(CharArray(cells.size) { if (cells[it]) '1' else '0' })

    companion object {
        const val COLS = 32
        const val ROWS = 24

        /** Returns null if [bits] is not a valid serialised mask (e.g. empty = never learned). */
        fun deserialize(bits: String): StaticMask? =
            if (bits.length == COLS * ROWS && bits.all { it == '0' || it == '1' }) {
                StaticMask(BooleanArray(bits.length) { bits[it] == '1' })
            } else {
                null
            }
    }
}
