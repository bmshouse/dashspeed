package org.chaosorderx.dashspeed.camera

import androidx.camera.core.ImageAnalysis
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.chaosorderx.dashspeed.detection.Detection
import org.chaosorderx.dashspeed.detection.VehicleDetector
import timber.log.Timber
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
    ) {
        private val _detectionFlow =
            MutableSharedFlow<FrameDetections>(
                replay = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val detectionFlow: Flow<FrameDetections> = _detectionFlow.asSharedFlow()

        @Suppress("TooGenericExceptionCaught")
        val analyzer: ImageAnalysis.Analyzer =
            ImageAnalysis.Analyzer { imageProxy ->
                val frameTimestampMs = imageProxy.imageInfo.timestamp / 1_000_000L
                val bitmap = imageProxy.toBitmap()
                try {
                    _detectionFlow.tryEmit(
                        FrameDetections(vehicleDetector.detect(bitmap), frameTimestampMs),
                    )
                } catch (e: Exception) {
                    Timber.w(e, "FrameProcessor: frame analysis failed, skipping")
                } finally {
                    bitmap.recycle()
                    imageProxy.close()
                }
            }
    }
