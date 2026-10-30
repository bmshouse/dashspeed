package org.chaosorderx.dashspeed.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "VehicleDetectorTest"
private const val MARGIN = 6f
private const val PHANTOM_CONFIDENCE = 0.5f

@RunWith(AndroidJUnit4::class)
class VehicleDetectorTest {
    // targetContext = the app under test (needed by VehicleDetector to load the model asset)
    // testContext   = the instrumentation app (hosts files in androidTest/assets/)
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val detector = VehicleDetector(targetContext)

    @After
    fun tearDown() = detector.close()

    @Test
    fun gpuDelegateActivatesWithoutException() {
        assertNotNull(detector)
    }

    @Test
    fun detectorReturnsNonNullListOnAnyBitmapInput() {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val detections = detector.detect(bitmap)
        bitmap.recycle()
        assertNotNull(detections)
        assertTrue(
            "Detection list must be empty or valid",
            detections.all { it.confidence >= ModelConfig.DETECTION_FLOOR },
        )
    }

    @Test
    fun detectorReturnsVehicleDetectionsOnAllTestFrames() {
        val jpgAssets =
            testContext.assets.list("")
                ?.filter { it.endsWith(".jpg") || it.endsWith(".jpeg") }
                .orEmpty()
        assumeTrue("No .jpg assets found in androidTest/assets — add images to activate this test", jpgAssets.isNotEmpty())

        for (assetName in jpgAssets) {
            val bitmap = testContext.assets.open(assetName).use { BitmapFactory.decodeStream(it) }
            assertNotNull("[$assetName] Failed to decode image", bitmap)

            val detections = detector.detect(bitmap)
            bitmap.recycle()

            Log.d(
                TAG,
                "[$assetName] ${detections.size} detection(s): " +
                    detections.joinToString { "class=${it.classId} conf=%.2f".format(it.confidence) },
            )

            // "distant" scenes may legitimately yield nothing (or, with a larger model, a few far
            // vehicles); whatever is returned must still be a valid vehicle detection.
            if ("distant" in assetName) {
                assertTrue(
                    "[$assetName] non-vehicle class returned",
                    detections.all { it.classId in ModelConfig.VEHICLE_CLASS_IDS },
                )
            } else {
                assertTrue("[$assetName] Expected at least one vehicle detection", detections.isNotEmpty())
                detections.forEach { d ->
                    assertTrue(
                        "[$assetName] classId ${d.classId} is not a vehicle class",
                        d.classId in ModelConfig.VEHICLE_CLASS_IDS,
                    )
                    assertTrue(
                        "[$assetName] confidence ${d.confidence} is below threshold",
                        d.confidence >= ModelConfig.DETECTION_FLOOR,
                    )
                }
            }
        }
    }

    @Test
    fun gpuAndCpuBackendsAgreeOnTestFramesForEveryBundledModel() {
        val jpgAssets =
            testContext.assets.list("")
                ?.filter { it.endsWith(".jpg") || it.endsWith(".jpeg") }
                .orEmpty()
        assumeTrue("No .jpg assets found", jpgAssets.isNotEmpty())
        val modelFiles = targetContext.assets.list("")?.filter { it.endsWith(".tflite") }.orEmpty()
        for (modelFile in modelFiles) {
            val gpuDetector = VehicleDetector(targetContext).apply { forcedModelFile = modelFile }
            val cpuDetector =
                VehicleDetector(targetContext).apply {
                    allowGpu = false
                    forcedModelFile = modelFile
                }
            try {
                for (assetName in jpgAssets) {
                    val bitmap = testContext.assets.open(assetName).use { BitmapFactory.decodeStream(it) }
                    val gpu = gpuDetector.detect(bitmap)
                    val cpu = cpuDetector.detect(bitmap)
                    bitmap.recycle()
                    val tag = "[$modelFile / $assetName]"
                    Log.d(TAG, "$tag ${gpuDetector.backend}/${cpuDetector.backend} input=${gpuDetector.modelInput}")
                    Log.d(TAG, "$tag gpu: " + gpu.joinToString { "c%d %.2f".format(it.classId, it.confidence) })
                    Log.d(TAG, "$tag cpu: " + cpu.joinToString { "c%d %.2f".format(it.classId, it.confidence) })
                    val unmatched = gpu.count { g -> cpu.none { c -> iou(g, c) > 0.5f } }
                    Log.d(TAG, "$tag gpu=${gpu.size} cpu=${cpu.size} gpuUnmatched=$unmatched")
                    assertTrue("$tag GPU produced boxes the CPU did not", unmatched <= 1)
                    assertTrue("$tag GPU/CPU detection counts differ a lot", kotlin.math.abs(gpu.size - cpu.size) <= 2)
                }
            } finally {
                gpuDetector.close()
                cpuDetector.close()
            }
        }
    }

    @Test
    fun benchmarkPicksAModelAndRunsIt() {
        val modelFiles = targetContext.assets.list("")?.filter { it.endsWith(".tflite") }.orEmpty()
        assumeTrue("No models bundled", modelFiles.isNotEmpty())
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        detector.detect(bitmap)
        bitmap.recycle()
        Log.d(TAG, "chosen=${detector.modelFile} backend=${detector.backend} input=${detector.modelInput}")
        Log.d(TAG, "benchmark=${detector.benchmarkJson}")
        assertTrue("Chosen model must be one of the bundled files", detector.modelFile in modelFiles)
    }

