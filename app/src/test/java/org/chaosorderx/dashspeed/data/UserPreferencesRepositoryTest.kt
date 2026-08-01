package org.chaosorderx.dashspeed.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesRepositoryTest : FreeSpec({

    val testScope = TestScope(UnconfinedTestDispatcher())
    lateinit var tmpDir: File

    beforeTest {
        tmpDir = Files.createTempDirectory("dashspeed_test").toFile()
    }

    afterTest {
        tmpDir.deleteRecursively()
    }

    fun createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { File(tmpDir, "test_prefs.preferences_pb") },
        )

    "defaults are applied when no prefs exist" {
        runTest {
            val repo = UserPreferencesRepository(createDataStore())
            val prefs = repo.preferences.first()
            prefs.alertThresholdMph.shouldBeWithinPercentageOf(15f, 1.0)
            prefs.alertCooldownSec shouldBe 8
        }
    }

    "update persists new alert threshold" {
        runTest {
            val repo = UserPreferencesRepository(createDataStore())
            repo.update { this[PreferencesKeys.ALERT_THRESHOLD_MPH] = 20f }
            val prefs = repo.preferences.first()
            prefs.alertThresholdMph.shouldBeWithinPercentageOf(20f, 1.0)
        }
    }

    "update persists brightness value" {
        runTest {
            val repo = UserPreferencesRepository(createDataStore())
            repo.update { this[PreferencesKeys.SCREEN_BRIGHTNESS] = 0.3f }
            val prefs = repo.preferences.first()
            prefs.screenBrightness.shouldBeWithinPercentageOf(0.3f, 1.0)
        }
    }
})
