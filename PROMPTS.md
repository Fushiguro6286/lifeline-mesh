# Lifeline Mesh: prompts for Claude Code

## How to use this file

1. Make an empty folder `lifeline-mesh`. Put `CLAUDE.md` in it, and `DESIGN.md` and `SETUP.md` inside a `docs/` folder.
2. Open the folder in VS Code and start Claude Code in its terminal.
3. Paste the prompts below one at a time. Don't paste the next phase until the current one passes its "done when" checks in `CLAUDE.md`.
4. When a phase needs the phones, Claude Code will tell you what to do. Report back exactly what you saw.

---

## Kickoff

```
Read CLAUDE.md, docs/DESIGN.md, docs/SETUP.md and docs/MODEL_SELECTOR.md fully. They are the spec.

Before writing code:
1. Summarize the project in 5 lines.
2. Check current official docs and list anything in these files that is out of date or risky: library versions marked "latest stable" (look up and propose exact versions), Nearby Connections permissions, foreground service rules, the LiteRT-LM Android API and version, MapLibre PMTiles support.
3. List exactly what you need from me (Android Studio version, phone models and Android versions, model files, fonts, map file, server address).

Then do Phase 0 only. Stop when Phase 0 is done and tell me how to install it on my three phones and what to check.
```

---

## Phase 0: project and design system

```
Do Phase 0 from CLAUDE.md.

1. Gradle project with the version catalog from docs/SETUP.md (pin exact current stable versions), manifest and permissions, Room and DataStore skeletons.
2. The design system from docs/DESIGN.md: Color.kt with Night and Daylight schemes, Type.kt with bundled Archivo and Atkinson Hyperlegible (tell me exactly which font files to download and where to put them), Shapes.kt, Motion.kt.
3. All shared components from DESIGN.md section 6 (SignalRings, HoldToSendButton, NeedChip, SeverityBadge, StatTile, PacketBytesStrip, StatusTimeline, BigIconButton, EmptyState, SimulatedBanner), each with Compose previews in both themes, plus a debug "Component gallery" screen in the app.
4. Navigation with the 4 bottom tabs, the top status strip, Settings, and the 3-page onboarding with the permissions checklist and battery-settings row.

Use placeholder data only inside the component gallery. Check text contrast for every color pairing and fix any below 4.5:1.
```

---

## Phase 1: packet codec

```
Do Phase 1 from CLAUDE.md: Packet sealed classes and PacketCodec for all 7 types in section 6.

Tests: round trip for every type, max-length notes, Hindi and Kannada notes trimmed on whole characters, malformed input (too short, unknown version, unknown type, oversized fields). Run them and show results. Print encoded sizes for: alert with no note, alert with the Hinglish demo sentence, check-in, ack, hello with 3 neighbors.
```

---

## Phase 2: mesh core

```
Do Phase 2 from CLAUDE.md. It is the most important phase; take care.

Order:
1. SeenCache, Outbox, RateLimiter, PeerSync, HelloBeacon and Router as pure Kotlin, behind a Transport interface.
2. MeshSimulator for tests, with tests for: A to C through B, duplicates dropped, TTL stop, B off then on (store and forward via Summary), acks reaching the origin, ping and echo round trip, hello topology, rate limiting, priority ordering.
3. NearbyTransport (P2P_CLUSTER, auto-accept, reconnect with backoff) and MeshService as a connectedDevice foreground service with an ongoing notification.
4. AppRepository exposing StateFlows for neighbors, topology, packets, events and metrics.
5. A debug panel: my origin, neighbor states, "send test alert", "send ping", event log.
6. Clear handling for Bluetooth off, location off, missing permissions, outdated Play services.

Run the simulator tests and show results. Then give me step-by-step real-phone tests (placement, taps, expected result on each phone), including the B-screen-off test, and wait for my report.
```

If phones fail:
```
On the phones this happened: <exact description and the event logs from each phone>.
Find the root cause before changing code. Add logging if needed. Don't continue until A to C through B, B off/on, and screen-off relaying all work.
```

---

## Phase 3: live mesh screen and scoreboard

