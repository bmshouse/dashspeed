package org.chaosorderx.dashspeed.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

object PreferencesKeys {
    val MOUNT_HEIGHT_CM = floatPreferencesKey("mount_height_cm")
    val TILT_ANGLE_DEG = floatPreferencesKey("tilt_angle_deg")
    val ALERT_THRESHOLD_MPH = floatPreferencesKey("alert_threshold_mph")
    val ALERT_COOLDOWN_SEC = intPreferencesKey("alert_cooldown_sec")
    val SCREEN_BRIGHTNESS = floatPreferencesKey("screen_brightness")
    val HOMOGRAPHY_MATRIX = stringPreferencesKey("homography_matrix_json")
    val STATIC_MASK = stringPreferencesKey("static_mask_bits")
    val SHOW_STATIC_OVERLAY = booleanPreferencesKey("show_static_overlay")
    val SHOW_SPEED_TAGS = booleanPreferencesKey("show_speed_tags")
    val BAND_CENTER = floatPreferencesKey("band_center_frac_v2") // v1 was polluted by hood detections
    val ROAD_CENTER_X = floatPreferencesKey("road_center_x")
}

class UserPreferencesRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val preferences: Flow<UserPreferences> =
            dataStore.data.map { prefs ->
                UserPreferences(
                    mountHeightCm = prefs[PreferencesKeys.MOUNT_HEIGHT_CM] ?: 120f,
                    tiltAngleDeg = prefs[PreferencesKeys.TILT_ANGLE_DEG] ?: 5f,
                    alertThresholdMph = prefs[PreferencesKeys.ALERT_THRESHOLD_MPH] ?: 15f,
                    alertCooldownSec = prefs[PreferencesKeys.ALERT_COOLDOWN_SEC] ?: 8,
                    screenBrightness = prefs[PreferencesKeys.SCREEN_BRIGHTNESS] ?: 0.7f,
                    homographyMatrixJson = prefs[PreferencesKeys.HOMOGRAPHY_MATRIX] ?: "",
                    staticMaskBits = prefs[PreferencesKeys.STATIC_MASK] ?: "",
                    showStaticOverlay = prefs[PreferencesKeys.SHOW_STATIC_OVERLAY] ?: false,
                    showSpeedTags = prefs[PreferencesKeys.SHOW_SPEED_TAGS] ?: false,
                    bandCenterFrac = prefs[PreferencesKeys.BAND_CENTER] ?: 0f,
                    roadCenterX = prefs[PreferencesKeys.ROAD_CENTER_X] ?: 0f,
                )
            }

        suspend fun update(block: suspend MutablePreferences.() -> Unit) {
            dataStore.edit(block)
        }
    }
