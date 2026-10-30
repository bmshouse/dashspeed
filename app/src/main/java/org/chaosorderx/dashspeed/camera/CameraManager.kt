package org.chaosorderx.dashspeed.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import org.chaosorderx.dashspeed.detection.ModelChooser
import org.chaosorderx.dashspeed.detection.ModelSelector
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

        private companion object {
            const val ASPECT_W = 4
            const val ASPECT_H = 3
        }

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

        /**
         * Analysis frames are 640 px wide unless the chosen model is a band model that benefits from more
         * detail (the width it was chosen with is remembered from the previous benchmark). Still 4:3.
         */
        private fun analysisSelector(): ResolutionSelector {
            val width = ModelChooser.savedAnalysisWidth(context)
            val builder =
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            if (width > ModelSelector.REFERENCE_FRAME_WIDTH) {
                val size = Size(width, width * ASPECT_H / ASPECT_W)
                builder.setResolutionStrategy(
                    ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
            }
            return builder.build()
        }

        private fun bindUseCases(
            cameraProvider: ProcessCameraProvider,
            previewView: PreviewView,
            lifecycleOwner: LifecycleOwner,
        ) {
            // Same aspect ratio on both use cases so the analysed frame has the same field of
            // view as the preview, which the overlay relies on to place boxes.
            val resolutionSelector =
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            val preview =
                Preview.Builder().setResolutionSelector(resolutionSelector).build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            val imageAnalysis =
                ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector())
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
