package org.chaosorderx.dashspeed.speed

import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tan

@Singleton
class HomographyCalibrator
    @Inject
    constructor() {
        companion object {
            // Approximate focal length in pixels for a 416px model frame (~60° vertical FOV).
            private const val FOCAL_LENGTH_PX = 360f
            private const val MODEL_CENTER_Y = 208f
            private const val MODEL_INPUT_SIZE = 416f

            // Fallback mpp when no calibration matrix is available.
            // 0.66 m/px at mid-frame matches the unit-test track builders (dt/0.05 = 0.033/0.05).
            internal const val DEFAULT_MPP = 0.66f

            // Rays at or above the horizon (β ≤ 0) have no ground intersection; clamp to ~0.57°
            // so groundDistanceMeters stays finite for boxes hugging the horizon line.
            private const val MIN_BETA_RAD = 0.01f
            private const val MAX_GROUND_DISTANCE_M = 300f
        }

        private data class CalibParams(val h: Float, val tiltRad: Float, val fy: Float)

        // Cache parsed params to avoid re-parsing the same JSON string on every frame.
        private var cachedJson: String? = null
        private var cachedParams: CalibParams? = null

        /**
         * Compute a lightweight perspective model from mount geometry and return it as a JSON
         * string suitable for persistence in DataStore.
         */
        fun calibrate(
            mountHeightCm: Float,
            tiltAngleDeg: Float,
        ): String {
            val h = mountHeightCm / 100f
            val tiltRad = tiltAngleDeg * (PI / 180.0).toFloat()
            return JSONObject()
                .put("h", h.toDouble())
                .put("tiltRad", tiltRad.toDouble())
                .put("fy", FOCAL_LENGTH_PX.toDouble())
                .toString()
        }

        /**
         * Returns meters-per-pixel at the given model-space y coordinate.
         *
         * Empty [matrixJson] → constant fallback so unit tests work without calibration data.
         * Populated [matrixJson] → geometric model: mpp = H / (fy × sin²β) where β is the
         * angle below the horizon at pixel row y.
         *
         * Parsing is cached per unique JSON string to avoid repeated allocations on every frame.
         */
        fun metersPerPixel(
            y: Float,
            matrixJson: String,
        ): Float {
            if (matrixJson.isEmpty()) return DEFAULT_MPP
            val params = getParams(matrixJson) ?: return DEFAULT_MPP
            val alpha = (y - MODEL_CENTER_Y) / params.fy
            val beta = params.tiltRad + alpha
            return if (beta <= 0f) {
                DEFAULT_MPP
            } else {
                val sinB = sin(beta.toDouble()).toFloat()
                (params.h / (params.fy * sinB * sinB)).coerceIn(0.01f, 5f)
            }
        }

        /**
         * Returns the ground-plane distance ahead (metres) imaged at model-space row [y].
         *
         * This is the exact integral of [metersPerPixel]: D = H / tan(β). Differencing two
         * rows through this function is more accurate than `Δy × mpp` for large displacements,
         * because mpp varies (quadratically) between the two rows.
         *
         * Empty or invalid [matrixJson] → affine fallback `(416 − y) × DEFAULT_MPP`. Distance
         * differences (all the speed estimator uses) then match the constant-mpp model exactly.
         */
        fun groundDistanceMeters(
            y: Float,
            matrixJson: String,
        ): Float {
            val params = if (matrixJson.isEmpty()) null else getParams(matrixJson)
            if (params == null) return (MODEL_INPUT_SIZE - y) * DEFAULT_MPP
            val alpha = (y - MODEL_CENTER_Y) / params.fy
            val beta = (params.tiltRad + alpha).coerceAtLeast(MIN_BETA_RAD)
            return (params.h / tan(beta.toDouble()).toFloat()).coerceIn(0f, MAX_GROUND_DISTANCE_M)
        }

        private fun getParams(matrixJson: String): CalibParams? {
            if (matrixJson == cachedJson) return cachedParams
            return try {
                val obj = JSONObject(matrixJson)
                CalibParams(
                    h = obj.getDouble("h").toFloat(),
                    tiltRad = obj.getDouble("tiltRad").toFloat(),
                    fy = obj.getDouble("fy").toFloat(),
                ).also {
                    cachedJson = matrixJson
                    cachedParams = it
                }
            } catch (e: JSONException) {
                Timber.w(e, "Failed to parse homography matrix JSON; using default mpp")
                cachedJson = matrixJson
                cachedParams = null
                null
            }
        }
    }
