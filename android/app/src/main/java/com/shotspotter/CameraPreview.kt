package com.shotspotter

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CameraActions(
    val stepZoom: (Float) -> Unit,
    val resetZoom: () -> Unit,
    val setTorchEnabled: (Boolean) -> Unit
)

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner,
    analyzer: Analyzer,
    lockTarget: Boolean,
    onCameraActionsAvailable: (CameraActions) -> Unit
) {
    val context = LocalContext.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraState = remember { mutableStateOf<Camera?>(null) }
    val previewViewState = remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier
            .pointerInput(cameraState.value) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val camera = cameraState.value ?: return@detectTransformGestures
                    val zoomState = camera.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    val newZoomRatio = (zoomState.zoomRatio * zoomChange)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    camera.cameraControl.setZoomRatio(newZoomRatio)
                }
            }
            .pointerInput(cameraState.value, previewViewState.value, lockTarget) {
                detectTapGestures { offset ->
                    if (lockTarget) return@detectTapGestures
                    val camera = cameraState.value ?: return@detectTapGestures
                    val previewView = previewViewState.value ?: return@detectTapGestures
                    if (previewView.width == 0 || previewView.height == 0) {
                        return@detectTapGestures
                    }

                    val meteringPointFactory = SurfaceOrientedMeteringPointFactory(
                        previewView.width.toFloat(),
                        previewView.height.toFloat()
                    )
                    val meteringPoint = meteringPointFactory.createPoint(offset.x, offset.y)
                    val meteringAction = FocusMeteringAction.Builder(
                        meteringPoint,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    )
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build()

                    camera.cameraControl.startFocusAndMetering(meteringAction)
                }
            },
        factory = {
            PreviewView(it).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewViewState.value = this
                bindCamera(
                    context = it,
                    lifecycleOwner = lifecycleOwner,
                    previewView = this,
                    analyzer = analyzer,
                    analysisExecutor = analysisExecutor,
                    onCameraBound = { camera ->
                        cameraState.value = camera
                        onCameraActionsAvailable(
                            CameraActions(
                                stepZoom = zoomStepLambda@{ zoomStep ->
                                    val zoomState = camera.cameraInfo.zoomState.value
                                        ?: return@zoomStepLambda
                                    val newZoomRatio = (zoomState.zoomRatio + zoomStep)
                                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                                    camera.cameraControl.setZoomRatio(newZoomRatio)
                                },
                                resetZoom = {
                                    camera.cameraControl.setZoomRatio(1f)
                                },
                                setTorchEnabled = { enabled ->
                                    camera.cameraControl.enableTorch(enabled)
                                }
                            )
                        )
                    }
                )
            }
        }
    )

    DisposableEffect(lockTarget, cameraState.value) {
        if (lockTarget) {
            cameraState.value?.cameraControl?.cancelFocusAndMetering()
        }
        onDispose { }
    }
}

private fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzer: Analyzer,
    analysisExecutor: ExecutorService,
    onCameraBound: (Camera) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(selectImageAnalysisResolution(context))
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor, analyzer)
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            analysis
        )
        onCameraBound(camera)
    }, ContextCompat.getMainExecutor(context))
}

private fun selectImageAnalysisResolution(context: Context): Size {
    val fallback = Size(1280, 720)
    val preferred = Size(1920, 1080)
    return runCatching {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            lensFacing == CameraCharacteristics.LENS_FACING_BACK
        } ?: return fallback

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return fallback

        pickAnalysisResolution(map, preferred, fallback)
    }.getOrElse {
        Log.w("CameraPreview", "Failed to query analysis resolution; using fallback", it)
        fallback
    }
}

private fun pickAnalysisResolution(
    map: StreamConfigurationMap,
    preferred: Size,
    fallback: Size
): Size {
    val supported = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList().orEmpty()
    if (supported.contains(preferred)) return preferred
    if (supported.contains(fallback)) return fallback

    return supported
        .filter { it.width * 9 == it.height * 16 }
        .filter { it.width <= preferred.width && it.height <= preferred.height }
        .maxByOrNull { it.width * it.height }
        ?: fallback
}
