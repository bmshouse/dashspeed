package org.chaosorderx.dashspeed.alerts

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import org.chaosorderx.dashspeed.data.UserPreferences
import org.chaosorderx.dashspeed.overlay.SpeedAnnotation
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal fun shouldAlert(
    annotations: List<SpeedAnnotation>,
    now: Long,
    lastAlertMs: Long,
    cooldownMs: Long,
    thresholdMph: Float,
): String? {
    if (now - lastAlertMs < cooldownMs) return null
    val fastest = annotations.minByOrNull { it.relativeMph } ?: return null
    return if (fastest.relativeMph < -thresholdMph) "Fast approach" else null
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

        fun evaluate(annotations: List<SpeedAnnotation>): String? {
            val now = System.currentTimeMillis()
            val message = shouldAlert(annotations, now, lastAlertMs, cooldownMs, thresholdMph)
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
