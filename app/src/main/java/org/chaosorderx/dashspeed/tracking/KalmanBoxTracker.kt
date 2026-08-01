package org.chaosorderx.dashspeed.tracking

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs

// Kalman filter dimensions
private const val STATE_DIM = 8 // [cx, cy, w, h, vx, vy, vw, vh]
private const val MEAS_DIM = 4 // [cx, cy, w, h]

// Position samples retained per track for windowed speed estimation (~0.3–0.5 s at 20–30 fps).
private const val HISTORY_SIZE = 10

// Tuning constants — adjust these to trade off smoothness vs. responsiveness
private const val INIT_P_POS = 10f // initial position-state covariance
private const val INIT_P_VEL = 1_000f // initial velocity-state covariance (high = uncertain)
private const val Q_POS_NOISE = 1f // process noise — position components
private const val Q_VEL_NOISE = 0.01f // process noise — velocity components
private const val R_DIAG = 1f // measurement noise (diagonal)

private typealias Mat = Array<FloatArray>

private fun zeros(
    rows: Int,
    cols: Int,
): Mat = Array(rows) { FloatArray(cols) }

private fun matMul(
    a: Mat,
    b: Mat,
): Mat {
    val m = a.size
    val k = a[0].size
    val n = b[0].size
    val result = zeros(m, n)
    for (i in 0 until m) {
        for (l in 0 until k) {
            for (j in 0 until n) {
                result[i][j] += a[i][l] * b[l][j]
            }
        }
    }
    return result
}

private fun matVecMul(
    a: Mat,
    v: FloatArray,
): FloatArray {
    val m = a.size
    val k = a[0].size
    val result = FloatArray(m)
    for (i in 0 until m) {
        for (j in 0 until k) {
            result[i] += a[i][j] * v[j]
        }
    }
    return result
}

private fun transpose(a: Mat): Mat = Array(a[0].size) { j -> FloatArray(a.size) { i -> a[i][j] } }

private fun matAdd(
    a: Mat,
    b: Mat,
): Mat = Array(a.size) { i -> FloatArray(a[0].size) { j -> a[i][j] + b[i][j] } }

// Gauss-Jordan inverse with partial pivoting (safe for the 4×4 positive-definite S matrix).
// Returns null if S is singular — update() will skip the Kalman correction that frame.
@Suppress("ComplexMethod")
private fun inverse(a: Mat): Mat? {
    val n = a.size
    val aug =
        Array(n) { i ->
            FloatArray(2 * n) { j ->
                when {
                    j < n -> a[i][j]
                    j - n == i -> 1f
                    else -> 0f
                }
            }
        }
    for (col in 0 until n) {
        var maxRow = col
        for (row in col + 1 until n) {
            if (abs(aug[row][col]) > abs(aug[maxRow][col])) maxRow = row
        }
        val tmp = aug[col]
        aug[col] = aug[maxRow]
        aug[maxRow] = tmp
        val pivot = aug[col][col]
        if (pivot == 0f) return null
        for (j in 0 until 2 * n) aug[col][j] /= pivot
        for (row in 0 until n) {
            if (row == col) continue
            val factor = aug[row][col]
            for (j in 0 until 2 * n) aug[row][j] -= factor * aug[col][j]
        }
    }
    return Array(n) { i -> FloatArray(n) { j -> aug[i][j + n] } }
}

/**
 * Tracks a single detected vehicle using a constant-velocity Kalman filter.
 *
 * State vector: [cx, cy, w, h, vx, vy, vw, vh] — box dimensions carry velocity states too,
 * so the bottom edge (cy + h/2) tracks the rapid shape changes of oblique adjacent-lane
 * views instead of lagging them.
 * Measurement:  [cx, cy, w, h]
 */
class KalmanBoxTracker(initialBox: RectF, val id: Int) {
    var age: Int = 0
    var hitStreak: Int = 0
    var timeSinceUpdate: Int = 0

    private val historyBuffer = ArrayDeque<PositionSample>(HISTORY_SIZE)
    val history: List<PositionSample> get() = historyBuffer.toList()

    private var x: FloatArray
    private var p: Mat

    // State transition — identity with cx += vx and cy += vy coupling
    private val f: Mat

    // Observation — selects first MEAS_DIM components of state
    private val h: Mat = Array(MEAS_DIM) { i -> FloatArray(STATE_DIM) { j -> if (i == j) 1f else 0f } }

