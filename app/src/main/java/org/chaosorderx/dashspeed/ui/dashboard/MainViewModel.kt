package org.chaosorderx.dashspeed.ui.dashboard

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.chaosorderx.dashspeed.BuildConfig
import org.chaosorderx.dashspeed.alerts.AlertEngine
import org.chaosorderx.dashspeed.camera.FrameProcessor
import org.chaosorderx.dashspeed.data.PreferencesKeys
import org.chaosorderx.dashspeed.data.UserPreferences
import org.chaosorderx.dashspeed.data.UserPreferencesRepository
import org.chaosorderx.dashspeed.debug.DebugRecorder
import org.chaosorderx.dashspeed.detection.BandLearner
import org.chaosorderx.dashspeed.detection.ModelConfig
import org.chaosorderx.dashspeed.detection.StaticMask
import org.chaosorderx.dashspeed.detection.StaticMaskLearner
import org.chaosorderx.dashspeed.detection.VehicleDetector
import org.chaosorderx.dashspeed.gps.GpsManager
import org.chaosorderx.dashspeed.overlay.SpeedAnnotation
import org.chaosorderx.dashspeed.speed.SpeedEstimator
import org.chaosorderx.dashspeed.tracking.LaneAnalyzer
import org.chaosorderx.dashspeed.tracking.LaneReport
import org.chaosorderx.dashspeed.tracking.LaneSummary
import org.chaosorderx.dashspeed.tracking.LeadTracker
import org.chaosorderx.dashspeed.tracking.LeadVehicle
import org.chaosorderx.dashspeed.tracking.SortTracker
import org.chaosorderx.dashspeed.tracking.TrackedVehicle
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class DashboardUiState(
    val annotations: List<SpeedAnnotation> = emptyList(),
    val tracks: List<TrackedVehicle> = emptyList(),
    val egoSpeedMph: Float = 0f,
    val trackCount: Int = 0,
    val alertMessage: String? = null,
    val lead: LeadVehicle? = null,
    val lanes: List<LaneSummary> = emptyList(),
)

private const val MPS_TO_MPH = 2.237f

private fun lanesJson(lanes: List<LaneSummary>): JSONArray =
    JSONArray().also { array ->
        lanes.forEach {
            array.put(
                JSONObject()
                    .put("lane", it.lane)
                    .put("cars", it.cars)
                    .put("closeMps", it.closingMps?.toDouble() ?: JSONObject.NULL),
            )
        }
    }

