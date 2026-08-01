package org.chaosorderx.dashspeed.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
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
                )
            }

        suspend fun update(block: suspend MutablePreferences.() -> Unit) {
            dataStore.edit(block)
        }
    }