```
Do Phase 3 from CLAUDE.md, following docs/DESIGN.md section 8.3 exactly. This screen is the centerpiece of the demo, so make it beautiful and smooth.

- Canvas with me at the center, direct neighbors on the inner orbit, two-hop phones from Hello packets on the outer orbit, link styles by state, battery arcs, role icons, gentle idle drift.
- Packet animations along links (700 ms, colored by type), SignalRings on arrival, floating "Alert · hop 2" labels, "N waiting" badge for queued packets.
- Radar sweep and guidance text when no neighbors.
- Draggable bottom sheet: Scoreboard (hops, round trip, bytes; "Not measured" when empty) and the event timeline with filters.
- Tap a node for its details.
- Demo mode (bigger elements, screen awake) and simulation mode with the Simulated banner and labeled metrics.
- Reduced-motion support, 60 fps (precompute layout on topology change, cap active animations).

Test it first in simulation mode and describe what I should see. Then tell me how to check it on the three phones.
```

---

## Phase 4: send flow

```
Do Phase 4 from CLAUDE.md, following docs/DESIGN.md section 8.2.

RuleExtractor tests (port the logic from the web prototype):
- "Water is rising, 5 people on the roof, one elderly person is ill" → trapped + medical, 5 people, severity 3
- "Paani badh gaya hai, chhat par 5 log hain, ek bujurg beemar hain" → trapped + medical, 5 people, severity 3
- "ಮನೆಯಲ್ಲಿ 4 ಜನ ಸಿಲುಕಿದ್ದಾರೆ, ನೀರು ಬೇಕು" → trapped + water or food, 4 people
- "आग लगी है, ३ लोग अंदर हैं" → fire, 3 people (Devanagari digits)
- "please help us" → blocked by QualityGate

Build: SOS screen with HoldToSendButton (3 s hold, haptics, cancel on early release, accessible alternative), Speak and Type buttons, the Review sheet (transcript, source tag, need chips, people stepper, severity, location row, gate question with fixed en/hi/kn strings for now, PacketBytesStrip, Send), Delivery Status timeline with honest states, the "Help is coming" reply state with vibration, and the I'm safe card. Speech can be a stub that fills typed text until Phase 7.
```

---

## Phase 5: rescuer flow

```
Do Phase 5 from CLAUDE.md, following docs/DESIGN.md section 8.4.

Merger (same needs, within 60 m, within 15 min) and Ranker (severity, then report count, then distance) with tests. Rescue screen: header StatTiles, AlertCards, detail sheet with ETA picker and "Send help is coming" (Ack packet), check-ins, community reports, a "Load sample alerts" debug button. The situation report card and Ask button can show "Coming in Phase 8" placeholders for now.

Then give me the full-loop phone test: alert from A, acknowledge on C, reply with vibration on A.
```

---

## Phase 6: map

```
Do Phase 6 from CLAUDE.md, following docs/DESIGN.md section 8.5 and docs/SETUP.md section 6.

1. Give me the exact commands to install the pmtiles CLI and cut venue.pmtiles for this bounding box: <minLon,minLat,maxLon,maxLat around the venue>.
2. Write style.json with fill and line layers only, Night and Daylight versions, loading pmtiles://asset://map/venue.pmtiles.
3. MapLibre map with alert circles (size by people, color by severity, rank number), help points, my position, attribution, and tap to open the alert detail sheet.
4. CanvasFallbackMap used automatically if the map file or MapLibre fails.

Test with Wi-Fi and mobile data off.
```

---

## Phase 7: speech, model selector and local LLM

Do this in two parts. Part A works on the laptop before phones arrive.

