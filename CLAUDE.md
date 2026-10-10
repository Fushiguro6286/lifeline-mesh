# Lifeline Mesh: project brief for Claude Code

This file is the source of truth for the project. Read it fully before doing any work. Also read:

- `docs/DESIGN.md` for the visual design, every screen, and the live mesh view.
- `docs/SETUP.md` for every tool, library, permission, model and data file.
- `docs/MODEL_SELECTOR.md` for how the app checks the phone and picks the local LLM that runs best on it.

Re-read the relevant section before each phase.

---

## 1. What we are building

Lifeline Mesh is an Android app that keeps emergency alerts moving when cell towers and internet are down (floods, cyclones, earthquakes).

1. A person speaks or types an SOS in Hindi, Kannada or English, or holds a panic button.
2. On-device AI turns it into a compact structured alert: emergency type, people, severity, GPS.
3. A quality gate asks for missing details before sending.
4. Nearby phones relay the alert phone to phone (Google Nearby Connections over Bluetooth and Wi-Fi, no internet), with a hop limit (TTL), duplicate suppression and store-and-forward.
5. A rescuer's phone merges duplicate reports, ranks them, shows them on an offline map, and sends "help is coming" back through the mesh.
6. The local LLM writes situation reports, answers rescuer questions from stored alerts, and translates.
7. Any phone that finds internet uploads queued data to a small server.

**Context:** solo build for the iQOO Hackathon Grand Finale (48 hours, Bengaluru). Demo device: iQOO 15 (Snapdragon 8 Elite Gen 5, Android 16), plus two more Android phones. Judges care about on-device AI, local LLMs, the Snapdragon NPU, and a demo that works.

**Pitch line:** "Panic in, clear alert out, even with no network."

---

## 2. Non-negotiable rules

1. **The core never depends on the LLM.** Packet building, routing, triage ranking and quality-gate checks are deterministic Kotlin. The LLM adds extraction help, questions, translation and summaries. Every LLM call has a timeout and a non-LLM fallback, and the user never waits more than the timeout.
2. **No internet for core features.** Only `ServerUploader` uses the network, and only when available.
3. **Never fake numbers.** Hop counts, round-trip times, packet sizes, NPU/CPU timings and battery drain must be measured on device. Anything not measured shows "Not measured". Simulated values are always labeled "Simulated".
4. **Ground every LLM answer** in alerts stored on the phone, citing alert numbers. If nothing matches: "No matching alerts."
5. **Build in phase order** (section 12). Don't start a phase until the previous one's "done when" checks pass. If blocked, stop and tell me.
6. **Every feature must actually work.** A feature is done only when it works end to end on the phones (or, for pure logic, when its tests pass) and its failure cases are handled (no permission, Bluetooth off, location off, model missing, no neighbors, app in background).
7. **Keep it simple.** Plain code, few files, no DI framework, no feature outside this file without asking me.
8. **Verify current APIs and versions.** Check official docs before using Nearby Connections, LiteRT-LM, MapLibre, Android permissions and foreground services. Tell me if anything here is out of date.
9. **Commit after each working step** with a clear message.
10. **Medical safety.** The first-aid helper only quotes the guide text in `assets/firstaid.json`, never generates medical advice, and always says to contact emergency services if possible. (10 Oct 2026, my decision: Claude Code drafted the 17 guides from published Red Cross / St John / NHS / WHO guidance. Each has `"reviewed": false` and the app shows "Not yet reviewed by a medical professional" until a qualified person checks it and sets it to true. Claude Code must not mark a guide reviewed.)
11. **Design matters.** Follow `docs/DESIGN.md`. The app must look intentional and calm under stress, not like a default template.

---

## 3. Tech stack (versions in `docs/SETUP.md`)

