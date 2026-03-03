# ShotSpotter

Project documentation is tracked in [FEATURES.md](FEATURES.md).

## Android Studio setup (fix for "Module not specified")

This repository's Android Gradle project lives in the `android/` directory.

1. Open `android/` (not the repository root) in Android Studio.
2. Ensure Android SDK is configured:
   - **Option A (recommended per-machine):** set environment variable `ANDROID_SDK_ROOT`.
   - **Option B (project-local):** create `android/local.properties` with:
     ```properties
     sdk.dir=C:\\Users\\<YOUR_USERNAME>\\AppData\\Local\\Android\\Sdk
     ```
3. Sync Gradle and run the `app` configuration.

If you are on Windows PowerShell and want to set SDK location globally, run:

```powershell
setx ANDROID_SDK_ROOT "C:\Users\<YOUR_USERNAME>\AppData\Local\Android\Sdk"
```

Then restart Android Studio and re-sync the project.