**Part A (no phone needed)**
```
Do the laptop-testable part of Phase 7 from CLAUDE.md, following docs/MODEL_SELECTOR.md exactly:

1. assets/models.json with the three-model ladder.
2. DeviceProfiler (RAM, free storage, ABI, chip, Android version, thermal, battery, power save).
3. ModelShortlist as pure Kotlin with the unit tests listed in MODEL_SELECTOR.md section 8. Run them and show results.
4. LlmEngine interface plus a FakeLlmEngine for tests, and ModelSelector logic (load → speed probe → language probe → choose → save) tested against FakeLlmEngine, including fall-through when a probe fails and "Rules only" when nothing passes.
5. ModelInstaller: scan the models folder, import via the system file picker with progress, Wi-Fi-only resumable download for ungated models, size and sha256 check.
6. AiGovernor (thermal, battery, memory) and the On-device AI settings card with "Why this model?".
7. SpeechInput with the on-device recognizer where available, typed input always available.

Mark everything that needs a real phone in docs/DEMO.md under "Needs real phones".
```

**Part B (on the iQOO 15)**
```
Phones are here. Finish Phase 7:

1. Implement the LiteRT-LM LlmEngine (check the current LiteRT-LM Kotlin guide for exact names): GPU backend with CPU fallback, background load, single worker, streaming, timeouts (6 s GPU, 10 s CPU), timing records.
2. Give me the exact adb commands to push the model files from docs/SETUP.md section 5 into the app's models folder.
3. Run the selector on the iQOO 15 and show me the device profile, every candidate with its probe numbers, and the chosen model and backend. Then run it on a second phone and compare.
4. AlertExtractor (labels only, Kotlin maps to the bitmask, strict JSON validation, RuleExtractor fallback, source tag in the Review sheet) and ClarifyingQuestions, both respecting per-language probe results.
5. Benchmarks screen from docs/DESIGN.md 8.6 with GPU vs CPU numbers and "Run benchmark" (fixed prompt, 5 runs).
6. Test with every model removed: the app must say "Rules only" and all features must still work.

NPU is optional: only try it after everything above works, and only with a model file built for this exact chip.
```

---

## Phase 8: LLM extras

```
Do Phase 8 from CLAUDE.md.

- SituationReport: facts computed in Kotlin, LLM phrases them, numbers in the output validated against the facts, fallback to the template.
- AskMesh: chat screen per docs/DESIGN.md 8.4, numbered alerts in the prompt, answers must cite valid #n (tappable chips), otherwise the rule-based answer with a "Rules" tag. Quick chips from CLAUDE.md 9.5.
- Translator for alert notes and ack notes, labeled "Machine translated", original as fallback.
- ReadAloud with TextToSpeech for questions, replies and reports, toggle in Settings.

Show me each working on sample alerts, then with the model missing.
```

---

## Phase 9: sync and polish

```
Do Phase 9 from CLAUDE.md in this order, stopping when I say:
1. server/main.py (FastAPI + SQLite, POST and GET /api/packets idempotent by message ID, an HTML table at /) and ServerUploader. Give me the commands to run it and set the phone's URL.
2. Sending community reports from the SOS tab.
3. BatteryMonitor and drain per hour on the Benchmarks screen.
4. First aid using only assets/firstaid.json, which I will provide.
5. A final design pass: compare every screen with docs/DESIGN.md in both themes and at font scale 1.3, and fix clipped text, spacing, contrast, empty and error states.
```

---

## Prompts for any time

**Make it look better**
```
Review <screen name> against docs/DESIGN.md. List every difference (color, type, spacing, motion, empty/error states, accessibility), then fix them. Keep the one bold element per screen and keep everything else quiet. Show me what changed.
```

**Verify every feature works**
```
Go through the test plan in CLAUDE.md section 13. For each row, say whether it is covered by an automated test, verified by me on phones, or not yet verified. Write the missing automated tests and give me a checklist for the phone tests that remain.
```

**Status and cuts**
```
Summarize progress against the phases in CLAUDE.md: done and verified on phones, done but untested, not started. With <N> hours left, tell me what to cut using the cut order in section 12.
```

**Before the demo**
```
Write docs/DEMO.md: the 3-minute demo from CLAUDE.md section 14 adapted to what actually works, which phone does what, what to do if Nearby fails on stage (switch to simulation mode, clearly labeled), and every measured number with how it was measured. No unmeasured numbers.
```

**Bug hunt**
```
Bug: <what happened, on which phone, what the log says>. Find the root cause before changing code, explain it in two lines, fix it, and add a test if it's in pure Kotlin.
```
