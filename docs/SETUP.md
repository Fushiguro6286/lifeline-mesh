# Lifeline Mesh: setup, downloads and dependencies

Everything that must be installed or downloaded. Versions marked **checked** were confirmed against official sources on 9 Oct 2026; everything else says "latest stable", and Claude Code must look up and pin the current stable version before building (and keep Kotlin, KSP and the Compose compiler plugin compatible with each other).

---

## 1. On the laptop

| Tool | Why | Notes |
|---|---|---|
| Android Studio (latest stable) | Build, run, Logcat | Includes a bundled JDK; use it for Gradle |
| Android SDK: current platform, build tools, platform-tools | Compile and `adb` | Install from Android Studio SDK Manager |
| Git | Commits after each step | |
| Python 3.10+ with `pip` | Sync server | `pip install -r server/requirements.txt` |
| `pmtiles` CLI (go-pmtiles, from the protomaps/go-pmtiles GitHub releases) | Cut the offline map file | Only for the map phase |
| Claude Code, logged in | Building the project | Run it from the project folder in the VS Code terminal |
| Hugging Face account | Download model files (Gemma 3 1B is gated: log in and accept its license) | Download on the laptop, then push to phones |

---

## 2. On each phone

1. Enable Developer options and USB debugging; check `adb devices` lists it.
2. Update Google Play services (Nearby Connections runs inside it).
3. Turn on Bluetooth, Wi-Fi radio (internet not needed) and Location.
4. **Battery:** allow background activity for Lifeline Mesh and turn off battery optimization for it. On vivo/iQOO (Funtouch OS / OriginOS) this is under the app's battery settings ("Allow background activity" / "Background power consumption"). Exact menu names differ by version, so check on the phone.
5. **Offline speech:** in the phone's voice input settings (often Google app → Settings → Voice → Offline speech recognition), download English (India) and Hindi. Check whether Kannada is offered for offline use; if not, Kannada works by typing only.
6. **Text-to-speech:** install voice data for Hindi and English (Settings → Accessibility or Language → Text-to-speech output).
7. Install the app with `./gradlew :app:installDebug` (or Run in Android Studio).

---

## 3. Gradle version catalog (`gradle/libs.versions.toml`)

```toml
[versions]
agp = "<latest stable>"
kotlin = "<latest stable>"
ksp = "<matching kotlin>"
composeBom = "<latest stable>"
activityCompose = "<latest stable>"
lifecycle = "<latest stable>"
navigationCompose = "<latest stable>"
room = "<latest stable>"
datastore = "<latest stable>"
coroutines = "<latest stable>"
serialization = "<latest stable>"
coreKtx = "<latest stable>"
playServicesNearby = "19.5.1"        # checked: Google Play services release notes, 28 Sep 2026
playServicesLocation = "<latest stable>"
maplibre = "13.6.1"                   # seen on Maven Central; use latest 13.x. PMTiles needs 11.7.0 or newer
litertlm = "<latest release on Google Maven>"   # the LiteRT-LM guide suggests "latest.release"; pin the exact version
junit = "4.13.2"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
compose-animation = { module = "androidx.compose.animation:animation" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-service = { module = "androidx.lifecycle:lifecycle-service", version.ref = "lifecycle" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-play-services = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-play-services", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
play-services-nearby = { module = "com.google.android.gms:play-services-nearby", version.ref = "playServicesNearby" }
play-services-location = { module = "com.google.android.gms:play-services-location", version.ref = "playServicesLocation" }
maplibre-android = { module = "org.maplibre.gl:android-sdk", version.ref = "maplibre" }
litertlm-android = { module = "com.google.ai.edge.litertlm:litertlm-android", version.ref = "litertlm" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

Notes for Claude Code:
- `material-icons-extended` is large. If the current Compose BOM no longer manages it, pin its last published version or import the few Material Symbols needed as vector drawables instead.
- **LiteRT-LM:** one runtime for every model in the ladder (`docs/MODEL_SELECTOR.md`). Check the current LiteRT-LM Android guide for the exact API. GPU needs the `uses-native-library` entries in section 4. NPU needs chip-specific model files and bundled NPU libraries; treat it as an optional experiment.
- Don't compress `.litertlm` files if any are ever bundled in assets (add `androidResources { noCompress += "litertlm" }`); normally models are pushed or downloaded, not bundled, because of their size.
- Restrict ABIs to `arm64-v8a` to keep the APK small.
- Enable `buildConfig` and add `SERVER_URL_DEFAULT` there (editable in Settings).

---

## 4. Manifest permissions

Claude Code must check current Android docs for Nearby Connections and foreground services, then adapt. Expected set:

```xml
<!-- Nearby Connections -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- Relaying in the background -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- Voice, haptics -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.VIBRATE" />

<!-- Sync only (core features work without it) -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<uses-feature android:name="android.hardware.bluetooth" android:required="false" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
<uses-feature android:name="android.hardware.wifi" android:required="false" />

<service
    android:name=".mesh.MeshService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />

<!-- Inside <application>: needed for the LiteRT-LM GPU backend -->
<uses-native-library android:name="libvndksupport.so" android:required="false" />
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

Do not add `neverForLocation` to `BLUETOOTH_SCAN` unless the current Nearby docs say it is safe; discovery may rely on location. Request runtime permissions in onboarding, grouped and explained.

Package-visibility query for speech recognition (Android 11+):

