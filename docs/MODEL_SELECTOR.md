# On-device AI model selector

The app checks what the phone can handle, picks the best local LLM that actually runs well on it, and falls back step by step. It never blocks the core app: if no model fits, every AI feature uses its rule-based fallback.

## 1. The model ladder

All models run through **LiteRT-LM** (one runtime, one code path). Sizes and benchmarks are from the model pages on Hugging Face (checked 9 Oct 2026); benchmarks were measured on Samsung phones, not on the iQOO 15, so the app must measure its own.

| Tier | Model | File | Size | License | Published Android numbers |
|---|---|---|---|---|---|
| 1 | Gemma 4 E4B instruct | `.litertlm` from `litert-community/gemma-4-E4B-it-litert-lm` | ~3.66 GB | Apache 2.0 | S26 Ultra: GPU TTFT 0.8 s, 22 tok/s, 710 MB CPU RAM; CPU TTFT 5.3 s, 18 tok/s, 3,283 MB |
| 2 | Gemma 4 E2B instruct | `.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm` | ~2.58 GB | Apache 2.0 | S26 Ultra: GPU TTFT 0.3 s, 52 tok/s, 676 MB; CPU TTFT 1.8 s, 47 tok/s, 1,733 MB |
| 3 | Gemma 3 1B instruct (int4) | `gemma3-1b-it-int4.litertlm` from `litert-community/Gemma3-1B-IT` | ~529 MB | Gemma license, gated (log in and accept on Hugging Face) | S24 Ultra: CPU 55 tok/s, ~1,009 MB; GPU 49 tok/s, ~1,205 MB |
| 4 | No model | none | 0 | none | Rules only |

Language note: Gemma 4 is described as supporting over 140 languages, but nothing published evaluates Kannada specifically. The probe in section 4 tests each language and only uses the LLM for languages that pass.

Experimental NPU tier (optional, not part of the main ladder): NPU-specific `.litertlm` files exist for some Qualcomm chips (for example a Gemma 3 1B build for SM8750, and Qualcomm AI Hub lists Gemma 4 E4B for the Galaxy variant of Snapdragon 8 Elite Gen 5). Only try NPU if a file built for this exact chip is installed and the user enables "Experimental NPU" in Settings. LiteRT-LM uses `Backend.NPU(nativeLibraryDir = …)` with the NPU libraries bundled in the app.

## 2. Model catalog (`assets/models.json`)

The ladder lives in a JSON file so models can be added without code changes:

```json
[
  {
    "id": "gemma4-e4b",
    "name": "Gemma 4 E4B",
    "fileName": "gemma-4-E4B-it.litertlm",
    "sizeBytes": 3660000000,
    "sha256": "<fill in after download>",
    "downloadUrl": "<direct file URL from the litert-community page>",
    "gated": false,
    "license": "Apache-2.0",
    "tier": 1,
    "minTotalRamMb": 8000,
    "minAvailRamMbCpu": 3800,
    "minAvailRamMbGpu": 1500,
    "storageHeadroomMb": 1000,
    "backends": ["GPU", "CPU"]
  },
  {
    "id": "gemma4-e2b",
    "name": "Gemma 4 E2B",
    "fileName": "gemma-4-E2B-it.litertlm",
    "sizeBytes": 2583000000,
    "sha256": "<fill in>",
    "downloadUrl": "<direct file URL>",
    "gated": false,
    "license": "Apache-2.0",
    "tier": 2,
    "minTotalRamMb": 6000,
    "minAvailRamMbCpu": 2200,
    "minAvailRamMbGpu": 1200,
    "storageHeadroomMb": 800,
    "backends": ["GPU", "CPU"]
  },
  {
    "id": "gemma3-1b",
    "name": "Gemma 3 1B",
    "fileName": "gemma3-1b-it-int4.litertlm",
    "sizeBytes": 529000000,
    "sha256": "<fill in>",
    "downloadUrl": null,
    "gated": true,
    "license": "Gemma",
    "tier": 3,
    "minTotalRamMb": 4000,
    "minAvailRamMbCpu": 1400,
    "minAvailRamMbGpu": 1400,
    "storageHeadroomMb": 400,
    "backends": ["CPU", "GPU"]
  }
]
```

The RAM thresholds are **starting guesses** derived from the published memory numbers plus headroom for the rest of the app. The probe (section 4) is what decides; tune the thresholds after measuring on real phones. Gated models have no download URL in the app: they are imported (section 6).

## 3. Device profile (`ai/DeviceProfiler.kt`)

Collected on every launch (cheap, no model loaded):

