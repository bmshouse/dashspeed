package org.chaosorderx.dashspeed.ui.calibration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTheme

@AndroidEntryPoint
class CalibrationActivity : ComponentActivity() {
    private val viewModel: CalibrationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DashSpeedTheme {
                CalibrationScreen(viewModel = viewModel)
            }
        }
    }
}
