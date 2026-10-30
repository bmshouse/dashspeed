package org.chaosorderx.dashspeed.camera

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.chaosorderx.dashspeed.debug.DebugRecorder
import org.chaosorderx.dashspeed.debug.SnapshotRequest
import org.chaosorderx.dashspeed.detection.BandLearner
import org.chaosorderx.dashspeed.detection.Detection
import org.chaosorderx.dashspeed.detection.DetectionFilter
import org.chaosorderx.dashspeed.detection.HoodDetector
import org.chaosorderx.dashspeed.detection.RgbaFrame
import org.chaosorderx.dashspeed.detection.StaticMask
import org.chaosorderx.dashspeed.detection.StaticMaskLearner
import org.chaosorderx.dashspeed.detection.VehicleDetector
import org.chaosorderx.dashspeed.gps.GpsManager
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detections for one camera frame, stamped with the frame's *capture* timestamp.
 *
 * [timestampMs] comes from the camera HAL (monotonic, ns → ms), not the wall clock at
 * processing time — inference and scheduling jitter must not leak into the time deltas
 * the speed estimator divides by. Only differences between timestamps are meaningful.
 */
data class FrameDetections(
    val detections: List<Detection>,
    val timestampMs: Long,
)

@Singleton
class FrameProcessor
    @Inject
    constructor(
        private val vehicleDetector: VehicleDetector,
        private val staticMaskLearner: StaticMaskLearner,
        private val bandLearner: BandLearner,
        private val hoodDetector: HoodDetector,
        private val gpsManager: GpsManager,
        private val debugRecorder: DebugRecorder,
    ) {
        private val _detectionFlow =
            MutableSharedFlow<FrameDetections>(
                replay = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val detectionFlow: Flow<FrameDetections> = _detectionFlow.asSharedFlow()

        // Analysis-thread only.
        private var rgbaFrame: RgbaFrame? = null
        private val frameGate = FrameRateGate()
        private var lastLoggedMask: StaticMask? = null
        private var lastLearnerLogMs = 0L
        private var benchmarkLogged = false
        private var lastSwitchCount = 0
        private var lastResetCount = 0

        @Suppress("TooGenericExceptionCaught")
        val analyzer: ImageAnalysis.Analyzer =
            ImageAnalysis.Analyzer { imageProxy ->
                try {
                    val frameTimestampMs = imageProxy.imageInfo.timestamp / 1_000_000L
                    if (frameGate.accept(frameTimestampMs)) process(copyPixels(imageProxy), frameTimestampMs)
                } catch (e: Exception) {
                    Timber.w(e, "FrameProcessor: frame analysis failed, skipping")
                } finally {
                    imageProxy.close()
                }
            }

        /** Copies the camera's RGBA plane into a reused tightly packed array (no per-frame allocation). */
        private fun copyPixels(imageProxy: ImageProxy): RgbaFrame {
            val width = imageProxy.width
            val height = imageProxy.height
            val rowBytes = width * RgbaFrame.BYTES_PER_PIXEL
            val frame =
                rgbaFrame?.takeIf { it.width == width && it.height == height }
                    ?: RgbaFrame(width, height, ByteArray(rowBytes * height)).also { rgbaFrame = it }
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            if (plane.rowStride == rowBytes) {
                buffer.rewind()
                buffer.get(frame.rgba, 0, rowBytes * height)
            } else {
                for (row in 0 until height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(frame.rgba, row * rowBytes, rowBytes)
                }
            }
            return frame
        }

        private fun process(
            frame: RgbaFrame,
            frameTimestampMs: Long,
        ) {
            val egoSpeedMps = gpsManager.egoSpeedMps.value
            val moving =
                egoSpeedMps >= StaticMaskLearner.MIN_SPEED_MPS &&
                    !gpsManager.headingChangeTooFast.value
            if (staticMaskLearner.learningFlow.value) {
                val bitmap = frame.toBitmap()
                staticMaskLearner.observe(bitmap, moving, frameTimestampMs)
                bitmap.recycle()
            }
            val mask = staticMaskLearner.mask

            val startedMs = SystemClock.elapsedRealtime()
            val raw = vehicleDetector.detect(frame, bandLearner.centerFrac)
            val inferenceMs = SystemClock.elapsedRealtime() - startedMs
            if (staticMaskLearner.resetCount != lastResetCount) {
                lastResetCount = staticMaskLearner.resetCount
                hoodDetector.reset()
            }
            val (maskDropped, notMasked) = raw.partition { isStatic(it, mask) }
            val (kept, newZones) = hoodDetector.filter(notMasked, moving, frameTimestampMs)
            val dropped = maskDropped + (notMasked - kept.toSet())
            newZones.forEach { staticMaskLearner.addRegion(it) }
            _detectionFlow.tryEmit(FrameDetections(kept, frameTimestampMs))
            feedBandLearner(kept, moving, mask)

            if (debugRecorder.enabled) {
                recordDebug(frame, frameTimestampMs, egoSpeedMps, moving, inferenceMs, kept, dropped, mask)
            }
        }

        /** Teaches the band learner where vehicles appear, and tells it about any the full-frame probe saw outside. */
        private fun feedBandLearner(
            kept: List<Detection>,
            moving: Boolean,
            mask: StaticMask,
        ) {
            val nowMs = SystemClock.elapsedRealtime()
            val bandHeight = vehicleDetector.bandHeightFraction
            bandLearner.observe(kept, moving, bandHeight, nowMs)
            // The probe sees the whole frame, hood included, so it gets the same static/hood filtering.
            vehicleDetector.takeProbeResult()?.filterNot { isStatic(it, mask) }?.let {
                bandLearner.observeOutside(it, bandHeight, nowMs)
            }
        }

        private fun isStatic(
            detection: Detection,
            mask: StaticMask,
        ): Boolean {
            val b = detection.boundingBox
            return DetectionFilter.isStatic(b.left, b.top, b.right, b.bottom, mask)
        }

        @Suppress("LongParameterList")
        private fun recordDebug(
            frame: RgbaFrame,
            frameTimestampMs: Long,
            egoSpeedMps: Float,
            moving: Boolean,
            inferenceMs: Long,
            kept: List<Detection>,
            dropped: List<Detection>,
            mask: StaticMask,
        ) {
            val wallMs = System.currentTimeMillis()
            debugRecorder.appendLine(
                JSONObject()
                    .put("type", "frame")
                    .put("t", frameTimestampMs)
                    .put("wall", wallMs)
                    .put("egoMps", egoSpeedMps.toDouble())
                    .put("turning", gpsManager.headingChangeTooFast.value)
                    .put("learnerMoving", moving)
                    .put("inferMs", inferenceMs)
                    .put("backend", vehicleDetector.backend)
                    .put("model", vehicleDetector.modelInput)
                    .put("modelFile", vehicleDetector.modelFile)
                    .put("frame", "${frame.width}x${frame.height}")
                    .put("band", bandRows()?.let { JSONArray(listOf(it.first, it.second)) } ?: JSONObject.NULL)
                    .put("kept", detectionsJson(kept))
                    .put("droppedByMask", detectionsJson(dropped))
                    .put("weak", detectionsJson(vehicleDetector.lastWeak)),
            )
            logModelState(wallMs)
            logStateFiles(wallMs, mask)
            val reason = debugRecorder.snapshotReason(wallMs, dropped.isNotEmpty()) ?: return
            val caption = "t=$frameTimestampMs v=%.1fm/s kept=${kept.size} dropped=${dropped.size}".format(egoSpeedMps)
            debugRecorder.saveSnapshot(
                SnapshotRequest(reason, frame.toBitmap(), kept, dropped, mask, caption, bandRows()),
                rawToo = reason == "mark",
            )
        }

        /** `mask.json` when the mask changes; `learner.json` and `band.json` every 30 s. */
        private fun logStateFiles(
            wallMs: Long,
            mask: StaticMask,
        ) {
            if (mask !== lastLoggedMask) {
                lastLoggedMask = mask
                debugRecorder.writeJson("mask.json", JSONObject().put("bits", mask.serialize()).put("wall", wallMs))
            }
            if (wallMs - lastLearnerLogMs >= LEARNER_LOG_INTERVAL_MS) {
                lastLearnerLogMs = wallMs
                debugRecorder.writeJson("learner.json", staticMaskLearner.debugJson())
                debugRecorder.writeJson("band.json", bandLearner.debugJson())
            }
        }

        private fun bandRows(): Pair<Int, Int>? = vehicleDetector.lastCrop?.let { it.top to it.top + it.height }

        /** Writes the benchmark once and a line each time the governor changes model. */
        private fun logModelState(wallMs: Long) {
            val benchmark = vehicleDetector.benchmarkJson
            if (!benchmarkLogged && benchmark != null) {
                benchmarkLogged = true
                debugRecorder.writeJson("model_benchmark.json", JSONObject(benchmark))
            }
            val switches = vehicleDetector.switchCount
            if (switches != lastSwitchCount) {
                lastSwitchCount = switches
                debugRecorder.appendLine(
                    JSONObject()
                        .put("type", "model_switch")
                        .put("wall", wallMs)
                        .put("modelFile", vehicleDetector.modelFile)
                        .put("detail", vehicleDetector.lastSwitch),
                )
            }
        }

        private fun detectionsJson(detections: List<Detection>): JSONArray =
            JSONArray().also { array ->
                detections.forEach {
                    val b = it.boundingBox
                    array.put(
                        JSONObject()
                            .put("box", JSONArray(listOf(b.left, b.top, b.right, b.bottom).map { v -> v.toDouble() }))
                            .put("cls", it.classId)
                            .put("conf", it.confidence.toDouble()),
                    )
                }
            }

        private companion object {
            const val LEARNER_LOG_INTERVAL_MS = 30_000L
        }
    }

/** A mutable bitmap copy of [this] (for the mask learner and debug snapshots; not on the hot path). */
private fun RgbaFrame.toBitmap(): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    }