| Area | Choice |
|---|---|
| Language, UI | Kotlin, Jetpack Compose, Material 3 (heavily themed), Navigation Compose |
| State | ViewModel + StateFlow, coroutines |
| Storage | Room (with KSP), DataStore Preferences for settings |
| JSON | kotlinx.serialization (LLM output parsing, server sync) |
| Mesh | Google Nearby Connections, `Strategy.P2P_CLUSTER`, inside a foreground service |
| Location | Fused Location Provider (works offline using GPS) |
| Speech in | Android `SpeechRecognizer`, on-device recognizer where available |
| Speech out | Android `TextToSpeech` |
| Local LLM | LiteRT-LM (`litertlm-android`) with a model ladder chosen per phone by `ModelSelector`: Gemma 4 E4B → Gemma 4 E2B → Gemma 3 1B → rules only (see `docs/MODEL_SELECTOR.md`) |
| GPU / NPU | LiteRT-LM GPU backend first, CPU fallback. NPU is an optional experiment only if a model file built for this exact chip is installed |
| Map | MapLibre Native Android reading an offline `.pmtiles` file; Compose Canvas fallback map |
| Server | Python FastAPI + SQLite |
| Tests | JUnit, kotlinx-coroutines-test |

Package `com.lifeline.mesh`. `minSdk 26`, current stable `compileSdk`/`targetSdk`. ABI `arm64-v8a`. Guard newer APIs with runtime checks.

---

## 4. Architecture

```
            ┌────────────────────────── on the phone, no internet needed ──────────────────────────┐
 Voice/text │ SpeechInput → RuleExtractor ─┐                                                        │
 Panic hold │                AlertExtractor(LLM, optional) → AlertDraft → QualityGate ─► PacketCodec │
            │                                     ClarifyingQuestions(LLM) ◄─ blocked              │
            │                                                                                 │     │
            │   MeshService (foreground) ── NearbyTransport ◄──► Router ◄── SeenCache, Outbox,     │
            │                                   ▲  ▼              RateLimiter, PeerSync, Hello   │
            │                              other phones           │                              │
            │                                                     ▼                              │
            │                     Room: packets, neighbors, metrics, settings                    │
            │                         │                 │                    │                   │
            │              Rescuer: Merger→Ranker   LiveMesh view      SituationReport/AskMesh/  │
            │              Map, Ack sender          Scoreboard         Translator (LLM)          │
            └─────────────────────────────────────────┬──────────────────────────────────────────┘
                                                      ▼ only when internet appears
                                               ServerUploader → FastAPI server
```

- `MeshService` is a foreground service (type `connectedDevice`) that owns `NearbyTransport` and `Router`, so relaying continues with the screen off or the app in the background.
- UI talks to `MeshService` through a repository exposing `StateFlow`s (neighbors, packets, events, metrics).
- `Router` and everything in `packet/`, `mesh/` (except `NearbyTransport` and `MeshService`), `triage/` and `ai/QualityGate`, `ai/RuleExtractor` are pure Kotlin with no Android imports, so they are unit-testable.

---

## 5. Project structure

