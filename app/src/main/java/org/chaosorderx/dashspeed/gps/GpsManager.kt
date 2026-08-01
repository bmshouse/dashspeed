package org.chaosorderx.dashspeed.gps

import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Returns the shortest angular difference between two compass bearings (0–360°). */
internal fun bearingDelta(
    a: Float,
    b: Float,
): Float {
    val diff = abs(a - b) % 360f
    return if (diff > 180f) 360f - diff else diff
}

@Singleton
class GpsManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        private val _egoSpeedMps = MutableStateFlow(0f)
        val egoSpeedMps: StateFlow<Float> = _egoSpeedMps

        private val _headingChangeTooFast = MutableStateFlow(false)

        /** True when the ego vehicle's heading is changing faster than 5 °/s (entering a curve). */
        val headingChangeTooFast: StateFlow<Boolean> = _headingChangeTooFast

        private var lastBearingDeg = Float.NaN
        private var lastBearingTimeMs = 0L

        private val locationRequest =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
                .setMinUpdateIntervalMillis(250L)
                .build()

        private val locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        if (location.hasSpeed()) {
                            _egoSpeedMps.value = location.speed
                        }
                        if (location.hasBearing()) {
                            val now = System.currentTimeMillis()
                            val bearing = location.bearing
                            if (!lastBearingDeg.isNaN() && now > lastBearingTimeMs) {
                                val dtSec = (now - lastBearingTimeMs) / 1_000f
                                val rateDegPerSec = bearingDelta(lastBearingDeg, bearing) / dtSec
                                _headingChangeTooFast.value = rateDegPerSec > 5f
                            }
                            lastBearingDeg = bearing
                            lastBearingTimeMs = now
                        }
                    }
                }
            }

        fun start() {
            try {
                fusedClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper(),
                )
                Timber.d("GpsManager: location updates started")
            } catch (e: SecurityException) {
                Timber.w(e, "GpsManager: location permission not granted")
            }
        }

        fun stop() {
            fusedClient.removeLocationUpdates(locationCallback)
            Timber.d("GpsManager: location updates stopped")
        }
    }