```xml
<queries>
    <intent><action android:name="android.speech.RecognitionService" /></intent>
    <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
</queries>
```

---

## 5. Local LLM files

The app picks the model per phone (`docs/MODEL_SELECTOR.md`). Download on the laptop (start the big ones now):

| Model | Hugging Face repo | Size | Access |
|---|---|---|---|
| Gemma 4 E4B instruct (`.litertlm`) | `litert-community/gemma-4-E4B-it-litert-lm` | ~3.66 GB | Apache 2.0 |
| Gemma 4 E2B instruct (`.litertlm`) | `litert-community/gemma-4-E2B-it-litert-lm` | ~2.58 GB | Apache 2.0 |
| Gemma 3 1B instruct int4 (`gemma3-1b-it-int4.litertlm`) | `litert-community/Gemma3-1B-IT` | ~529 MB | Gated: log in, accept the Gemma license |

Pick the CPU/GPU `.litertlm` file, not the web or NPU variants. Rename files to match `fileName` in `assets/models.json`, and record each file's sha256 there.

Install the app once (so its folder exists), then push to each phone that runs AI (at least A and C):

```
adb shell mkdir -p /sdcard/Android/data/com.lifeline.mesh/files/models
adb push gemma-4-E4B-it.litertlm /sdcard/Android/data/com.lifeline.mesh/files/models/
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.lifeline.mesh/files/models/
adb push gemma3-1b-it-int4.litertlm /sdcard/Android/data/com.lifeline.mesh/files/models/
```

Then open Settings → On-device AI → "Re-test". Pushing all three lets the selector show its step-down choices. On a low-storage phone, push only the smaller ones.

Language note: Gemma 4 is described as covering over 140 languages, but Kannada quality is unverified. The selector's language probe decides per phone whether Kannada uses the model or rules.

NPU (optional experiment): only with a `.litertlm` built for this exact chip plus the NPU libraries bundled in the app. Not needed for the demo.

---

## 6. Offline map files

1. Pick a bounding box around the venue (a few km).
2. Cut a vector map from a Protomaps basemap build (check protomaps docs for the current build URL):
   ```
   pmtiles extract <protomaps build URL>.pmtiles venue.pmtiles --bbox=<minLon>,<minLat>,<maxLon>,<maxLat> --maxzoom=15
   ```
3. Put `venue.pmtiles` in `app/src/main/assets/map/`. **Correction (checked 9 Oct 2026):** the MapLibre Android docs say
   `pmtiles://asset://` is *not* supported. The app copies the archive from assets into app storage on first use and opens
   it as `pmtiles://file://<absolute path>` (see `ui/map/MapSupport.kt`).

   Current file (9 Oct 2026): central Bengaluru, bbox 77.55,12.93,77.65,13.01, max zoom 15, Protomaps build 20261008
   (basemap v4.15.2), 8.2 MB. To re-cut for the exact venue, change the bbox and run (then update `MapAssets` bounds):
   ```
   pmtiles extract https://build.protomaps.com/20261008.pmtiles app/src/main/assets/map/venue.pmtiles --bbox=<minLon>,<minLat>,<maxLon>,<maxLat> --maxzoom=15
   ```
4. Style: write a small `style.json` with only fill and line layers (water, land use, roads, buildings) in Night and Daylight colors. Without text layers, no glyph files are needed. Our own markers are drawn as circle layers or Compose overlays. If labels are wanted later, bundle the needed glyph and sprite files from the Protomaps basemap assets and point to them with `asset://` URLs.
5. Show "© OpenStreetMap contributors" (and Protomaps credit if their docs ask for it).
6. Do not bulk-download tiles from openstreetmap.org servers; their tile usage policy forbids it.

If this is not ready by the map phase, ship the Canvas fallback map.

---

## 7. Fonts (offline, bundled)

From Google Fonts (SIL Open Font License), copy into `app/src/main/res/font/`:
- **Archivo** (variable font with a width axis; use `FontVariation` settings in Compose for the condensed width, or a static condensed cut).
- **Atkinson Hyperlegible** regular and bold.

Hindi and Kannada fall back to the phone's system Noto fonts.

---

## 8. Vetted first-aid text

`app/src/main/assets/firstaid.json`, about five entries I will supply from a trusted source (for example national disaster-management or Red Cross guidance), each with: `id`, `title`, `keywords`, `text`, `source`. Claude Code must not write medical content itself.

---

## 9. Server

`server/requirements.txt`:
```
fastapi
uvicorn[standard]
pydantic
```

Run on the laptop:
```
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Endpoints: `POST /api/packets` (list of decoded packets, idempotent by message ID), `GET /api/packets`, `GET /` (simple HTML table for judges). Set the phone's server URL to the laptop's address on the same Wi-Fi, e.g. `http://192.168.x.x:8000`. Allow cleartext HTTP for that address in a network security config for the demo.

---

## 10. Pre-demo checklist

- [ ] App installed on all three phones, same build
- [ ] Permissions granted, battery restrictions off, Bluetooth and location on
- [ ] Model files pushed to A and C, "AI ready" shows
- [ ] Offline speech packs installed; demo sentence tested 5 times
- [ ] Map file present or Canvas fallback confirmed
- [ ] Server running and reachable (if showing sync)
- [ ] Backup screen recording of a full successful run
- [ ] Phones charged, chargers at the table
