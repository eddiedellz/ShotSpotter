package com.shotspotter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureNanoTime

class MainActivity : ComponentActivity() {

    private val latestFrameRef = AtomicReference<GrayFrame?>(null)
    private val detector = Detector()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ShotSpotterApp(
                    onFrameReady = { frame -> latestFrameRef.set(frame) },
                    getLatestFrame = { latestFrameRef.get() },
                    detector = detector
                )
            }
        }
    }
}

@Composable
private fun ShotSpotterApp(
    onFrameReady: (GrayFrame) -> Unit,
    getLatestFrame: () -> GrayFrame?,
    detector: Detector
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var baselineFrame by remember { mutableStateOf<GrayFrame?>(null) }
    var candidates by remember { mutableStateOf<List<HoleCandidate>>(emptyList()) }
    var strongest by remember { mutableStateOf<HoleCandidate?>(null) }
    var confirmedDetection by remember { mutableStateOf(false) }
    var detectionConfidence by remember { mutableStateOf(0f) }
    var frameTimeMs by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("Camera ready") }
    var roi by remember { mutableStateOf(RoiNorm.DEFAULT) }
    var showDebugOverlay by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        statusText = if (granted) "Camera ready" else "Camera permission required"
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val latestRoi = rememberUpdatedState(roi)

    val analyzer = remember {
        Analyzer(
            getRoi = { latestRoi.value },
            onFrameReady = onFrameReady
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    lifecycleOwner = lifecycleOwner,
                    analyzer = analyzer
                )
                TargetRoiOverlay(
                    roi = roi,
                    onRoiChange = { roi = it },
                    showOverlay = showDebugOverlay,
                    modifier = Modifier.fillMaxSize()
                )
                if (showDebugOverlay) {
                    ShotOverlay(
                        roi = roi,
                        candidates = candidates,
                        strongest = strongest,
                        confirmedDetection = confirmedDetection,
                        detectionConfidence = detectionConfidence,
                        frameTimeMs = frameTimeMs,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Text(
                    text = "Camera permission is required.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = statusText)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Confirmed detection: $confirmedDetection | Confidence: " +
                        String.format(Locale.US, "%.2f", detectionConfidence)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Debug Overlay")
                    Switch(
                        checked = showDebugOverlay,
                        onCheckedChange = { showDebugOverlay = it }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val latest = getLatestFrame()
                        if (latest != null) {
                            baselineFrame = latest.copy(pixels = latest.pixels.copyOf())
                            detector.resetStability()
                            candidates = emptyList()
                            strongest = null
                            confirmedDetection = false
                            detectionConfidence = 0f
                            frameTimeMs = 0f
                            statusText = "Baseline captured @ ${latest.timestampNanos}"
                        } else {
                            statusText = "No analyzed frame available yet"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Set Baseline")
                }

                Button(
                    onClick = {
                        val baseline = baselineFrame
                        val latest = getLatestFrame()
                        if (baseline == null) {
                            statusText = "Set baseline first"
                            return@Button
                        }
                        if (latest == null) {
                            statusText = "No analyzed frame available yet"
                            return@Button
                        }

                        if (baseline.width != latest.width || baseline.height != latest.height) {
                            detector.resetStability()
                            confirmedDetection = false
                            detectionConfidence = 0f
                            frameTimeMs = 0f
                            statusText = "ROI changed after baseline; set baseline again"
                            return@Button
                        }

                        var result: DetectionResult? = null
                        val elapsedNs = measureNanoTime {
                            result = detector.detect(baseline = baseline, current = latest)
                        }
                        val detection = result ?: return@Button

                        candidates = detection.candidates
                        strongest = detection.strongest
                        confirmedDetection = detection.confirmedDetection
                        detectionConfidence = detection.confidence
                        frameTimeMs = elapsedNs / 1_000_000f
                        statusText = if (detection.framePositive) {
                            "Frame positive. Confirmed=${detection.confirmedDetection} (${(detection.confidence * 100).toInt()}%)"
                        } else {
                            "No frame hit. Confirmed=${detection.confirmedDetection} (${(detection.confidence * 100).toInt()}%)"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Next Shot")
                }

                Button(
                    onClick = {
                        baselineFrame = null
                        detector.resetStability()
                        candidates = emptyList()
                        strongest = null
                        confirmedDetection = false
                        detectionConfidence = 0f
                        frameTimeMs = 0f
                        statusText = "Cleared baseline and overlays"
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }
            }

            Button(
                onClick = { roi = RoiNorm.DEFAULT },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Reset ROI")
            }
        }
    }
}
