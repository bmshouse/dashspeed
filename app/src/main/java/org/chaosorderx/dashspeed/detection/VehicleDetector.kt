package org.chaosorderx.dashspeed.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import org.chaosorderx.dashspeed.BuildConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val NANOS_PER_MS = 1_000_000f

// Debug builds also keep detections below the floor, to see what the model "almost" found.
private const val WEAK_MIN_CONFIDENCE = 0.05f
private const val WEAK_KEEP = 4
private const val WEAK_CANDIDATE_LIMIT = 60

// A box whose bottom edge is this close to the crop's bottom edge (model px) is cut off by the crop.
private const val CLIP_MARGIN_PX = 2f

// With a band model active, every this-many frames the whole frame is also checked by a full-frame
// model, to notice vehicles outside the band.
private const val PROBE_EVERY = 30

@Singleton
class VehicleDetector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        // Guards concurrent detect() (analysis thread) vs close() (main thread).
        private val interpreterLock = Any()
        private var model: LoadedModel? = null
        private var governor: ModelGovernor? = null
        private var probeModel: LoadedModel? = null
        private var probeFile: String? = null
        private var framesSinceProbe = 0
        private var bitmapScratch: RgbaFrame? = null
        private var loadAttempted = false

        /** Ladder index the governor wants next, applied on the following [detect]; tests may set it. */
        @Volatile
        internal var pendingSwitch: Int? = null

        @Volatile
        private var closeRequested = false

        private val loader = ModelLoader(context) { allowGpu }
        private val chooser = ModelChooser(context, loader, { allowGpu }, { closeRequested })
        private val classIds = ModelConfig.VEHICLE_CLASS_IDS.toIntArray()

        /** Set false (before the first [detect]) to force CPU inference, e.g. to compare backends in tests. */
        internal var allowGpu = true

        /** Tests: crop every frame to a strip of this many rows, whatever the model, to exercise the crop mapping. */
        internal var forcedCropRows: Int? = null

        /** Set before the first [detect] to run this asset instead of the benchmark's choice (tests). */
        internal var forcedModelFile: String? = null

        /** `"gpu"`, `"cpu"`, or `"none"` (no usable model) once the first frame has been processed. */
        @Volatile
        var backend: String = "none"
            private set

        /** Model input as `"<width>x<height>"` (+ `" nchw"`), or `"none"` before/without a loaded model. */
        @Volatile
        var modelInput: String = "none"
            private set

        /** Asset name of the model in use, or `"none"`. */
        @Volatile
        var modelFile: String = "none"
            private set

        /** Benchmark results behind the choice (JSON), when this run or an earlier one measured them. */
        @Volatile
        var benchmarkJson: String? = null
            private set

        /** Number of times the governor has changed model since start, and a description of the last change. */
        @Volatile
        var switchCount = 0
            private set

        @Volatile
        var lastSwitch: String? = null
            private set

        /** Debug builds: the best below-floor detections of the last frame (frame-normalised boxes). */
        @Volatile
        var lastWeak: List<Detection> = emptyList()
            private set

        /** The part of the frame the last detection looked at, when a band model is active; null = whole frame. */
        @Volatile
        var lastCrop: FrameCrop? = null
            private set

        /** Detections the periodic full-frame probe found outside the band; handed over once by [takeProbeResult]. */
        @Volatile
        private var probeOutside: List<Detection>? = null

        /** Fraction of the frame's height the active band model covers, or null for a full-frame model. */
        @Volatile
        var bandHeightFraction: Float? = null
            private set

        /** Outside-the-band detections from the latest probe, if one ran since the last call. */
        fun takeProbeResult(): List<Detection>? = probeOutside.also { probeOutside = null }

        /**
         * Picks and loads the model on first use, from the analysis thread: the GPU delegate must run
         * on the thread that created it, and the analyser is the only thread that runs inference.
         * Call with [interpreterLock] held.
         */
        private fun ensureLoaded() {
            if (loadAttempted) return
            loadAttempted = true
            val files = loader.listCandidates()
            val forced = forcedModelFile
            val choice =
                if (forced != null) {
                    ModelChoice(forced, emptyList(), null)
                } else {
                    val benchmarkRun = { loaded: LoadedModel, frame: RgbaFrame, center: Float ->
                        detectWith(loaded, frame, center, primary = false)
                        Unit
                    }
                    chooser.choose(files, benchmarkRun)
                }
            benchmarkJson = choice.json
            // Try the chosen model first, then any other, so one bad file can't disable detection
            // (a forced model, used by tests, gets no fallback).
            val fallbacks = if (forced == null) files.filter { it != choice.file } else emptyList()
            val order = listOfNotNull(choice.file) + fallbacks
            val loaded = order.firstNotNullOfOrNull { loader.open(it) }
            if (loaded == null) {
                Timber.e("VehicleDetector: no usable model among $files — detection disabled")
                return
            }
            adopt(loaded)
            val start = choice.results.firstOrNull { it.file == loaded.file }
            val governed = start?.takeIf { forced == null }
            val ladder = governed?.let { ModelSelector.governorLadder(choice.results, it) }.orEmpty()
            val startIndex = ladder.indexOfFirst { it.file == loaded.file }
            if (ladder.size > 1 && startIndex >= 0) governor = ModelGovernor(ladder, startIndex)
            probeFile = choice.results.filter { !it.band }.minByOrNull { it.p90Ms }?.file
            Timber.i("VehicleDetector: running ${loaded.file} on $backend, input $modelInput")
        }

        private fun adopt(loaded: LoadedModel) {
            model = loaded
            backend = loaded.backend
            modelInput = loaded.inputDescription
            modelFile = loaded.file
            bandHeightFraction = null
            if (!FrameGeometry.isBandModel(loaded.io.width, loaded.io.height)) {
                probeModel?.close()
                probeModel = null
            }
        }

        fun detect(
            frame: Bitmap,
            bandCenterFrac: Float = DEFAULT_BAND_CENTER_FRAC,
        ): List<Detection> {
            val rgba = RgbaFrame.fromBitmap(frame, bitmapScratch)
            bitmapScratch = rgba
            return detect(rgba, bandCenterFrac)
        }

        /**
         * [bandCenterFrac]: where a band model's strip is centred (0..1 of the frame height); full-frame
         * models ignore it.
         */
        fun detect(
            frame: RgbaFrame,
            bandCenterFrac: Float = DEFAULT_BAND_CENTER_FRAC,
        ): List<Detection> =
            synchronized(interpreterLock) {
                ensureLoaded()
                applyPendingSwitch()
                val loaded = model ?: return emptyList()
                val startNs = System.nanoTime()
                val detections = detectWith(loaded, frame, bandCenterFrac, primary = true)
                val frameMs = (System.nanoTime() - startNs) / NANOS_PER_MS
                pendingSwitch = governor?.record(frameMs, SystemClock.elapsedRealtime())
                if (lastCrop != null && ++framesSinceProbe >= PROBE_EVERY) probe(frame, bandCenterFrac)
                detections
            }

        /** Runs a full-frame model on [frame] and keeps what it sees outside the band the main model looks at. */
        private fun probe(
            frame: RgbaFrame,
            bandCenterFrac: Float,
        ) {
            framesSinceProbe = 0
            val file = probeFile ?: return
            val probe = probeModel ?: loader.open(file)?.also { probeModel = it } ?: return
            val crop = lastCrop ?: return
            val topRow = crop.top.toFloat() / frame.height * ModelConfig.FRAME_SPACE
            val bottomRow = (crop.top + crop.height).toFloat() / frame.height * ModelConfig.FRAME_SPACE
            probeOutside =
                detectWith(probe, frame, bandCenterFrac, primary = false).filter {
                    val centerRow = (it.boundingBox.top + it.boundingBox.bottom) / 2f
                    centerRow < topRow || centerRow > bottomRow
                }
        }

        /** Moves to the model the governor asked for; on failure the governor is switched off. */
        private fun applyPendingSwitch() {
            val target = pendingSwitch ?: return
            pendingSwitch = null
            val gov = governor ?: return
            val next = loader.open(gov.fileAt(target))
            if (next == null) {
                Timber.w("VehicleDetector: could not open ${gov.fileAt(target)}; governor off")
                governor = null
                return
            }
            lastSwitch = "$modelFile -> ${next.file} (median frame time over limit, or recovered)"
            model?.close()
            adopt(next)
            gov.onSwitched(target, SystemClock.elapsedRealtime())
            switchCount++
            Timber.i("VehicleDetector: governor switched to ${next.file} ($modelInput)")
        }

        /** One frame through [loaded]; [primary] false (benchmarks, probes) leaves the debug state untouched. */
        private fun detectWith(
            loaded: LoadedModel,
            frame: RgbaFrame,
            bandCenterFrac: Float,
            primary: Boolean,
        ): List<Detection> {
            val io = loaded.io
            val forcedRows = if (primary) forcedCropRows else null
            val crop =
                when {
                    forcedRows != null -> FrameGeometry.stripCrop(frame.width, frame.height, forcedRows, bandCenterFrac)
                    FrameGeometry.isBandModel(io.width, io.height) ->
                        FrameGeometry.bandCrop(frame.width, frame.height, io.width, io.height, bandCenterFrac)
                    else -> FrameCrop(0, 0, frame.width, frame.height)
                }
            val letterbox = FrameGeometry.letterbox(frame.width, frame.height, io.width, io.height, crop)
            io.fill(frame, letterbox)
            io.infer(loaded.interpreter)
            // YOLOv8 output: [1, 84, anchors] (or transposed) where 84 = 4 box + 80 classes, anchors =
            // sum of (H/s)·(W/s) over strides 8/16/32 (3549 for 416², 6300 for 480×640).
            val threshold = if (BuildConfig.DEBUG) WEAK_MIN_CONFIDENCE else ModelConfig.DETECTION_FLOOR
            val candidates =
                YoloDecoder.decode(io.output, io.layout, io.width.toFloat(), io.height.toFloat(), threshold, classIds)
            val (strong, weak) = candidates.partition { it.confidence >= ModelConfig.DETECTION_FLOOR }
            if (primary) {
                lastCrop = if (letterbox.isCropped) crop else null
                bandHeightFraction = if (letterbox.isCropped) crop.height.toFloat() / frame.height else null
                if (BuildConfig.DEBUG) {
                    val best = weak.sortedByDescending { it.confidence }.take(WEAK_CANDIDATE_LIMIT)
                    lastWeak = toFrameSpace(best, letterbox).take(WEAK_KEEP)
                }
            }
            return toFrameSpace(strong, letterbox)
        }

        /** NMS, then boxes into frame-normalised space (full frame = 0..FRAME_SPACE), independent of model size. */
        private fun toFrameSpace(
            candidates: List<Candidate>,
            letterbox: FrameGeometry.Letterbox,
        ): List<Detection> =
            ModelConfig.nonMaxSuppression(
                candidates.map {
                    val clipped = letterbox.isCropped && it.bottom >= letterbox.imageBottom - CLIP_MARGIN_PX
                    Detection(RectF(it.left, it.top, it.right, it.bottom), it.classId, it.confidence, clipped)
                },
            ).map {
                val b = it.boundingBox
                it.copy(
                    boundingBox =
                        RectF(
                            letterbox.toNormX(b.left),
                            letterbox.toNormY(b.top),
                            letterbox.toNormX(b.right),
                            letterbox.toNormY(b.bottom),
                        ),
                )
            }

        fun close() {
            closeRequested = true
            synchronized(interpreterLock) {
                model?.close()
                model = null
                probeModel?.close()
                probeModel = null
            }
        }
    }
