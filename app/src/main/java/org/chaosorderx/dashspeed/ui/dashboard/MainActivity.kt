package org.chaosorderx.dashspeed.ui.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dagger.hilt.android.AndroidEntryPoint
import org.chaosorderx.dashspeed.camera.CameraManager
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var cameraManager: CameraManager

    private val viewModel: MainViewModel by viewModels()

    override fun onDestroy() {
        cameraManager.release()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            DashSpeedTheme {
                PermissionGate {
                    val uiState by viewModel.uiState.collectAsState()
                    DashboardScreen(
                        uiState = uiState,
                        cameraManager = cameraManager,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}