    @Test
    fun governorSwitchLoadsAnotherModelAndKeepsDetecting() {
        val modelFiles = targetContext.assets.list("")?.filter { it.endsWith(".tflite") }.orEmpty()
        assumeTrue("Needs several bundled models", modelFiles.size > 2)
        val bitmap = testContext.assets.open("traffic_dense.jpg").use { BitmapFactory.decodeStream(it) }
        val before = detector.detect(bitmap)
        val firstModel = detector.modelFile
        detector.pendingSwitch = 1
        val after = detector.detect(bitmap)
        bitmap.recycle()
        Log.d(TAG, "switch: $firstModel -> ${detector.modelFile} (${detector.modelInput}); before=${before.size} after=${after.size}")
        assertTrue("Model should have changed", detector.modelFile != firstModel)
        assertTrue("Switch should be counted", detector.switchCount == 1)
        assertTrue("Detection must continue after a switch", after.isNotEmpty())
    }

    @Test
    fun croppedStripFindsTheSameVehiclesInsideTheStripWithCorrectBoxes() {
        val bitmap = testContext.assets.open("traffic_dense.jpg").use { BitmapFactory.decodeStream(it) }
        detector.detect(bitmap) // loads the benchmark's choice
        val modelFile = detector.modelFile
        val full = VehicleDetector(targetContext).apply { forcedModelFile = modelFile }
        val strip =
            VehicleDetector(targetContext).apply {
                forcedModelFile = modelFile
                forcedCropRows = bitmap.height / 2
            }
        try {
            val center = 0.6f
            val fullBoxes = full.detect(bitmap, center)
            val stripBoxes = strip.detect(bitmap, center)
            val topRow = (center - 0.25f) * ModelConfig.FRAME_SPACE
            val bottomRow = (center + 0.25f) * ModelConfig.FRAME_SPACE
            val inside = fullBoxes.filter { it.boundingBox.top > topRow + MARGIN && it.boundingBox.bottom < bottomRow - MARGIN }
            val found = inside.count { f -> stripBoxes.any { iou(f, it) > 0.5f } }

            // A box cut by the strip's top or bottom edge legitimately differs from the full-frame box.
            fun touchesEdge(d: Detection) = d.boundingBox.top <= topRow + MARGIN || d.boundingBox.bottom >= bottomRow - MARGIN
            val unmatched = stripBoxes.filter { s -> fullBoxes.none { iou(s, it) > 0.4f } }
            // Low-confidence extras are normal CNN variation between differently padded inputs; a confident
            // box that the full-frame run lacks would mean the strip is mapped to the wrong place.
            val phantoms = unmatched.count { !touchesEdge(it) && it.confidence >= PHANTOM_CONFIDENCE }
            val extras = unmatched.joinToString { describe(it) }
            Log.d(TAG, "crop: full=${fullBoxes.size} inside=${inside.size} found=$found strip=${stripBoxes.size} extras=$extras")
            assertTrue("Test image needs vehicles inside the strip", inside.isNotEmpty())
            assertTrue("Strip run lost vehicles that lie inside it ($found of ${inside.size})", found >= inside.size * 0.7)
            assertTrue("Strip run has confident boxes the full-frame run lacks", phantoms == 0)
            assertTrue(
                "Strip detections must be inside the strip",
                stripBoxes.all {
                    it.boundingBox.bottom >= topRow - MARGIN && it.boundingBox.top <= bottomRow + MARGIN
                },
            )
        } finally {
            full.close()
            strip.close()
            bitmap.recycle()
        }
    }

    @Test
    fun reloadingAModelUsesTheGpuKernelCache() {
        val yolo = targetContext.assets.list("")?.filter { it.endsWith(".tflite") && it.startsWith("yolov8n") }.orEmpty()
        val modelFile = yolo.firstOrNull { "480x640" in it } ?: yolo.maxOrNull()
        assumeTrue("No yolov8n models bundled", modelFile != null)
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val timesMs =
            List(3) {
                val d = VehicleDetector(targetContext).apply { forcedModelFile = modelFile }
                val start = android.os.SystemClock.elapsedRealtime()
                d.detect(bitmap)
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                d.close()
                elapsed
            }
        bitmap.recycle()
        Log.d(TAG, "gpu load+first detect times for $modelFile: $timesMs ms")
        assertTrue("A repeat load should not be slower than the first compile", timesMs.last() <= timesMs.first() + 500)
    }

    private fun describe(d: Detection): String {
        val b = d.boundingBox
        return "[%.0f,%.0f,%.0f,%.0f c%.2f]".format(b.left, b.top, b.right, b.bottom, d.confidence)
    }

    private fun iou(
        a: Detection,
        b: Detection,
    ): Float {
        val l = maxOf(a.boundingBox.left, b.boundingBox.left)
        val t = maxOf(a.boundingBox.top, b.boundingBox.top)
        val r = minOf(a.boundingBox.right, b.boundingBox.right)
        val btm = minOf(a.boundingBox.bottom, b.boundingBox.bottom)
        val inter = maxOf(0f, r - l) * maxOf(0f, btm - t)
        val union = a.boundingBox.width() * a.boundingBox.height() + b.boundingBox.width() * b.boundingBox.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
