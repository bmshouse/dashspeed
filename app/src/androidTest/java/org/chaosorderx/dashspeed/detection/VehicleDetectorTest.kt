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
            detections.all { it.confidence >= ModelConfig.CONFIDENCE_THRESHOLD },
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

            // Images named with "distant" are expected to produce no detections — vehicles are
            // too small in frame for the 416 px model to exceed the confidence threshold.
            if ("distant" in assetName) {
                assertTrue("[$assetName] Expected no detections for distant scene", detections.isEmpty())
            } else {
                assertTrue("[$assetName] Expected at least one vehicle detection", detections.isNotEmpty())
                detections.forEach { d ->
                    assertTrue(
                        "[$assetName] classId ${d.classId} is not a vehicle class",
                        d.classId in ModelConfig.VEHICLE_CLASS_IDS,
                    )
                    assertTrue(
                        "[$assetName] confidence ${d.confidence} is below threshold",
                        d.confidence >= ModelConfig.CONFIDENCE_THRESHOLD,
                    )
                }
            }
        }
    }
}
