package org.chaosorderx.dashspeed.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleDetector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        // Guards concurrent detect() (analysis thread) vs close() (main thread).
        private val interpreterLock = Any()
        private var interpreter: Interpreter? = null
        private var gpuDelegate: GpuDelegate? = null

        // Pre-allocated inference buffers — reused every frame to avoid per-frame GC pressure.
        private val numPixels = ModelConfig.INPUT_SIZE * ModelConfig.INPUT_SIZE
        private val scaledBitmap =
            Bitmap.createBitmap(ModelConfig.INPUT_SIZE, ModelConfig.INPUT_SIZE, Bitmap.Config.ARGB_8888)
        private val scaledCanvas = Canvas(scaledBitmap)
        private val inputBuffer =
            ByteBuffer.allocateDirect(numPixels * 3 * 4).order(ByteOrder.nativeOrder())
        private val pixelArray = IntArray(numPixels)
        private val srcRect = Rect()
        private val dstRect = Rect(0, 0, ModelConfig.INPUT_SIZE, ModelConfig.INPUT_SIZE)

        init {
            loadModel()
        }

        // GpuDelegate can throw many RuntimeException subclasses depending on device/driver state;
        // catching the base class here is intentional so we always fall back to CPU inference.
        @Suppress("TooGenericExceptionCaught")
        private fun loadModel() {
            val model =
                try {
                    val fd = context.assets.openFd(ModelConfig.MODEL_FILE)
                    FileInputStream(fd.fileDescriptor).use { fis ->
                        fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    }
                } catch (e: IOException) {
                    Timber.e(e, "VehicleDetector: model asset not found — detection disabled")
                    return
                }

            val options = Interpreter.Options()
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate!!)
                Timber.d("VehicleDetector: GPU delegate enabled")
            } catch (e: Throwable) {
                Timber.w("VehicleDetector: GPU unavailable, using CPU: ${e.message}")
                gpuDelegate = null
            }

            try {
                synchronized(interpreterLock) {
                    interpreter = Interpreter(model, options)
                }
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "VehicleDetector: failed to create Interpreter — detection disabled")
            }
        }

        fun detect(frame: Bitmap): List<Detection> {
            // Scale frame into the pre-allocated bitmap; runs on the analysis thread only.
            srcRect.set(0, 0, frame.width, frame.height)
            scaledCanvas.drawBitmap(frame, srcRect, dstRect, null)
            scaledBitmap.getPixels(
                pixelArray,
                0,
                ModelConfig.INPUT_SIZE,
                0,
                0,
                ModelConfig.INPUT_SIZE,
                ModelConfig.INPUT_SIZE,
            )
            // YOLOv8n tflite input: float32, values normalised to [0, 1], RGB channel order.
            inputBuffer.rewind()
            for (pixel in pixelArray) {
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((pixel and 0xFF) / 255f)
            }
            inputBuffer.rewind()

            // YOLOv8n tflite output: [1, 84, 3549] where 84 = 4 box + 80 classes,
            // 3549 = 52×52 + 26×26 + 13×13 grid cells for 416-px input.
            // Box coords (rows 0-3) are normalized [0, 1]; parseDetections scales to pixels.
            return synchronized(interpreterLock) {
                val interp = interpreter ?: return emptyList()
                val shape = interp.getOutputTensor(0).shape()
                val output = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
                interp.run(inputBuffer, output)
                parseDetections(output[0], shape[2])
            }
        }

        private fun parseDetections(
            output: Array<FloatArray>,
            numPredictions: Int,
        ): List<Detection> {
            val inputSize = ModelConfig.INPUT_SIZE.toFloat()
            val candidates = mutableListOf<Detection>()
            for (i in 0 until numPredictions) {
                // Model outputs normalized [0,1] coords; scale to pixel space.
                val cx = output[0][i] * inputSize
                val cy = output[1][i] * inputSize
                val w = output[2][i] * inputSize
                val h = output[3][i] * inputSize
                var bestScore = ModelConfig.CONFIDENCE_THRESHOLD
                var bestClassId = -1
                for (classId in ModelConfig.VEHICLE_CLASS_IDS) {
                    val score = output[4 + classId][i]
                    if (score > bestScore) {
                        bestScore = score
                        bestClassId = classId
                    }
                }
                if (bestClassId == -1) continue
                val left = (cx - w / 2f).coerceIn(0f, inputSize)
                val top = (cy - h / 2f).coerceIn(0f, inputSize)
                val right = (cx + w / 2f).coerceIn(0f, inputSize)
                val bottom = (cy + h / 2f).coerceIn(0f, inputSize)
                if (right > left && bottom > top) {
                    candidates.add(Detection(RectF(left, top, right, bottom), bestClassId, bestScore))
                }
            }
            return ModelConfig.nonMaxSuppression(candidates)
        }

        fun close() {
            synchronized(interpreterLock) {
                interpreter?.close()
                interpreter = null
            }
            gpuDelegate?.close()
            gpuDelegate = null
            scaledBitmap.recycle()
        }
    }
