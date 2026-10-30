package org.chaosorderx.dashspeed.detection

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import org.chaosorderx.dashspeed.BuildConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

// Bump when the benchmark methodology changes so cached decisions made the old way are discarded.
// v2: times the whole per-frame pipeline instead of just the model run.
// v3: pipeline rewritten (direct RGBA conversion, flat output buffer), so earlier timings are stale.
// v4: models ranked by detail (scale) rather than pixel count; band models benchmarked on their own frame size.
private const val BENCHMARK_METHOD_VERSION = 4
private const val BENCH_ASPECT_W = 4
private const val BENCH_ASPECT_H = 3

/** Where the band is centred before anything has been learned: rows 144-368 of 480 hold 99.9% of vehicles. */
internal const val DEFAULT_BAND_CENTER_FRAC = 256f / 480f

/** The result of choosing among the bundled models. */
internal class ModelChoice(
    val file: String?,
    /** Benchmarks behind the choice (also what the governor and probe select from). */
    val results: List<ModelBenchmark>,
    val json: String?,
)

/**
 * Picks which bundled model to run by benchmarking each on this device, and remembers the decision
 * (and the camera frame width it wants) until the app version, device or set of model files changes.
 */
internal class ModelChooser(
    private val context: Context,
    private val loader: ModelLoader,
    private val allowGpu: () -> Boolean,
    private val closing: () -> Boolean,
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * [runOnce] is the real per-frame pipeline, run on a synthetic frame of the width the model wants.
     * With a single bundled model there is nothing to choose.
     */
    fun choose(
        files: List<String>,
        runOnce: (LoadedModel, RgbaFrame, Float) -> Unit,
    ): ModelChoice {
        if (files.size <= 1) return ModelChoice(files.firstOrNull(), emptyList(), null)
        // A CPU-forced detector (tests) must not read or overwrite the GPU decision.
        val store = if (allowGpu()) prefs else null
        val fingerprint = fingerprint(files)
        cachedChoice(store, files, fingerprint)?.let { return it }
        Timber.i("ModelChooser: benchmarking ${files.size} models for this device")
        val results = benchmarkAll(files, runOnce)
        val best = ModelSelector.choose(results)
        val json = toJson(results, best)
        if (store != null && best != null && !closing()) {
            store.edit()
                .putString(KEY_FINGERPRINT, fingerprint)
                .putString(KEY_CHOSEN, best.file)
                .putString(KEY_BENCHMARK, json)
                .putInt(KEY_ANALYSIS_WIDTH, if (best.band) best.frameWidth else ModelSelector.REFERENCE_FRAME_WIDTH)
                .apply()
        }
        return ModelChoice(best?.file, results, json)
    }

    /** The remembered decision, if it was made for exactly this app build, device and set of model files. */
    private fun cachedChoice(
        store: SharedPreferences?,
        files: List<String>,
        fingerprint: String,
    ): ModelChoice? {
        val chosen = store?.getString(KEY_CHOSEN, null)
        val json = store?.getString(KEY_BENCHMARK, null)
        val current = store?.getString(KEY_FINGERPRINT, null) == fingerprint
        return if (json != null && chosen in files && current) ModelChoice(chosen, fromJson(json), json) else null
    }

    private fun benchmarkAll(
        files: List<String>,
        runOnce: (LoadedModel, RgbaFrame, Float) -> Unit,
    ): List<ModelBenchmark> {
        val frames = mutableMapOf<Int, RgbaFrame>()
        return files.takeWhile { !closing() }.mapNotNull { file ->
            loader.benchmark(file) { model ->
                val frameW = benchFrameWidth(model.io.width, model.io.height)
                runOnce(model, frames.getOrPut(frameW) { grayFrame(frameW) }, DEFAULT_BAND_CENTER_FRAC)
            }?.withGeometry()
        }
    }

    private fun grayFrame(width: Int): RgbaFrame {
        val height = width * BENCH_ASPECT_H / BENCH_ASPECT_W
        val bytes = ByteArray(width * height * RgbaFrame.BYTES_PER_PIXEL)
        bytes.fill(Color.GRAY.toByte())
        return RgbaFrame(width, height, bytes)
    }

    private fun fingerprint(files: List<String>): String {
        val build = listOf(BENCHMARK_METHOD_VERSION, BuildConfig.VERSION_CODE, Build.MODEL, Build.VERSION.SDK_INT)
        return build.joinToString("|") + "|" + files.joinToString(",") { "$it:${loader.sizeBytes(it)}" }
    }

    companion object {
        private const val PREFS_NAME = "model_selection"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_CHOSEN = "chosen"
        private const val KEY_BENCHMARK = "benchmark"
        private const val KEY_ANALYSIS_WIDTH = "analysis_width"

        /** Camera analysis frame width the chosen model wants (640 unless a band model needs more detail). */
        fun savedAnalysisWidth(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_ANALYSIS_WIDTH, ModelSelector.REFERENCE_FRAME_WIDTH)

        internal fun toJson(
            results: List<ModelBenchmark>,
            best: ModelBenchmark?,
        ): String =
            JSONObject()
                .put("budgetP90Ms", ModelConfig.MODEL_BUDGET_P90_MS.toDouble())
                .put("chosen", best?.file)
                .put(
                    "models",
                    JSONArray().also { array ->
                        results.forEach {
                            array.put(
                                JSONObject()
                                    .put("file", it.file)
                                    .put("input", "${it.width}x${it.height}")
                                    .put("backend", it.backend)
                                    .put("medianMs", it.medianMs.toDouble())
                                    .put("p90Ms", it.p90Ms.toDouble())
                                    .put("scale", it.scale.toDouble())
                                    .put("band", it.band)
                                    .put("frameWidth", it.frameWidth),
                            )
                        }
                    },
                ).toString()

        /** Inverse of [toJson]'s `models` list; unparsable entries are skipped. */
        internal fun fromJson(json: String): List<ModelBenchmark> =
            try {
                val models = JSONObject(json).getJSONArray("models")
                (0 until models.length()).mapNotNull { i ->
                    val m = models.getJSONObject(i)
                    val (w, h) = m.getString("input").split("x").map { it.toInt() }
                    ModelBenchmark(
                        file = m.getString("file"),
                        width = w,
                        height = h,
                        backend = m.getString("backend"),
                        medianMs = m.getDouble("medianMs").toFloat(),
                        p90Ms = m.getDouble("p90Ms").toFloat(),
                        scale = m.optDouble("scale", 1.0).toFloat(),
                        band = m.optBoolean("band", false),
                        frameWidth = m.optInt("frameWidth", ModelSelector.REFERENCE_FRAME_WIDTH),
                    )
                }
            } catch (e: JSONException) {
                Timber.w(e, "ModelChooser: cached benchmark unreadable")
                emptyList()
            }
    }
}

/** Frame width a model should be benchmarked at: its own width for band models (full detail), else 640. */
private fun benchFrameWidth(
    modelW: Int,
    modelH: Int,
): Int =
    if (FrameGeometry.isBandModel(
            modelW,
            modelH,
        )
    ) {
        maxOf(modelW, ModelSelector.REFERENCE_FRAME_WIDTH)
    } else {
        ModelSelector.REFERENCE_FRAME_WIDTH
    }

/** Fills in how much detail the model gets (see [ModelBenchmark.scale]) from its input shape. */
private fun ModelBenchmark.withGeometry(): ModelBenchmark {
    val frameW = benchFrameWidth(width, height)
    val frameH = frameW * BENCH_ASPECT_H / BENCH_ASPECT_W
    val crop = FrameGeometry.bandCrop(frameW, frameH, width, height, DEFAULT_BAND_CENTER_FRAC)
    val letterbox = FrameGeometry.letterbox(frameW, frameH, width, height, crop)
    return copy(
        scale = letterbox.scale * frameW / ModelSelector.REFERENCE_FRAME_WIDTH,
        band = FrameGeometry.isBandModel(width, height),
        frameWidth = frameW,
    )
}
