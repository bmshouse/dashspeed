package org.chaosorderx.dashspeed.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val frameProcessor: FrameProcessor,
    ) {
        private val analysisExecutor = Executors.newSingleThreadExecutor()

        fun bindPreview(
            previewView: PreviewView,
            lifecycleOwner: LifecycleOwner,
        ) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { bindUseCases(future.get(), previewView, lifecycleOwner) },
                ContextCompat.getMainExecutor(context),
            )
        }

        fun release() {
            analysisExecutor.shutdown()
        }

        private fun bindUseCases(
            cameraProvider: ProcessCameraProvider,
            previewView: PreviewView,
            lifecycleOwner: LifecycleOwner,
        ) {
            val preview =
                Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            val imageAnalysis =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, frameProcessor.analyzer) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (e: IllegalStateException) {
                Timber.e(e, "CameraManager: camera bind failed")
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "CameraManager: camera bind failed — lifecycle owner already destroyed")
            }
        }
    }