@Suppress("TooManyFunctions") // One small setter per preference.
@HiltViewModel
class MainViewModel
    @Suppress("LongParameterList") // Hilt-injected singletons; grouping them would only add indirection.
    @Inject
    constructor(
        private val frameProcessor: FrameProcessor,
        private val sortTracker: SortTracker,
        private val speedEstimator: SpeedEstimator,
        private val gpsManager: GpsManager,
        private val alertEngine: AlertEngine,
        private val vehicleDetector: VehicleDetector,
        private val staticMaskLearner: StaticMaskLearner,
        private val bandLearner: BandLearner,
        private val debugRecorder: DebugRecorder,
        private val prefsRepository: UserPreferencesRepository,
    ) : ViewModel() {
        // Declared before `init`: its collectors may run immediately and use these.
        private val leadTracker = LeadTracker()
        private val laneAnalyzer = LaneAnalyzer()

        init {
            gpsManager.start()
            viewModelScope.launch {
                prefsRepository.preferences.collect { prefs ->
                    alertEngine.updatePreferences(prefs)
                    speedEstimator.setCalibration(prefs.homographyMatrixJson)
                    staticMaskLearner.load(prefs.staticMaskBits)
                    bandLearner.load(prefs.bandCenterFrac)
                    laneAnalyzer.load(prefs.roadCenterX)
                    if (debugRecorder.enabled) debugRecorder.writeJson("prefs.json", debugPrefsJson(prefs))
                }
            }
            viewModelScope.launch {
                staticMaskLearner.learned.collect { bits ->
                    prefsRepository.update { this[PreferencesKeys.STATIC_MASK] = bits }
                }
            }
            viewModelScope.launch {
                bandLearner.learnedFlow.collect { center ->
                    prefsRepository.update { this[PreferencesKeys.BAND_CENTER] = center }
                }
            }
            viewModelScope.launch {
                laneAnalyzer.learnedFlow.collect { centerX ->
                    prefsRepository.update { this[PreferencesKeys.ROAD_CENTER_X] = centerX }
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
                    val report = laneAnalyzer.update(tracks, frame.timestampMs)
                    // "Fast approach" comes from the own-lane car's box growth (time to contact), not from speeds.
                    val alertMessage =
                        alertEngine.evaluate(report.approach.takeUnless { gpsManager.headingChangeTooFast.value })
                    val byTrack = annotations.associateBy { it.trackId }
                    val lead = leadTracker.update(tracks, byTrack, frame.timestampMs, report.laneByTrack)
                    if (debugRecorder.enabled) {
                        logDebugTracks(frame.timestampMs, egoSpeed, tracks, annotations, lead, report)
                    }
                    DashboardUiState(
                        annotations = annotations,
                        tracks = tracks,
                        egoSpeedMph = egoSpeed * MPS_TO_MPH,
                        trackCount = tracks.size,
                        alertMessage = alertMessage,
                        lead = lead,
                        lanes = report.lanes,
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

        /** Debug builds: flag this moment in the session log and save a full-resolution frame. */
        fun markMoment() = debugRecorder.mark()

        val debugEnabled: Boolean get() = debugRecorder.enabled

        private var lastTrackIds = emptySet<Int>()

        @Suppress("LongParameterList")
        private fun logDebugTracks(
            timestampMs: Long,
            egoSpeedMps: Float,
            tracks: List<TrackedVehicle>,
            annotations: List<SpeedAnnotation>,
            lead: LeadVehicle?,
            report: LaneReport,
        ) {
            val speeds = annotations.associateBy { it.trackId }
            val trackArray = JSONArray()
            tracks.forEach { t ->
                trackArray.put(
                    JSONObject()
                        .put("id", t.id)
                        .put(
                            "box",
                            JSONArray(
                                listOf(
                                    t.boundingBox.left,
                                    t.boundingBox.top,
                                    t.boundingBox.right,
                                    t.boundingBox.bottom,
                                ).map { it.toDouble() },
                            ),
                        )
                        .put("lane", report.laneByTrack[t.id] ?: JSONObject.NULL)
                        .put("samples", t.history.size)
                        .put("coast", t.coasting)
                        .put("approx", speeds[t.id]?.approximate ?: false)
                        .put("mph", speeds[t.id]?.speedMph?.toDouble() ?: JSONObject.NULL)
                        .put("relMph", speeds[t.id]?.relativeMph?.toDouble() ?: JSONObject.NULL),
                )
            }
            debugRecorder.appendLine(
                JSONObject().put("type", "tracks").put("t", timestampMs)
                    .put("egoMps", egoSpeedMps.toDouble()).put("tracks", trackArray)
                    .put("lead", lead?.trackId ?: JSONObject.NULL)
                    .put("leadM", lead?.distanceMeters?.toDouble() ?: JSONObject.NULL)
                    .put("vp", report.roadCenterX.toDouble())
                    .put("lanes", lanesJson(report.lanes))
                    .put("ttc", report.approach?.ttcSec?.toDouble() ?: JSONObject.NULL),
            )
            val ids = tracks.map { it.id }.toSet()
            if (ids != lastTrackIds) debugRecorder.requestSnapshot("track_change")
            lastTrackIds = ids
        }

        private fun debugPrefsJson(prefs: UserPreferences): JSONObject =
            JSONObject()
                .put("mountHeightCm", prefs.mountHeightCm.toDouble())
                .put("tiltAngleDeg", prefs.tiltAngleDeg.toDouble())
                .put("alertThresholdMph", prefs.alertThresholdMph.toDouble())
                .put("homography", prefs.homographyMatrixJson)
                .put("detectionFloor", ModelConfig.DETECTION_FLOOR.toDouble())
                .put("trackStartConfidence", ModelConfig.TRACK_START_CONFIDENCE.toDouble())
                .put("staticMaskOverlap", ModelConfig.STATIC_MASK_OVERLAP.toDouble())
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")

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

        /** Forgets the learned hood/mount mask and learns it again (call after moving the phone). */
        fun relearnStaticAreas() {
            viewModelScope.launch {
                // Clear the saved mask first so a stale prefs emission can't restore it after reset.
                prefsRepository.update {
                    remove(PreferencesKeys.STATIC_MASK)
                    remove(PreferencesKeys.BAND_CENTER)
                    remove(PreferencesKeys.ROAD_CENTER_X)
                }
                staticMaskLearner.reset()
                bandLearner.reset()
                laneAnalyzer.reset()
            }
        }

        /** Saves a hand-painted mask and stops auto-learning. */
        fun setStaticMask(mask: StaticMask) = staticMaskLearner.setManual(mask)

        fun updateShowStaticOverlay(show: Boolean) {
            viewModelScope.launch {
                prefsRepository.update { this[PreferencesKeys.SHOW_STATIC_OVERLAY] = show }
            }
        }

        fun updateShowSpeedTags(show: Boolean) {
            viewModelScope.launch {
                prefsRepository.update { this[PreferencesKeys.SHOW_SPEED_TAGS] = show }
            }
        }

        val staticMask: StateFlow<StaticMask> = staticMaskLearner.maskFlow
        val staticLearning: StateFlow<Boolean> = staticMaskLearner.learningFlow

        fun updateBrightness(value: Float) {
            viewModelScope.launch {
                prefsRepository.update { this[PreferencesKeys.SCREEN_BRIGHTNESS] = value }
            }
        }
    }
