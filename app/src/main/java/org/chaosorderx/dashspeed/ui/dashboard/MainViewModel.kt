package org.chaosorderx.dashspeed.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.chaosorderx.dashspeed.alerts.AlertEngine
import org.chaosorderx.dashspeed.camera.FrameProcessor
import org.chaosorderx.dashspeed.data.PreferencesKeys
import org.chaosorderx.dashspeed.data.UserPreferences
import org.chaosorderx.dashspeed.data.UserPreferencesRepository
import org.chaosorderx.dashspeed.detection.VehicleDetector
import org.chaosorderx.dashspeed.gps.GpsManager
import org.chaosorderx.dashspeed.overlay.SpeedAnnotation
import org.chaosorderx.dashspeed.speed.SpeedEstimator
import org.chaosorderx.dashspeed.tracking.SortTracker
import org.chaosorderx.dashspeed.tracking.TrackedVehicle
import javax.inject.Inject

data class DashboardUiState(
    val annotations: List<SpeedAnnotation> = emptyList(),
    val tracks: List<TrackedVehicle> = emptyList(),
    val egoSpeedMph: Float = 0f,
    val trackCount: Int = 0,
    val alertMessage: String? = null,
)

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val frameProcessor: FrameProcessor,
        private val sortTracker: SortTracker,
        private val speedEstimator: SpeedEstimator,
        private val gpsManager: GpsManager,
        private val alertEngine: AlertEngine,
        private val vehicleDetector: VehicleDetector,
        private val prefsRepository: UserPreferencesRepository,
    ) : ViewModel() {
        init {
            gpsManager.start()
            viewModelScope.launch {
                prefsRepository.preferences.collect { prefs ->
                    alertEngine.updatePreferences(prefs)
                    speedEstimator.setCalibration(prefs.homographyMatrixJson)
                }
            }
        }

        override fun onCleared() {
            gpsManager.stop()
            vehicleDetector.close()
            alertEngine.shutdown()
            super.onCleared()
        }

        val prefs: kotlinx.coroutines.flow.StateFlow<UserPreferences> =
            prefsRepository.preferences
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

        // Driven by camera frames only: the tracker must advance exactly once per frame.
        // Combining GPS flows as triggers re-ran it with the same cached detections on every
        // GPS tick, injecting duplicate zero-motion history samples. GPS values are StateFlows,
        // so the freshest speed/heading are sampled per frame instead.
        val uiState =
            frameProcessor.detectionFlow
                .map { frame ->
                    val egoSpeed = gpsManager.egoSpeedMps.value
                    val tracks = sortTracker.update(frame.detections, frame.timestampMs)
                    speedEstimator.cleanupTracks(tracks.map { it.id }.toSet())
                    val rawAnnotations = tracks.mapNotNull { speedEstimator.estimate(it, egoSpeed) }
                    val annotations = if (gpsManager.headingChangeTooFast.value) emptyList() else rawAnnotations
                    val alertMessage = alertEngine.evaluate(annotations)
                    DashboardUiState(
                        annotations = annotations,
                        tracks = tracks,
                        egoSpeedMph = egoSpeed * 2.237f,
                        trackCount = tracks.size,
                        alertMessage = alertMessage,
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

        fun updateAlertThreshold(mph: Float) {
            viewModelScope.launch {
                prefsRepository.update { this[PreferencesKeys.ALERT_THRESHOLD_MPH] = mph }
            }
        }

        fun updateAlertCooldown(sec: Int) {
            viewModelScope.launch {
                prefsRepository.update { this[PreferencesKeys.ALERT_COOLDOWN_SEC] = sec }
            }
        }

        fun updateBrightness(value: Float) {
            viewModelScope.launch {
                prefsRepository.update { this[PreferencesKeys.SCREEN_BRIGHTNESS] = value }
            }
        }
    }