```
lifeline-mesh/
├── CLAUDE.md
├── docs/  DESIGN.md, SETUP.md, DEMO.md, MEASUREMENTS.md
├── gradle/libs.versions.toml
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/  models.json, map/venue.pmtiles, map/style.json, firstaid.json
│   ├── res/font/  (bundled fonts, see DESIGN.md)
│   └── kotlin/com/lifeline/mesh/
│       ├── MainActivity.kt, LifelineApp.kt, AppRepository.kt
│       ├── ui/
│       │   ├── theme/       Color.kt, Type.kt, Theme.kt, Shapes.kt, Motion.kt
│       │   ├── components/  SignalRings, HoldToSendButton, NeedChip, SeverityBadge, StatTile,
│       │   │                PacketBytesStrip, StatusTimeline, BigIconButton, EmptyState
│       │   ├── onboarding/  OnboardingScreen, PermissionsScreen
│       │   ├── send/        SendScreen, ReviewSheet, DeliveryStatus, CheckInCard
│       │   ├── mesh/        LiveMeshScreen, MeshCanvas, EventTimeline, Scoreboard
│       │   ├── rescuer/     RescuerScreen, AlertCard, AlertDetailSheet, SituationReportCard, AskMeshScreen
│       │   ├── map/         OfflineMapScreen, CanvasFallbackMap
│       │   └── settings/    SettingsScreen, BenchmarksScreen
│       ├── packet/   Packet.kt, PacketCodec.kt
│       ├── mesh/     MeshService.kt, NearbyTransport.kt, Router.kt, SeenCache.kt, Outbox.kt,
│       │             PeerSync.kt, RateLimiter.kt, HelloBeacon.kt, MeshSimulatorTransport.kt
│       ├── triage/   Merger.kt, Ranker.kt
│       ├── ai/       SpeechInput.kt, RuleExtractor.kt, QualityGate.kt, LlmEngine.kt, Prompts.kt,
│       │             DeviceProfiler.kt, ModelCatalog.kt, ModelShortlist.kt, ModelSelector.kt,
│       │             ModelInstaller.kt, AiGovernor.kt (thermal/battery/memory),
│       │             AlertExtractor.kt, ClarifyingQuestions.kt, SituationReport.kt, AskMesh.kt,
│       │             Translator.kt, FirstAid.kt, ReadAloud.kt
│       ├── location/ LocationProvider.kt
│       ├── metrics/  Metrics.kt, BatteryMonitor.kt, Benchmarks.kt
│       ├── sync/     ServerUploader.kt
│       └── data/     AppDatabase.kt, entities, DAOs, SettingsStore.kt
├── app/src/test/kotlin/…  PacketCodecTest, RouterTest, MeshSimulator, MergerTest, RankerTest,
│                          QualityGateTest, RuleExtractorTest, AskMeshFallbackTest
└── server/  main.py, requirements.txt
```

---

## 6. Packet format (binary, big-endian)

Every packet starts with a 19-byte header:

| Offset | Bytes | Field | Notes |
|---|---|---|---|
| 0 | 1 | Version and type | High nibble version = 1, low nibble type |
| 1 | 8 | Message ID | Random 64-bit |
| 9 | 4 | Origin | Random per-install ID, stored in DataStore, never a phone number |
| 13 | 1 | TTL | Starts at 5 (Hello starts at 2) |
| 14 | 1 | Hop count | Starts at 0 |
| 15 | 4 | Time | Unix seconds, sender clock |

