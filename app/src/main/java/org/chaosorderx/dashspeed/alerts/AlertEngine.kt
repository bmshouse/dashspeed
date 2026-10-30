package org.chaosorderx.dashspeed.alerts

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import org.chaosorderx.dashspeed.data.UserPreferences
import org.chaosorderx.dashspeed.tracking.OwnLaneApproach
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal const val APPROACH_SUSTAIN_MS = 1_000L
internal const val MPS_TO_MPH = 2.237f

/**
 * "Fast approach" when the nearest own-lane car has been closing at least [thresholdMph] with under 5 s to
 * contact for [APPROACH_SUSTAIN_MS]. Driven by box growth, not the pixel-row speed estimate, which field logs
 * showed to be unreliable for the large boxes of near cars.
 */
internal fun shouldAlert(
    approach: OwnLaneApproach?,
    now: Long,
    lastAlertMs: Long,
    cooldownMs: Long,
    thresholdMph: Float,
): String? {
    if (approach == null || now - lastAlertMs < cooldownMs) return null
    val sustained = approach.sustainedMs >= APPROACH_SUSTAIN_MS
    return if (sustained && approach.closingMps * MPS_TO_MPH >= thresholdMph) "Fast approach" else null
}

@Singleton
class AlertEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var tts: TextToSpeech? = null
        private var lastAlertMs = 0L

        @Volatile private var ttsReady = false

        @Volatile private var thresholdMph = 15f

        @Volatile private var cooldownMs = 8_000L

        init {
            tts =
                TextToSpeech(context) { status ->
                    if (status != TextToSpeech.SUCCESS) {
                        Timber.w("TTS initialization failed with status %d", status)
                    } else {
                        tts?.language = Locale.US
                        ttsReady = true
                    }
                }
        }

        fun updatePreferences(prefs: UserPreferences) {
            thresholdMph = prefs.alertThresholdMph
            cooldownMs = prefs.alertCooldownSec * 1000L
        }

        fun evaluate(approach: OwnLaneApproach?): String? {
            val now = System.currentTimeMillis()
            val message = shouldAlert(approach, now, lastAlertMs, cooldownMs, thresholdMph)
            if (message != null) {
                if (ttsReady) {
                    tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "alert")
                } else {
                    Timber.w("AlertEngine: TTS not ready, skipping audio for: %s", message)
                }
                lastAlertMs = now
            }
            return message
        }

        fun shutdown() {
            tts?.stop()
            tts?.shutdown()
        }
    }