    // Process noise — diagonal; lower uncertainty on position than velocity
    private val q: Mat =
        Array(STATE_DIM) { i ->
            FloatArray(STATE_DIM) { j ->
                if (i != j) {
                    0f
                } else if (i < MEAS_DIM) {
                    Q_POS_NOISE
                } else {
                    Q_VEL_NOISE
                }
            }
        }

    // Measurement noise — diagonal identity scaled by R_DIAG
    private val r: Mat = Array(MEAS_DIM) { i -> FloatArray(MEAS_DIM) { j -> if (i == j) R_DIAG else 0f } }

    init {
        x =
            floatArrayOf(
                (initialBox.left + initialBox.right) / 2f,
                (initialBox.top + initialBox.bottom) / 2f,
                initialBox.right - initialBox.left,
                initialBox.bottom - initialBox.top,
                0f,
                0f,
                0f,
                0f,
            )
        p =
            Array(STATE_DIM) { i ->
                FloatArray(STATE_DIM) { j ->
                    if (i != j) {
                        0f
                    } else if (i < MEAS_DIM) {
                        INIT_P_POS
                    } else {
                        INIT_P_VEL
                    }
                }
            }
        val fInit = Array(STATE_DIM) { i -> FloatArray(STATE_DIM) { j -> if (i == j) 1f else 0f } }
        fInit[0][4] = 1f // cx += vx each frame
        fInit[1][5] = 1f // cy += vy each frame
        fInit[2][6] = 1f // w += vw each frame
        fInit[3][7] = 1f // h += vh each frame
        f = fInit
    }

    /** Advances the Kalman state by one frame and returns the predicted box. */
    fun predict(): RectF {
        // Reset hitStreak when the track was already unmatched last frame (classic SORT behaviour).
        // A timeSinceUpdate of 0 means the track was matched last frame — don't reset there.
        if (timeSinceUpdate > 0) hitStreak = 0
        x = matVecMul(f, x)
        p = matAdd(matMul(matMul(f, p), transpose(f)), q)
        timeSinceUpdate++
        age++
        return stateToBox()
    }

    /**
     * Corrects the state with a new measurement.
     *
     * [frameTimestampMs] must come from the camera frame's capture timestamp, not the wall
     * clock at processing time — inference and scheduling jitter would otherwise corrupt the
     * time deltas the speed estimator divides by.
     */
    fun update(
        box: RectF,
        frameTimestampMs: Long,
    ) {
        // z = measured [cx, cy, w, h]; innovation y = z - H @ x (H selects first 4 components)
        val z =
            floatArrayOf(
                (box.left + box.right) / 2f,
                (box.top + box.bottom) / 2f,
                box.right - box.left,
                box.bottom - box.top,
            )
        val y = FloatArray(MEAS_DIM) { i -> z[i] - x[i] }

        // S = H P H^T + R  (innovation covariance)
        val hp = matMul(h, p)
        val s = matAdd(matMul(hp, transpose(h)), r)

        // K = P H^T S^{-1}  (Kalman gain)
        val k = matMul(matMul(p, transpose(h)), inverse(s) ?: return)

        // x = x + K y
        val ky = matVecMul(k, y)
        x = FloatArray(STATE_DIM) { i -> x[i] + ky[i] }

        // P = (I - K H) P
        val kh = matMul(k, h)
        val iKh = Array(STATE_DIM) { i -> FloatArray(STATE_DIM) { j -> (if (i == j) 1f else 0f) - kh[i][j] } }
        p = matMul(iKh, p)

        hitStreak++
        timeSinceUpdate = 0

        val corrected = stateToBox()
        val sample =
            PositionSample(
                bottomCenter =
                    PointF().apply {
                        this.x = (corrected.left + corrected.right) / 2f
                        this.y = corrected.bottom
                    },
                timestampMs = frameTimestampMs,
            )
        if (historyBuffer.size >= HISTORY_SIZE) historyBuffer.removeFirst()
        historyBuffer.addLast(sample)
    }

    /** Returns the current estimated box without advancing the filter. */
    fun getState(): RectF = stateToBox()

    private fun stateToBox(): RectF {
        val width = x[2].coerceAtLeast(1f)
        val height = x[3].coerceAtLeast(1f)
        return RectF().apply {
            left = x[0] - width / 2f
            top = x[1] - height / 2f
            right = x[0] + width / 2f
            bottom = x[1] + height / 2f
        }
    }
}
