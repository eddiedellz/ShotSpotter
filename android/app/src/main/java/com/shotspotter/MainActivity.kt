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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicReference

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
    var statusText by remember { mutableStateOf("Camera ready") }
    var roi by remember { mutableStateOf(RoiNorm.DEFAULT) }

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

    val analyzer = remember {
        Analyzer(onFrameReady = onFrameReady)
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
                    modifier = Modifier.fillMaxSize()
                )
                ShotOverlay(
                    candidates = candidates,
                    strongest = strongest,
                    modifier = Modifier.fillMaxSize()
                )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val latest = getLatestFrame()
                        if (latest != null) {
                            baselineFrame = latest.copy(pixels = latest.pixels.copyOf())
                            candidates = emptyList()
                            strongest = null
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

                        val result = detector.detect(baseline = baseline, current = latest, roi = roi)
                        candidates = result.candidates
                        strongest = result.strongest
                        statusText = if (result.strongest != null) {
                            "Detected ${result.candidates.size} candidates, LAST highlighted"
                        } else {
                            "No new shot detected"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Next Shot")
                }

                Button(
                    onClick = {
                        baselineFrame = null
                        candidates = emptyList()
                        strongest = null
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
