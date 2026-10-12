# Lifeline Mesh

**Panic in, clear alert out, even with no network.**

Lifeline Mesh is an Android app that keeps emergency alerts moving when cell towers and the internet are down — floods, cyclones, earthquakes. A person speaks, types or holds a panic button; on-device AI turns it into a tiny structured alert; nearby phones relay it phone to phone over Bluetooth and Wi‑Fi until it reaches a rescuer, who can reply "help is coming" back through the same mesh.

Built solo for the **iQOO Hackathon Grand Finale** (48 hours, Bengaluru), demoed on an iQOO 15 (Snapdragon 8 Elite Gen 5, Android 16) plus two other Android phones.

---

## How it works

1. **Ask for help** — speak or type an SOS in **English, Hindi or Kannada**, or hold the panic button for 3 seconds.
2. **Understand it on the phone** — deterministic rules (always) and an optional local LLM extract the emergency type, number of people, severity and GPS position.
3. **Check it** — a quality gate asks one short question if something essential is missing ("please help us" is not enough to send).
4. **Relay it** — the alert is encoded into a ~30–90 byte packet and flooded across nearby phones using Google Nearby Connections, with a hop limit, duplicate suppression and store-and-forward.
5. **Act on it** — a rescuer's phone merges duplicate reports, ranks them, shows them on an offline map and sends an acknowledgement with an ETA back through the mesh.
6. **Sync when possible** — any phone that finds internet uploads what it holds to a small server, which can forward alerts to the nearest online rescue team.

```
 Voice / text / panic ─► RuleExtractor (+ optional on-device LLM) ─► QualityGate ─► PacketCodec
                                                                                        │
              other phones ◄──► NearbyTransport ◄──► Router (TTL, dedupe, rate limit, store & forward)
                                                        │
                       Room DB ─► Rescuer view (merge, rank, dispatch) · Live mesh view · Offline map
                                                        │
                                          only when internet appears ─► FastAPI sync server
```

## Design principles

- **The core never depends on the LLM.** Packet building, routing, triage ranking and the quality gate are plain, unit-tested Kotlin. The LLM only helps with extraction, clarifying questions, translation and summaries — every call has a timeout and a non-AI fallback.
- **No internet for core features.** Only the server uploader touches the network.
- **Never fake numbers.** Hop counts, round-trip times, packet sizes, AI timings and battery drain are measured on device; anything unmeasured says "Not measured", and simulated values are always labelled "Simulated".
- **Grounded AI.** Rescuer answers cite the stored alerts they came from (`#1`, `#2`…), or say "No matching alerts".
- **Medical safety.** The first-aid helper only shows vetted guide text verbatim and always says to contact emergency services.

## Features

**For people who need help**
- Voice or typed SOS in English, Hindi, Hinglish and Kannada; 3‑second hold-to-send panic button
- Honest delivery status: *Waiting for a nearby phone → Passed to N phones → Reached a rescue team's phone → Help is coming*
- "I'm safe" check-ins, community reports (road blocked, water available, shelter open/full)
- **Where to go:** the nearest open shelter by walking time on offline roads, drawn on the map
- Urgent ambulance request sent directly to ambulance teams
- Fall / crash detection from the accelerometer, and optional watch vitals via Health Connect
- **SOS for phones without the app:** the phone's Bluetooth name becomes a short SOS line anyone nearby can see
- First-aid guides (17 guides, read aloud on request)

**For rescuers and teams**
- Team roles: Firefighter, Ambulance, Police, NDRF, other rescue teams, volunteers
- Merged, ranked alert list with filters by team type
- **Who goes where:** a deterministic dispatch split so every team phone suggests the same assignment
- Offline map (MapLibre + PMTiles) with live positions, accuracy circles and A* road routing that avoids reported blockages
- On-device situation report and "Ask the mesh" chat grounded in stored alerts
- Machine translation of notes (labelled as such)

**Mesh resilience**
- Controlled flooding with TTL 5, an LRU seen-cache and per-origin rate limiting
- Store-and-forward for 6 hours with summary exchange on every new connection
- Live Mesh view built from Hello beacons, with an event timeline and scoreboard
- **Low-battery survival mode** that drops non-urgent traffic below 20 % and 10 %
- **Relay gap finder** that spots lost phones and phones that are someone's only link
- Simulation mode (4 virtual phones running the real router), always clearly bannered

## On-device AI

The app profiles the phone and picks the best local model it can run well, probing speed and each language before trusting it:

**Gemma 4 E4B → Gemma 4 E2B → Gemma 3 1B → rules only**

Models run through **LiteRT-LM** on the GPU, with CPU fallback. An `AiGovernor` pauses non-essential AI on thermal pressure, low battery or low memory. Details in [`docs/MODEL_SELECTOR.md`](docs/MODEL_SELECTOR.md).

Measured on the iQOO 15 with Gemma 4 E4B (5 runs each, see [`docs/MEASUREMENTS.md`](docs/MEASUREMENTS.md)):

| Backend | Time to first token | Decode speed |
|---|---|---|
| GPU | 336 ms | 22 tok/s |
| CPU | 1,392 ms | 17 tok/s |

Mesh hop counts, round-trip times and battery drain on real phones are measured by the app and exported from Settings; see `docs/MEASUREMENTS.md` for what has been recorded so far.

## Packet format

