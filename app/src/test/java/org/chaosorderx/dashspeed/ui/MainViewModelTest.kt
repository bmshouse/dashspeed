package org.chaosorderx.dashspeed.ui

import app.cash.turbine.test
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.chaosorderx.dashspeed.camera.FrameDetections
import org.chaosorderx.dashspeed.camera.FrameProcessor
import org.chaosorderx.dashspeed.data.UserPreferences
import org.chaosorderx.dashspeed.data.UserPreferencesRepository
import org.chaosorderx.dashspeed.gps.GpsManager
import org.chaosorderx.dashspeed.speed.HomographyCalibrator
import org.chaosorderx.dashspeed.speed.SpeedEstimator
import org.chaosorderx.dashspeed.tracking.SortTracker
import org.chaosorderx.dashspeed.ui.dashboard.MainViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest : FreeSpec({
    val testDispatcher = UnconfinedTestDispatcher()

    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

    fun fakePrefsRepo(): UserPreferencesRepository =
        mockk<UserPreferencesRepository>(relaxed = true).also {
            every { it.preferences } returns flowOf(UserPreferences())
        }

    fun buildViewModel(
        detectionFlow: MutableSharedFlow<FrameDetections>,
        speedFlow: MutableStateFlow<Float>,
    ): MainViewModel {
        val mockGpsManager = mockk<GpsManager>(relaxed = true)
        every { mockGpsManager.egoSpeedMps } returns speedFlow
        every { mockGpsManager.headingChangeTooFast } returns MutableStateFlow(false)

        val mockFrameProcessor = mockk<FrameProcessor>(relaxed = true)
        every { mockFrameProcessor.detectionFlow } returns detectionFlow

        return MainViewModel(
            frameProcessor = mockFrameProcessor,
            sortTracker = SortTracker(),
            speedEstimator = SpeedEstimator(HomographyCalibrator()),
            gpsManager = mockGpsManager,
            alertEngine = mockk(relaxed = true),
            vehicleDetector = mockk(relaxed = true),
            prefsRepository = fakePrefsRepo(),
        )
    }

    "uiState reflects the latest GPS speed on the next camera frame" {
        runTest(testDispatcher) {
            val speedFlow = MutableStateFlow(0f)
            val detectionFlow = MutableSharedFlow<FrameDetections>(replay = 1)
            detectionFlow.tryEmit(FrameDetections(emptyList(), timestampMs = 0L))

            val viewModel = buildViewModel(detectionFlow, speedFlow)

            viewModel.uiState.test {
                awaitItem().egoSpeedMph shouldBe 0f
                speedFlow.value = 26.8f
                detectionFlow.tryEmit(FrameDetections(emptyList(), timestampMs = 33L))
                awaitItem().egoSpeedMph.shouldBeWithinPercentageOf(60f, 2.0)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    "uiState egoSpeedMph converts m/s to mph correctly" {
        runTest(testDispatcher) {
            val speedFlow = MutableStateFlow(0f)
            val detectionFlow = MutableSharedFlow<FrameDetections>(replay = 1)
            detectionFlow.tryEmit(FrameDetections(emptyList(), timestampMs = 0L))

            val viewModel = buildViewModel(detectionFlow, speedFlow)

            viewModel.uiState.test {
                awaitItem()
                speedFlow.value = 44.7f // 100 mph in m/s
                detectionFlow.tryEmit(FrameDetections(emptyList(), timestampMs = 33L))
                awaitItem().egoSpeedMph.shouldBeWithinPercentageOf(100f, 2.0)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    "a GPS update alone does not re-run the tracker pipeline" {
        runTest(testDispatcher) {
            val speedFlow = MutableStateFlow(0f)
            val detectionFlow = MutableSharedFlow<FrameDetections>(replay = 1)
            detectionFlow.tryEmit(FrameDetections(emptyList(), timestampMs = 0L))

            val viewModel = buildViewModel(detectionFlow, speedFlow)

            viewModel.uiState.test {
                awaitItem()
                // GPS ticks without a new frame must not produce new pipeline runs/emissions.
                speedFlow.value = 10f
                speedFlow.value = 20f
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
})
