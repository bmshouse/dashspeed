package org.chaosorderx.dashspeed.detection

import android.content.Context
import android.os.SystemClock
import org.chaosorderx.dashspeed.BuildConfig
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** An interpreter ready to run, with everything that must be released with it. */
internal class LoadedModel(
    val file: String,
    val interpreter: Interpreter,
    private val delegate: GpuDelegate?,
    val io: ModelIo,
    val backend: String,
) {
    val inputDescription = "${io.width}x${io.height}" + if (io.layout.nchw) " nchw" else ""

    fun close() {
        interpreter.close()
        delegate?.close()
        io.release()
    }
}

/** Finds, opens and benchmarks the `.tflite` models bundled in the app's assets. */
internal class ModelLoader(
    private val context: Context,
    private val allowGpu: () -> Boolean,
) {
    fun listCandidates(): List<String> {
        val names = context.assets.list("").orEmpty()
        return names.filter { it.endsWith(MODEL_SUFFIX) }.sorted()
    }

    fun sizeBytes(file: String): Long =
        try {
            context.assets.openFd(file).use { it.length }
        } catch (e: IOException) {
            Timber.w(e, "ModelLoader: cannot size $file")
            -1L
        }

    /** Opens [file] on the GPU if possible, else the CPU; null if missing or not a supported model. */
    fun open(file: String): LoadedModel? {
        val startedMs = SystemClock.elapsedRealtime()
        val buffer = mapAsset(file) ?: return null
        val (interpreter, delegate) = createGpuInterpreter(buffer, file) ?: (createCpuInterpreter(buffer) to null)
        val io = interpreter?.let { buildIo(it, file) }
        if (interpreter == null || io == null) {
            interpreter?.close()
            delegate?.close()
            return null
        }
        val backend = if (delegate != null) "gpu" else "cpu"
        Timber.i("ModelLoader: opened $file on $backend in ${SystemClock.elapsedRealtime() - startedMs} ms")
        return LoadedModel(file, interpreter, delegate, io, backend)
    }

    /**
     * Times steady-state per-frame work of [file] on this device by calling [runOnce] repeatedly.
     * Pass the real per-frame pipeline (resize, convert, inference, decode): the model alone is
     * often a small part of the cost. Null if the model can't be opened or run.
     */
    @Suppress("TooGenericExceptionCaught")
    fun benchmark(
        file: String,
        runOnce: (LoadedModel) -> Unit,
    ): ModelBenchmark? {
        val model = open(file) ?: return null
        return try {
            repeat(WARMUP_RUNS) { runOnce(model) }
            val timesMs =
                List(TIMED_RUNS) {
                    val start = System.nanoTime()
                    runOnce(model)
                    (System.nanoTime() - start) / NANOS_PER_MS
                }.sorted()
            ModelBenchmark(
                file = file,
                width = model.io.width,
                height = model.io.height,
                backend = model.backend,
                medianMs = timesMs[timesMs.size / 2],
                p90Ms = timesMs[(timesMs.size * P90_FRACTION).toInt()],
            ).also { Timber.i("ModelLoader: benchmark $it") }
        } catch (e: RuntimeException) {
            Timber.w(e, "ModelLoader: benchmark failed for $file")
            null
        } finally {
            model.close()
        }
    }

    private fun mapAsset(file: String): MappedByteBuffer? =
        try {
            val fd = context.assets.openFd(file)
            FileInputStream(fd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        } catch (e: IOException) {
            Timber.e(e, "ModelLoader: model asset $file not readable")
            null
        }

    // GpuDelegate can throw many Throwable subclasses (e.g. NoClassDefFoundError, driver
    // failures at Interpreter creation); any failure must fall back to CPU inference.
    @Suppress("TooGenericExceptionCaught")
    private fun createGpuInterpreter(
        model: MappedByteBuffer,
        file: String,
    ): Pair<Interpreter, GpuDelegate>? {
        if (!allowGpu()) return null
        var delegate: GpuDelegate? = null
        return try {
            delegate = GpuDelegate(gpuOptions(file))
            Interpreter(model, Interpreter.Options().addDelegate(delegate)) to delegate
        } catch (e: Throwable) {
            Timber.w(e, "ModelLoader: GPU unavailable, using CPU")
            delegate?.close()
            null
        }
    }

    /**
     * Compiled GPU kernels are cached on disk, keyed by model file, size and app version, so
     * reloading a model (a governor switch, the next launch) skips the multi-second compile.
     */
    private fun gpuOptions(file: String): GpuDelegateFactory.Options {
        val dir = File(context.codeCacheDir, GPU_CACHE_DIR).apply { mkdirs() }
        val token = "${file}_${sizeBytes(file)}_${BuildConfig.VERSION_CODE}"
        return GpuDelegateFactory.Options().setSerializationParams(dir.absolutePath, token)
    }

    private fun createCpuInterpreter(model: MappedByteBuffer): Interpreter? =
        try {
            Interpreter(model, Interpreter.Options())
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "ModelLoader: failed to create Interpreter")
            null
        }

    /** Sizes buffers from the model's own tensors; null if the model isn't what the decoder expects. */
    private fun buildIo(
        interp: Interpreter,
        file: String,
    ): ModelIo? {
        val inTensor = interp.getInputTensor(0)
        val inShape = inTensor.shape()
        val outShape = interp.getOutputTensor(0).shape()
        val layout = if (inTensor.dataType() == DataType.FLOAT32) ModelLayout.parse(inShape, outShape) else null
        if (layout == null) {
            Timber.e(
                "ModelLoader: unsupported model $file — input ${inShape.toList()} ${inTensor.dataType()}, " +
                    "output ${outShape.toList()}; expected float32 [1,H,W,3] or [1,3,H,W] -> [1,4+classes,N]",
            )
            return null
        }
        return ModelIo(layout)
    }

    private companion object {
        const val MODEL_SUFFIX = ".tflite"
        const val GPU_CACHE_DIR = "gpu_kernels"
        const val WARMUP_RUNS = 5
        const val TIMED_RUNS = 20
        const val P90_FRACTION = 0.9
        const val NANOS_PER_MS = 1_000_000f
    }
}