| Field | Android API |
|---|---|
| Total and available RAM, low-memory flag | `ActivityManager.getMemoryInfo()` (`totalMem`, `availMem`, `lowMemory`), `isLowRamDevice` |
| Free storage in the models folder | `StatFs(context.getExternalFilesDir(null))` |
| CPU ABIs | `Build.SUPPORTED_ABIS` (must include `arm64-v8a`) |
| Chip | `Build.SOC_MANUFACTURER`, `Build.SOC_MODEL` (Android 12+), else `Build.HARDWARE` |
| Phone | `Build.MANUFACTURER`, `Build.MODEL` |
| Android version | `Build.VERSION.SDK_INT` |
| Vulkan / OpenGL ES hints | `PackageManager.hasSystemFeature(FEATURE_VULKAN_HARDWARE_LEVEL)`, OpenGL ES version from `ConfigurationInfo` (hints only; the real GPU test is the probe) |
| Thermal state | `PowerManager.currentThermalStatus` (Android 10+) |
| Battery level, charging, power save | `BatteryManager`, `PowerManager.isPowerSaveMode` |

## 4. Selection algorithm (`ai/ModelSelector.kt`)

```
1. profile = DeviceProfiler.read()
2. installed = models present and complete in the models folders (size matches; sha256 matches if known)
3. candidates = for each catalog model, for each of its backends (in its listed order):
       keep if arm64 AND total RAM ≥ minTotalRamMb
            AND available RAM ≥ minAvailRam for that backend
            AND (installed OR free storage ≥ sizeBytes + storageHeadroomMb)
   sorted by tier (best first), GPU before CPU within a tier
4. For each candidate that is INSTALLED, in order:
       a. Load it (off the main thread, timeout 60 s). On failure → record reason, next.
       b. Speed probe: one fixed short prompt, 32 output tokens. Measure TTFT and decode tok/s.
          Pass if TTFT ≤ 2.5 s AND decode ≥ 10 tok/s (so a ~40-token JSON answer finishes in about 6 s).
          On fail → close engine, record numbers, next.
       c. Language probe: run AlertExtractor on 2 fixed test sentences per language (en, hi, kn)
          that are NOT the demo sentences. A language passes if both give valid, correct JSON.
       d. Chosen. Keep this engine loaded. Save the result.
5. If nothing passed → "Rules only" (tier 4).
6. If a better tier fits the phone but is not installed → show "Better AI available for this phone"
   with its size and a download or import option.
```

Rules:
- Never load two models at once. Close the engine before trying the next.
- The probe runs on first launch, after a model is installed or removed, after an app update, or when the user taps "Re-test". Otherwise the saved result is used (keyed by phone model, chip, total RAM and app version).
- Per-language results control where the LLM is used: for a language that failed, extraction and questions use rules and fixed strings, and the Review sheet shows "Understood by: Rules".
- All numbers are recorded as `MetricEntity` rows (feature `probe`, backend, model, TTFT, tok/s, pass or fail), so the Benchmarks screen shows real measurements.

## 5. Adapting while running

- **Thermal:** if `currentThermalStatus` reaches SEVERE or worse, pause non-essential AI (situation report, ask the mesh, translate) and keep extraction. Show "AI paused to cool down". Resume at MODERATE or better.
- **Battery:** if power save is on and battery is below 20%, same as above, and stop background warm-up.
- **Memory:** on `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` or a native out-of-memory error, close the engine and fall back to rules; reload the chosen model when memory recovers and the app is in the foreground.
- **Failure during use:** if a call fails 3 times in a row, mark the backend unhealthy for the session and try the same model on CPU, then the next tier.

The mesh, packets and quality gate never wait for any of this.

## 6. Getting the model onto the phone

Order of preference:

1. **Already installed:** the app scans `getExternalFilesDir("models")` (that is, `/sdcard/Android/data/com.lifeline.mesh/files/models/`) and the app's internal files dir.
2. **adb push (hackathon path):**
   ```
   adb shell mkdir -p /sdcard/Android/data/com.lifeline.mesh/files/models
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.lifeline.mesh/files/models/
   ```
   Then Settings → On-device AI → "Re-test". (Install the app once first so the folder exists.)
3. **Import from a file:** Settings → "Import model file" opens the system file picker (`ACTION_OPEN_DOCUMENT`) and copies the file into the models folder with a progress bar. Works for gated models downloaded on a laptop.
4. **Download (ungated models only, Wi-Fi only):** "Download recommended model" uses `DownloadManager` or a resumable HTTP download into the models folder, shows progress and size, refuses on mobile data unless the user confirms, verifies size and sha256, then runs the probe. Pitch this as "prepare before a disaster, while you still have Wi-Fi".

