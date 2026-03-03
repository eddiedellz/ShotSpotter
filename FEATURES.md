# Feature Tracker

## Milestone 1 (MVP Scaffold)

### Camera
- ✅ Live camera preview using CameraX
- ✅ ImageAnalysis with `STRATEGY_KEEP_ONLY_LATEST`
- ✅ Single-threaded executor for analysis
- ✅ Portrait handling

### UI
- ✅ Buttons: `Set Baseline`, `Next Shot`, `Clear`
- ✅ Compose UI only (no XML layouts)

### Baseline Logic
- ✅ On `Set Baseline`, store most recent analyzed grayscale frame
- ✅ Store as downscaled grayscale `ByteArray`

### Detection Logic (on `Next Shot`)
- ✅ Capture most recent analyzed frame
- ✅ Compute absolute difference vs baseline
- ✅ Threshold
- ✅ Connected components (BFS)
- ✅ Filter blobs by area
- ✅ Filter blobs by aspect ratio
- ✅ Score blobs by mean diff intensity
- ✅ Select strongest blob as LAST
- ✅ Overlay circles on preview
- ✅ Highlight strongest candidate as `LAST`

### Coordinate Rules
- ✅ Overlay coordinates are normalized (0.0–1.0)
- ✅ Overlay scales to any screen size

### Tech Constraints
- ✅ Kotlin
- ✅ Jetpack Compose
- ✅ CameraX
- ✅ No OpenCV
- ✅ No third-party computer vision libraries
- ✅ Designed to run on real device
- ✅ No heavy work on UI thread
- ✅ Baseline/current frames stored as lightweight downscaled grayscale buffers

## Milestone 2
- ⬜ Target locking / alignment
- ⬜ Homography / stabilization
