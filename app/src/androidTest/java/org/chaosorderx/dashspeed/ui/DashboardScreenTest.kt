package org.chaosorderx.dashspeed.ui

import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.chaosorderx.dashspeed.overlay.SpeedAnnotation
import org.chaosorderx.dashspeed.overlay.SpeedOverlayCanvas
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun speedBadgeRendersForFastApproachingVehicle() {
        composeTestRule.setContent {
            DashSpeedTheme {
                SpeedOverlayCanvas(
                    annotations =
                        listOf(
                            SpeedAnnotation(
                                trackId = 1,
                                modelBox = RectF(100f, 100f, 300f, 300f),
                                speedMph = 75f,
                                relativeMph = 15f,
                            ),
                        ),
                    tracks = emptyList(),
                    textMeasurer = rememberTextMeasurer(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("~75 mph").assertIsDisplayed()
    }
}
