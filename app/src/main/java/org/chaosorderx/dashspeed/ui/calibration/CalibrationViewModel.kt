package org.chaosorderx.dashspeed.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.chaosorderx.dashspeed.data.PreferencesKeys
import org.chaosorderx.dashspeed.data.UserPreferences
import org.chaosorderx.dashspeed.data.UserPreferencesRepository
import org.chaosorderx.dashspeed.speed.HomographyCalibrator
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel
    @Inject
    constructor(
        private val prefsRepository: UserPreferencesRepository,
        private val calibrator: HomographyCalibrator,
    ) : ViewModel() {
        val prefs: StateFlow<UserPreferences> =
            prefsRepository.preferences
                .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

        fun setMountHeight(cm: Float) {
            viewModelScope.launch {
                val matrixJson = calibrator.calibrate(cm, prefs.value.tiltAngleDeg)
                prefsRepository.update {
                    this[PreferencesKeys.MOUNT_HEIGHT_CM] = cm
                    this[PreferencesKeys.HOMOGRAPHY_MATRIX] = matrixJson
                }
            }
        }

        fun setTiltAngle(deg: Float) {
            viewModelScope.launch {
                val matrixJson = calibrator.calibrate(prefs.value.mountHeightCm, deg)
                prefsRepository.update {
                    this[PreferencesKeys.TILT_ANGLE_DEG] = deg
                    this[PreferencesKeys.HOMOGRAPHY_MATRIX] = matrixJson
                }
            }
        }
    }
