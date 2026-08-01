package org.chaosorderx.dashspeed.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.chaosorderx.dashspeed.detection.VehicleDetector
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@Ignore("Instrumented — requires physical device with camera; enable manually")
@RunWith(AndroidJUnit4::class)
class CameraManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val detector = VehicleDetector(context)
    private val processor = FrameProcessor(detector)
    private val manager = CameraManager(context, processor)

    @Test
    fun cameraManagerConstructsWithoutThrowing() {
        // Verifies the dependency graph compiles and initialises
    }

    @Test
    fun frameProcessorEmitsDetectionsAtTargetFps() {
        // Full test: bind to a real camera, collect 90 frames, assert delivery within 6 s (~15 FPS min).
        // Requires running under HiltAndroidTest with a real camera lifecycle.
        // Placeholder until Phase 1 device validation session.
    }
}