Future work, not for the hackathon: sharing a model file phone to phone over Nearby Connections file payloads.

## 7. User interface

**Settings → On-device AI card**
- Phone: "iQOO 15 · <chip> · 16 GB RAM · 120 GB free" (from the profile).
- Chosen: "Gemma 4 E2B on GPU", with measured TTFT and tok/s, and per-language ticks: English ✓, Hindi ✓, Kannada ✗ (rules).
- Status line: "Ready", "Loading", "Testing models…", "Paused to cool down", "Rules only".
- Buttons: "Re-test", "Import model file", "Download recommended (2.6 GB, Wi-Fi)", "Remove model".
- Expandable "Why this model?": the candidate list with pass/fail reasons ("Gemma 4 E4B · GPU · TTFT 3.1 s, too slow" or "not installed").

**Onboarding:** an optional last step, "Prepare offline AI", showing the recommended model and its size, with "Download now on Wi-Fi" or "Skip".

**Top status strip:** the AI dot shows the chosen model on long-press.

**Benchmarks screen:** probe history table.

## 8. Reference code (pure Kotlin decision logic, unit-testable)

```kotlin
package com.lifeline.mesh.ai

enum class LlmBackend { GPU, CPU, NPU }

data class DeviceProfile(
    val totalRamMb: Long,
    val availRamMb: Long,
    val freeStorageMb: Long,
    val isArm64: Boolean,
    val sdkInt: Int,
    val socModel: String?,
    val thermalSevere: Boolean,
    val powerSaveLowBattery: Boolean,
)

data class ModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val tier: Int,
    val minTotalRamMb: Long,
    val minAvailRamMbCpu: Long,
    val minAvailRamMbGpu: Long,
    val storageHeadroomMb: Long,
    val backends: List<LlmBackend>,
    val gated: Boolean,
    val downloadUrl: String?,
)

data class Candidate(val model: ModelSpec, val backend: LlmBackend, val installed: Boolean)

data class Rejection(val model: ModelSpec, val backend: LlmBackend?, val reason: String)

data class Shortlist(val candidates: List<Candidate>, val rejected: List<Rejection>)

object ModelShortlist {
    fun build(profile: DeviceProfile, catalog: List<ModelSpec>, installedIds: Set<String>): Shortlist {
        val keep = mutableListOf<Candidate>()
        val rejected = mutableListOf<Rejection>()
        for (m in catalog.sortedBy { it.tier }) {
            if (!profile.isArm64) { rejected += Rejection(m, null, "Needs a 64-bit ARM phone"); continue }
            if (profile.totalRamMb < m.minTotalRamMb) {
                rejected += Rejection(m, null, "Needs ${m.minTotalRamMb / 1000} GB RAM"); continue
            }
            val installed = m.id in installedIds
            val sizeMb = m.sizeBytes / 1_000_000
            if (!installed && profile.freeStorageMb < sizeMb + m.storageHeadroomMb) {
                rejected += Rejection(m, null, "Needs ${sizeMb + m.storageHeadroomMb} MB free storage"); continue
            }
            for (b in m.backends.sortedBy { if (it == LlmBackend.GPU) 0 else 1 }) {
                val need = if (b == LlmBackend.GPU) m.minAvailRamMbGpu else m.minAvailRamMbCpu
                if (profile.availRamMb < need) rejected += Rejection(m, b, "Needs $need MB free RAM")
                else keep += Candidate(m, b, installed)
            }
        }
        return Shortlist(keep, rejected)
    }
}

data class ProbeResult(
    val ttftMs: Long,
    val decodeTokPerSec: Double,
    val loaded: Boolean,
    val error: String? = null,
) {
    fun passesSpeed(maxTtftMs: Long = 2_500, minTokPerSec: Double = 10.0): Boolean =
        loaded && error == null && ttftMs <= maxTtftMs && decodeTokPerSec >= minTokPerSec
}
```

Unit tests must cover: a 16 GB phone with all models installed picks tier 1 GPU; a 6 GB phone skips tier 1; no arm64 gives rules only; low free storage rejects uninstalled models but keeps installed ones; GPU rejected for low available RAM while CPU still qualifies; the probe failing on tier 1 falls through to tier 2.

`ModelSelector` (Android side) runs the shortlist, then loads and probes installed candidates with LiteRT-LM:

```kotlin
// Shape only; check the current LiteRT-LM Kotlin guide for exact names.
val engine = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.GPU()))
engine.initialize()            // off the main thread; can take a while
// create a conversation, stream a fixed 32-token prompt, time first token and decode rate
engine.close()                 // before trying the next candidate
```

GPU needs these inside `<application>` in the manifest (from the LiteRT-LM Android guide):

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
```