Compact big-endian binary with a 19-byte header (version/type, 64-bit message ID, random per-install origin, TTL, hop count, timestamp). Every packet stays under the 1 KB Nearby payload limit — an alert with no note is **31 bytes**, a full Hindi note about 89.

Types: Alert, Check-in, Ack, Community report, Ping, Hello, Position, Official warning, and Summary (control). Full spec in [`CLAUDE.md`](CLAUDE.md#6-packet-format-binary-big-endian).

## Tech stack

| Area | Choice |
|---|---|
| App | Kotlin, Jetpack Compose, Material 3, Navigation Compose |
| State & storage | ViewModel + StateFlow, Room (KSP), DataStore |
| Mesh | Google Nearby Connections (`P2P_CLUSTER`) in a foreground service |
| Location | Fused Location Provider (offline GPS), with fix filtering |
| Speech | Android `SpeechRecognizer` and `TextToSpeech` |
| Local LLM | LiteRT-LM with Gemma 4 / Gemma 3 |
| Map | MapLibre Native Android + offline PMTiles, Canvas fallback |
| Health | Health Connect |
| Server | Python, FastAPI, SQLite |
| Tests | JUnit, kotlinx-coroutines-test, pytest |

`minSdk 26`, `targetSdk 37`, ABI `arm64-v8a`.

## Getting started

### Requirements
- Android Studio (recent stable, with bundled JDK) and the Android SDK
- One or more Android phones (three to demo a real relay) with Bluetooth, Location and up-to-date Google Play services
- Python 3.10+ for the optional sync server

### Build and install

```bash
git clone https://github.com/Fushiguro6286/lifeline-mesh.git
cd lifeline-mesh
./gradlew :app:installDebug
```

On each phone: allow background activity / turn off battery optimization for Lifeline Mesh (important on vivo/iQOO and similar brands), and download offline speech packs for English (India) and Hindi.

### Add a local model (optional)

The app works fully with rules only. To enable on-device AI, download a `.litertlm` model from Hugging Face (e.g. `litert-community/gemma-4-E2B-it-litert-lm`), rename it to match `app/src/main/assets/models.json`, and push it:

```bash
adb shell mkdir -p /sdcard/Android/data/com.lifeline.mesh/files/models
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.lifeline.mesh/files/models/
```

Then open **Settings → On-device AI → Re-test**.

### Run the sync server (optional)

```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Set the server URL in the app's Settings to your laptop's address on the same Wi‑Fi (e.g. `http://192.168.x.x:8000`). Open `http://localhost:8000/` for a live dashboard of alerts, teams, check-ins and reports. The server also relays live weather (Open-Meteo) and official NDMA SACHET warnings into the mesh.

### Run the tests

```bash
./gradlew :app:testDebugUnitTest     # codec, router, mesh simulator, triage, AI fallbacks, safety…
python -m pytest server              # server, weather and warnings
```

Full setup — permissions, map files, fonts and a pre-demo checklist — is in [`docs/SETUP.md`](docs/SETUP.md).

## Project structure

```
app/src/main/kotlin/com/lifeline/mesh/
├── ai/         rule & LLM extraction, quality gate, model selector, reports, Ask the mesh, first aid
├── packet/     binary packet format and codec
├── mesh/       foreground service, Nearby transport, router, outbox, beacons, power policy, simulator
├── triage/     merging, ranking, dispatch, shelters, teams
├── routing/    offline road map and A* route planner
├── safety/     fall detection, watch vitals
├── location/   location provider and fix filter
├── metrics/    measurements and battery monitor
├── sync/       server uploader
├── data/       Room database, profiles, settings
└── ui/         Compose screens: send, rescuer, mesh, map, onboarding, settings
app/src/main/assets/   models.json, firstaid.json, offline map
server/                FastAPI sync server, weather and official warnings
docs/                  design, setup, demo script, measurements, model selector, pitch deck
```

## Documentation

| Doc | What's inside |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Full project brief: rules, architecture, packet spec, mesh behaviour, build phases, test plan |
| [`docs/DESIGN.md`](docs/DESIGN.md) | Visual design and every screen |
| [`docs/SETUP.md`](docs/SETUP.md) | Tools, dependencies, permissions, models, map and server setup |
| [`docs/MODEL_SELECTOR.md`](docs/MODEL_SELECTOR.md) | How the app picks the local LLM for each phone |
| [`docs/MEASUREMENTS.md`](docs/MEASUREMENTS.md) | What was measured, how, and on which device |
| [`docs/DEMO.md`](docs/DEMO.md) | The 3-minute stage demo script |

## Important notes

- **Not a replacement for emergency services.** Lifeline Mesh is a hackathon prototype. Always contact official emergency services when you can.
- **First-aid guides are not yet medically reviewed.** They were drafted from published Red Cross, St John, NHS and WHO guidance and are shown with a "Not yet reviewed by a medical professional" label until a qualified person checks them.
- **Team roles are self-declared** and not verified.
- **Privacy:** phones are identified by a random per-install ID, never a phone number. Positions of people in need are visible only to rescue teams.

## Credits

- Map data © OpenStreetMap contributors, basemap by [Protomaps](https://protomaps.com)
- Fonts: [Archivo](https://fonts.google.com/specimen/Archivo) and [Atkinson Hyperlegible](https://fonts.google.com/specimen/Atkinson+Hyperlegible), SIL Open Font License (see `docs/licenses/`)
- Models: Gemma by Google, run with LiteRT-LM
