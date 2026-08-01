# DashSpeed

Real-time vehicle speed overlay for Android dashcams. Detects vehicles in the front camera feed, estimates their speeds using GPS and optical flow, and overlays mph labels on the live view.

Designed to be used landscape-mounted in a vehicle with a navigator monitoring the screen — no driver interaction required.

---

## Requirements

| Tool | Version |
|------|---------|
| JDK | 17 (Temurin recommended) |
| Android SDK | API 35 (Build Tools 35.0.0) |
| Android device | API 26+ (Android 8.0), physical device required (camera + GPS) |
| Gradle | Provided by the wrapper — no separate install needed |

### Windows note

All commands in this README use `./gradlew` (Unix shell script, works in **Git Bash** and on Linux/macOS CI). If you are using **PowerShell** or Command Prompt, substitute `.\gradlew.bat`:

```powershell
# PowerShell equivalent
.\gradlew.bat assembleDebug
```

After cloning on Linux or macOS, make the wrapper executable once:

```bash
chmod +x gradlew
```

---

## Model Asset

Detection requires a YOLOv8n INT8 TFLite model. The build will warn and skip downloading if the URL is not configured; the app launches without it but detection is disabled at runtime.

**Option A — auto-download:** Add to `gradle.properties`:
```
modelUrl=https://your-host/yolov8n_int8.tflite
```

**Option B — manual:** Place `yolov8n_int8.tflite` in `app/src/main/assets/`.

---

## Running Tests

```bash
# Lint and style
./gradlew ktlintCheck detekt

# Unit tests
./gradlew test

# Coverage gate (≥ 80% line, ≥ 70% branch)
./gradlew koverVerify

# Full CI check (all of the above + debug APK)
./gradlew ktlintCheck detekt test koverVerify assembleDebug
```

Coverage and test reports are written to `app/build/reports/`.

---

## Building the Debug APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Installing on a Device

Enable **USB debugging** on your Android device (`Settings → Developer options → USB debugging`), connect via USB, then:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

To reinstall over an existing build:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses application ID `org.chaosorderx.dashspeed.debug` and is independent of any release build.

### Runtime permissions

Grant these on first launch:

- **Camera** — live video feed
- **Fine Location** — GPS speed (ego vehicle)

---

## Releasing a Debug APK via GitHub Actions

Push a version tag to trigger a GitHub Release with the APK attached:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The release workflow builds the APK and publishes it as `dashspeed-v0.1.0-debug.apk` under **Releases** in the GitHub repository. Pre-built APKs can be downloaded and sideloaded without building locally.

CI checks (lint, tests, coverage) run automatically on every pull request to `main`.

---

## Project Structure

```
app/src/main/java/org/chaosorderx/dashspeed/
├── camera/          # CameraX frame capture and image analysis
├── detection/       # YOLOv8 TFLite inference
├── tracking/        # SORT multi-object tracker
├── speed/           # Speed estimation (homography + EMA smoothing)
├── gps/             # FusedLocationProvider ego-speed + heading guard
├── overlay/         # Canvas drawing and coordinate mapping
├── alerts/          # TTS alert engine
├── data/            # DataStore user preferences
├── di/              # Hilt modules
└── ui/
    ├── dashboard/   # Main camera view, HUD, MainViewModel
    ├── calibration/ # Homography calibration screen
    └── settings/    # Settings bottom sheet
```