| Type | Name | Body |
|---|---|---|
| 1 | Alert | lat int32 µdeg (4), lon int32 µdeg (4), accuracy 10 m steps, 255 unknown (1), needs bitmask (1), severity 0–3 (1), people 0–255 (1), note UTF-8 ≤ 60 bytes to end |
| 2 | Check-in | lat (4), lon (4), accuracy (1), name UTF-8 ≤ 24 bytes |
| 3 | Ack | referenced message ID (8), ETA minutes (1), note ≤ 40 bytes |
| 4 | Community report | lat (4), lon (4), accuracy (1), report type (1: road blocked, 2: water available, 3: shelter open, 4: shelter full), note ≤ 60 bytes |
| 5 | Ping | referenced message ID (8), echo flag (1) |
| 6 | Hello | battery % (1), role (1: needs help, 2: volunteer, 3: other rescue team, 4: firefighter, 5: ambulance, 6: police, 7: NDRF; 3 and up are teams), neighbor count (1), neighbor origins (4 each, max 16), display name ≤ 16 bytes |
| 7 | Position (Phase 10, beacon like Hello: not rate limited, not stored, no events) | lat (4), lon (4), accuracy (1), role (1, as Hello), display name ≤ 16 bytes |
| 8 | Official warning (10 Oct 2026; brought in by an online phone from the sync server, stored, relayed, never uploaded) | level = CAP severity 0–3 (1), flags bit0 drill, bit1 forecast check (server's own Open-Meteo check, not official) (1), expires unix s (4), event length (1), event UTF-8 ≤ 24 bytes, headline UTF-8 ≤ 160 bytes to end. Origin 0x5AC4E700, message ID = first 8 bytes of SHA-256 of the CAP identifier |
| 15 | Summary (control, never relayed) | count (1), up to 100 message IDs (8 each) |

Needs bitmask: 1 trapped, 2 medical, 4 water or food, 8 fire, 16 road blocked, 32 panic SOS, 64 ambulance requested (Phase 10).

`PacketCodec` rejects malformed packets (short, unknown version/type, oversized note, invalid UTF-8 trimmed safely) without crashing. Notes are trimmed on whole UTF-8 characters, never mid-character. Unit tests cover every type, maximum lengths, Hindi and Kannada notes, and malformed input.

---

## 7. Mesh behavior

### 7.1 Transport
- One service ID: `com.lifeline.mesh.v1`. Advertise and discover at the same time with `P2P_CLUSTER`.
- Endpoint name = short display name + origin (e.g. `Asha-3fa1`).
- Auto-accept connections whose endpoint info shows our service. Reconnect automatically when a link drops (backoff 2, 4, 8 s, max 30 s).
- Send packets as `Payload.fromBytes`. Never send a payload over 1 KB.
- Expose per-neighbor state: discovering, connecting, connected, lost, plus last-heard time.
- Handle and show clearly: Bluetooth off, Wi-Fi off (Nearby still works over Bluetooth but may be slower), location services off (needed for discovery on many devices), missing permissions, Google Play services missing or outdated.

### 7.2 Routing (controlled flooding) in `Router`
On receiving a relayable packet:
1. Rate limit: more than 10 packets per minute from one origin → drop and log "rate limited".
2. Seen message ID (`SeenCache`, LRU 2,000 entries, 6-hour expiry) → drop and log "duplicate".
3. Otherwise store it, emit an event for the UI, apply type-specific handling (below).
4. TTL 0 → stop. Else TTL−1, hops+1, forward to every connected neighbor except the one it came from.
5. Forward after a random 50–300 ms delay. Priority: alerts with severity 3 and acks first, then other alerts, pings, check-ins, reports, hellos.

Type handling:
- **Ack:** if the referenced ID is one of mine, show it as a reply ("Help is coming, ETA 20 min") with sound, vibration and read-aloud.
- **Ping:** if echo flag is 0 and I am the rescuer role (or the referenced alert's first receiver, or its sender), send back a Ping with echo flag 1 referencing the same ID. If echo flag is 1 and the original ping is mine, record round-trip time.
- **Automatic pings (10 Oct 2026):** the app pings my alert as soon as a neighbor takes it, and a team phone pings each new alert back towards its sender, so the round trip is measured without the debug panel. At most one automatic ping of my own alerts per 30 s; a team phone's pings back (receipts, one per new alert) go at most one per 15 s, the rest queued for up to 10 minutes, so pings never use up the 10-a-minute budget alerts and acks need.
- **Hello:** update the topology table (origin → neighbors, battery, role, name, last heard). Expire after 60 s silence.

### 7.3 Store and forward
- `Outbox` keeps relayable packets for 6 hours.
- On every new connection both sides send a Summary of recent IDs; each sends only what the other lacks.
- The sender's Delivery Status shows honest states: "Waiting for a nearby phone", "Passed to N nearby phones", "Reached a rescue team's phone" (10 Oct 2026, my request: a team phone pinged the alert back and its own beacon says it is a team), "Help is coming".

### 7.4 Hello beacons (for the live mesh view)
Every 10 s each phone broadcasts a Hello (TTL 2) with its direct neighbors. Every phone builds a topology graph from its own neighbors plus received Hellos, so the Live Mesh screen shows the whole local network, not just direct links.

### 7.5 Simulation mode (rehearsal and stage fallback)
`MeshSimulatorTransport` implements the same transport interface with 4 virtual phones in a line, run by the real `Router`. It can be switched on in Settings. While on, a red "Simulated" banner shows on every screen and all metrics are labeled "Simulated". Never mix simulated and real metrics.

### 7.6 Sync
When `ConnectivityManager` reports validated internet, `ServerUploader` posts unsent alerts, check-ins, reports and acks to `POST {server}/api/packets` as JSON, marks them uploaded, and broadcasts nothing extra. Server URL is set in Settings.

---

## 8. Data (Room)

- `PacketEntity`: id, type, origin, ttl, hops, sentAt, receivedAt, fromEndpoint, rawBytes, decoded fields (lat, lon, accuracy, needs, severity, people, note, refId, eta, reportType, name), isMine, uploaded, acked, ackEta.
- `TopologyEntity`: origin, name, role, battery, neighbors (comma list), lastHeard.
- `MetricEntity`: name, value, unit, backend, simulated (bool), timestamp, notes.
- `EventEntity` (capped at 500): time, kind, text, packetId.

Settings in DataStore: origin ID, display name, role, language, server URL, LLM backend, simulation mode, demo mode.

---

## 9. AI features

### 9.1 Extraction
- `RuleExtractor` (always runs, instant): keyword and number rules for English, Hinglish, Hindi and Kannada (port from the web prototype, including Devanagari digits and number words).
- `AlertExtractor` (LLM, optional): prompt in section 9.9, strict JSON, validate, 6 s timeout. Use the LLM result only if it parses and is valid. Show which one produced the result ("Rules" or "On-device AI").
- The user always reviews and can edit fields before sending.

### 9.2 Quality gate (`QualityGate`, deterministic)
Blocks sending when needs is empty or people is 0. GPS: if no fix within 8 s or accuracy worse than 50 m, use the last known location and mark it "approximate". Panic SOS bypasses the gate (needs = panic, severity 3, people 1).

### 9.3 Clarifying questions
When blocked, show one question for the first missing field in the sender's language (LLM-written, 4 s timeout; fixed fallback strings for en, hi, kn). Read it aloud if read-aloud is on. Label LLM text "Written on device".

### 9.4 Situation report
Compute all numbers in Kotlin (active alerts, people, urgent count, needs breakdown, top priority, check-ins, acknowledged vs open). The LLM only phrases them in three lines. Validate that every number in the output appears in the computed facts; if not, show the computed template text.

### 9.5 Ask the mesh
Chat screen for rescuers. Prompt includes only the ranked merged alerts as numbered facts. The answer must cite `#n`. If it cites none, cites a number that doesn't exist, or times out, show the rule-based answer (port from the prototype). Quick chips: "Who needs help first?", "How many people are trapped?", "Any medical cases?", "What is still unacknowledged?".

### 9.6 Translate
Translate alert notes for the rescuer and ack notes for the sender. Label "Machine translated". Fallback: original text.

### 9.7 First aid (last priority)
Load `assets/firstaid.json` (17 English guides drafted from published guidance, each flagged unreviewed until a medical professional checks it; see rule 10). The LLM only picks which entry matches the question (returns an entry ID). Show the entry text verbatim plus "Contact emergency services if you can." If no match, say so.

### 9.8 LLM engine and model selection
- Follow `docs/MODEL_SELECTOR.md` exactly: `DeviceProfiler` reads the phone, `ModelShortlist` (pure Kotlin, unit-tested) filters the ladder, `ModelSelector` loads and probes installed candidates (speed probe, then per-language probe), keeps the first that passes, and saves the result. No model passes → rules only.
- `LlmEngine` is an interface; the implementation uses LiteRT-LM (`Engine`, `EngineConfig`, `Backend.GPU()` / `Backend.CPU()`, streaming conversation). Check the current LiteRT-LM Kotlin guide for exact names.
- Models live in `getExternalFilesDir("models")` (adb push, import or Wi-Fi download via `ModelInstaller`).
- Single worker, one request at a time, cancellable, per-call timeout (6 s on GPU, 10 s on CPU), max new tokens per feature (extract 80, question 40, report 120, ask 150, translate 100). The model outputs labels only, never bits; Kotlin maps labels to the needs bitmask.
- Per-language gating: use the LLM only for languages that passed the probe; others use rules and fixed strings.
- `AiGovernor` pauses non-essential AI on severe thermal state or low battery in power save, and unloads the model on critical memory pressure.
- Records per call: feature, model, backend, prompt tokens, time to first token, tokens/s, total time.
- Warm-up: select and load in the background after launch; the status strip shows "AI loading", "AI ready (Gemma 4 E2B · GPU)" or "Rules only".

### 9.9 Prompt templates (`Prompts.kt`)
Keep prompts short. Examples (adapt to the chat template):

Extraction:
```
System: You convert emergency messages into JSON. Reply with JSON only.
Keys: needs (array of "trapped","medical","water_food","fire","road_blocked"), people (integer), severity (0-3, 3 = life threatening).
If unknown, use [] or 0.
User: <message>
```

Clarifying question:
```
System: Ask one short, calm question in <language> to learn: <missing field>. No other text.
```

Situation report:
```
System: Write exactly 3 short lines for a rescue team leader using ONLY these facts. Do not add numbers.
Facts: <computed facts as key: value lines>
```

Ask the mesh:
```
System: Answer using ONLY the numbered alerts below. Cite alerts like #1. If none match, reply "No matching alerts."
Alerts:
#1 trapped, medical | 6 people | 0.5 km | urgent | note: "..."
...
User: <question>
```

Translate:
```
System: Translate to <language>. Output only the translation.
User: <text>
```

### 9.10 GPU and NPU
GPU via LiteRT-LM is the main accelerated path; show measured GPU vs CPU numbers on the Benchmarks screen. NPU is an optional experiment, only after everything else works: it needs a `.litertlm` built for this exact chip and the NPU libraries bundled (`Backend.NPU(nativeLibraryDir = …)`). On a Samsung S25 Ultra, vendor policy blocked installed apps from the Hexagon NPU, so test on the iQOO 15, don't assume. If NPU doesn't work, say so honestly on the Benchmarks screen.

---

## 10. Metrics and scoreboard

- **Hops:** from hop count on arrival.
- **Packet size:** encoded bytes.
- **Round trip:** Ping/echo (section 7.2). Never compare clocks across phones.
- **LLM:** per call from `LlmEngine`.
- **Battery:** `BatteryMonitor` records % at relay start; drain per hour shown only after ≥ 15 minutes.
- **Export:** Settings → "Export measurements" writes CSV to app files and shares it. Keep `docs/MEASUREMENTS.md` updated with what was measured and how.

---

## 11. Android specifics that make features work

- **Foreground service:** `MeshService` with `foregroundServiceType="connectedDevice"` and an ongoing notification ("Relaying alerts · 2 phones nearby"). Start it from the UI after permissions are granted.
- **Battery restrictions:** vivo/iQOO (Funtouch OS) and some other brands kill background apps aggressively. Add a Settings row that opens the system battery-optimization screen and explain "Allow background activity" in onboarding. Test relaying with the screen off.
- **Permissions:** request in onboarding with plain explanations (list in `docs/SETUP.md`). If denied, show what stops working and a button to open app settings.
- **Location services:** if off, show a banner with a button to turn them on.
- **Keep screen awake** on the Live Mesh screen during demos (demo mode only).
- **Haptics:** strong vibration when panic SOS sends and when a reply arrives.
- **Process death:** everything important is in Room, so reopening the app restores state.

---

## 12. Build phases

Each phase ends with its "done when" checks. Tell me how to test anything that needs real phones, then wait for my result.

**Phase 0: Project and design system (3 h)**
Gradle with the version catalog from `docs/SETUP.md`, manifest and permissions, Room skeleton, DataStore, theme (colors, bundled fonts, shapes, motion tokens from `DESIGN.md`), the shared components with previews, bottom navigation, onboarding and permissions flow.
Done when: the app installs on all three phones, onboarding completes, and the component preview screen matches `DESIGN.md`.

**Phase 1: Packet codec (2 h)**
Done when: all codec tests pass, including Hindi/Kannada notes and malformed input; print sample sizes.

**Phase 2: Mesh core (10 h, highest priority)**
Router, SeenCache, Outbox, PeerSync, RateLimiter, HelloBeacon, MeshSimulator tests, NearbyTransport, MeshService, a debug panel.
Done when: (a) simulator tests pass for A→C via B, duplicates, TTL, B off/on store-and-forward, acks, pings, rate limit; (b) on real phones with A and C out of direct range, an alert from A reaches C through B; (c) B off → alert waits; B on → delivers; (d) relaying works with B's screen off.

**Phase 3: Live Mesh screen and scoreboard (4 h)**
Topology graph from Hellos, animated packets, event timeline, scoreboard with real hops, bytes and round trip; simulation mode.
Done when: someone who knows nothing about the app can follow an alert crossing the mesh on this screen.

**Phase 4: Send flow (4 h)**
Send screen, voice button (typed fallback), RuleExtractor, review sheet, QualityGate, GPS, panic hold, check-in, delivery status, replies.
Done when: the four demo sentences in `PROMPTS.md` extract correctly, "please help us" is blocked with a question, panic sends without speech, and status updates honestly.

**Phase 5: Rescuer flow (4 h)**
Merger, Ranker, alert list and detail, check-ins, acknowledge with ETA, community reports list, load-sample button.
Done when: merge/rank tests pass and an ack from C appears as a reply on A with vibration.

**Phase 6: Map (3 h, cut to Canvas fallback if behind)**
MapLibre with offline PMTiles and a local style; markers by severity; help points; attribution. Canvas fallback map always available.
Done when: the map loads with Wi-Fi and data off.

**Phase 7: Speech, model selector and LLM (9 h)**
SpeechInput, DeviceProfiler, ModelCatalog, ModelShortlist (with tests), ModelSelector with speed and language probes, ModelInstaller (adb folder, import, Wi-Fi download), AiGovernor, LiteRT-LM LlmEngine, AlertExtractor, ClarifyingQuestions, the On-device AI settings card, Benchmarks screen.
Done when: the shortlist tests pass; on the iQOO 15 the selector picks a model and shows why; a spoken demo sentence produces a correct alert 4 times out of 5; with all models removed, the app says "Rules only" and everything still works; timings are recorded.

**Phase 8: LLM extras (5 h, cut if behind)**
SituationReport, AskMesh, Translator, ReadAloud.
Done when: each works on sample data and falls back cleanly with the model missing.

**Phase 9: Sync and polish (remaining time)**
ServerUploader and FastAPI server, community report sending, battery measurement, first aid, demo mode, crash fixes, final design pass against `DESIGN.md`.

**Phase 10: Profiles, teams and live locations (added 10 Oct 2026; start only after Phase 9 and the 3-phone tests for phases 2–5 pass)**
Decisions made on 10 Oct 2026:
- **Offline profile, no server login.** Onboarding creates a profile on the phone: name, optional phone number, role. Roles: Needs help, Volunteer, and teams: Firefighter, Ambulance, Police, NDRF, Other rescuer. Team roles are self-declared (not verified). Works with no internet.
- **Live location, teams see all.** Every phone shares its position in the mesh periodically. Rescue teams see everyone's live position; other users see only teams and their own alerts. An SOS always carries position. Positions expire after silence like Hello entries.
- **ETA is computed in Kotlin, never by the LLM.** Straight-line distance and a travel mode per team (e.g. on foot, road vehicle, boat), labelled "Estimate (straight line)". No road routing offline. **Changed 10 Oct 2026 (my request):** road routing offline on the roads of the bundled map (A*, deterministic Kotlin), avoiding reported road blockages; boats stay straight-line; still labelled as an estimate.
- **Team views.** Each team sees all alerts in its area, ranked by the existing Ranker, grouped by need, with a filter defaulting to its type (Firefighter: fire; Ambulance: medical; NDRF: trapped; Police: road blocked and all).
- **Urgent ambulance request.** A helper with a patient can send an urgent request addressed to Ambulance teams (severity 3, medical); Ambulance phones get a strong alert with sound, vibration and read-aloud.
- **Internet relay.** When any phone holding an alert has internet, the server forwards it to the nearest online team of the matching type; online team phones poll the server about every 15 s and inject what they receive into their own Rescue list (marked "via internet"). The mesh path stays unchanged.
- **First aid stays vetted-only** (rule 10 unchanged): the LLM only picks the matching vetted guide.
Packet changes (team role codes, position in Hello or a new position packet, a target-role field for directed requests) must be designed at the start of the phase, versioned, and covered by codec tests.
Done when: on three phones, a team phone sees the others' live positions and an ETA; a person-in-need phone does not see other people's positions; an urgent ambulance request from A reaches an Ambulance phone C through B; with internet on B only, the alert also reaches an online team through the server.

**Cut order if behind:** Phase 10 → Phase 9 extras → map (use Canvas fallback) → Phase 8 → server sync. Never cut phases 0–5.

---

## 13. Test plan (must pass before the demo)

| Area | Test |
|---|---|
| Codec | All unit tests pass |
| Routing | All simulator tests pass |
| Relay | A→B→C with A and C apart; B off/on; B with screen off |
| Duplicates | Send the same alert twice; second is dropped on every phone |
| Gate | "please help us" blocked with question in the chosen language |
| Panic | 3-second hold sends; letting go early cancels |
| Ack | Ack from C reaches A as a reply with vibration |
| Ping | Round-trip time shown on A; not shown in simulation without "Simulated" label |
| LLM off | Delete or rename the model file: every feature still works via fallbacks |
| Model selector | iQOO 15 picks a model and explains why; a phone with less RAM picks a smaller tier or rules; "Re-test" re-runs the probe |
| Permissions | Deny Bluetooth: clear message, no crash |
| Offline | Airplane mode with Bluetooth and Wi-Fi radios back on: everything works except sync |
| Restart | Force-stop and reopen: alerts and replies still there |

---

## 14. Demo script (3 minutes)

1. Three phones in a line, A and C out of direct range, mobile data and Wi-Fi internet off. Live Mesh screen on C shows the topology.
2. Speak an SOS into A; show extracted fields, "On-device AI" label and packet bytes.
3. Watch it hop to C through B; show hops and round-trip time.
4. Switch B off, send again, show "Waiting for a nearby phone"; switch B on, watch it deliver.
5. On C: ranked alerts, acknowledge with ETA; A vibrates with "Help is coming".
6. Situation report, and one live "ask the mesh" question from a judge.
7. On-device AI card: show the phone's profile and which model the selector chose and why (and that a smaller phone in the mesh chose a smaller model or rules). Benchmarks: GPU vs CPU timings (NPU only if it really worked), packet size, hops.

---

## 15. How to work with me

- Start each phase by restating its goal and listing files to create or change.
- Run unit tests after changes to codec, router, triage, gate or extractors.
- For anything needing real devices, give exact steps and what I should see, then wait.
- Keep `docs/DEMO.md` updated with current demo steps and known issues.
- Never claim something works on device unless I confirmed it.
