# Lifeline Mesh: demo notes

Living document. Updated at the end of every phase.

## Current state (Phase 10 built; checked on the iQOO 15 alone and in simulation; 3-phone tests still open)

- Phases 0 to 6: app, codec, mesh, Live Mesh, simulation/demo modes, SOS send flow, Rescue tab, offline map.
- Phase 7: on-device AI. Model ladder (assets/models.json), DeviceProfiler, ModelShortlist, ModelSelector (load → speed
  probe → language probe → choose → save), LiteRT-LM engine (GPU, CPU fallback), ModelInstaller (adb folder, import with
  size + sha256 check, Wi-Fi resumable download), AiGovernor (thermal, battery saver, memory), AlertExtractor (strict JSON,
  validated; replaces the rules draft only if valid and not yet edited), ClarifyingQuestions ("Written on device"),
  SpeechInput (on-device recognizer, then the regular one; typed fallback), Settings → On-device AI card ("Why this
  model?"), Benchmarks screen (demo-sentence test, GPU vs CPU, every call, probes, mesh numbers).
- Phase 8: Rescue tab situation report card (numbers computed in Kotlin, template shown at once, model phrasing only if
  every number checks out, "Written on device", refresh, read aloud); "Ask the mesh" screen (4 quick chips, typed
  questions, tappable #n citations that open the alert, "Rules" label on the fallback); "Translate" on alert notes for
  the rescuer and automatic translation of a reply note in another script for the sender ("Machine translated", original
  kept on failure); Read aloud (Settings switch, on by default) for the gate question (also a speaker button) and for
  "Help is coming" replies.
- Phase 9 so far: sync server (server/main.py) and ServerUploader with Settings → Sync; community reports (Rescue tab →
  Add a report); battery drain while relaying (Benchmarks → Mesh); Settings → Export measurements (CSV, share sheet);
  Settings → Battery and background; First aid screen (SOS tab, bottom) waiting for the vetted firstaid.json.
- Phase 10: offline profile (I need help / volunteer / rescue team → Fire service, Ambulance, Police, NDRF/SDRF,
  Other; optional phone number, sent only with this phone's own uploads), live positions (Position packet every
  30 s while moving, every 2 min otherwise; teams see everyone, others see only teams), travel-time estimates computed
  in Kotlin (straight line × detour at an assumed speed, always labelled "estimate"), team views on the Rescue tab
  ("For you" chips), urgent ambulance requests (SOS tab and First aid) with an alarm on Ambulance phones, and the
  internet relay (server assigns each alert to the nearest online team of its type; phones poll every 15 s).
- 209 unit tests pass (25 suites, 10 Oct); debug build and lint clean of errors.
- Verified on the iQOO 15 (10 Oct, by Claude over adb): mesh service runs as connectedDevice|location (types 0x18);
  in simulation, Kiran (ambulance) shows on Map/Rings for a "needs help" phone while Meena and Ravi stay hidden; as
  Ambulance, the Rescue tab opens on "For you (Ambulance)" with "About 4 min by road · estimate"; Ravi's simulated
  ambulance request raised the alarm; internet relay end to end: a request uploaded to the laptop server for a spot
  940 m away was assigned to the iQOO, fetched, alarmed and listed "via internet".
- Verified on the iQOO 15 (10 Oct, by Claude over adb): Settings → Sync uploaded a real panic alert to the laptop server
  over Wi-Fi ("Waiting to upload: 0", server shows it; simulated packets stayed out); a wrong server address shows a
  plain error and keeps the packet queued. Design pass at font scale 1.0 and 1.3 on SOS, First aid, Mesh, Rescue, Map
  and Settings (fixes in the commit "Design pass"). Not yet checked: Daylight theme, Demo mode, Hindi/Kannada layouts.
- Verified on the iQOO 15 (10 Oct): selector picks Gemma 4 E4B on GPU (TTFT 814 ms, 16.6 tok/s) and passes en/hi/kn;
  demo sentences 4/4 correct by the AI; GPU 336 ms / 22 tok/s vs CPU 1,392 ms / 17 tok/s; "please help us" gets an
  AI-written question; with the models moved away the app says "Rules only" and the send flow works on rules.
- NOT verified: speaking a demo sentence (the iQOO has no offline speech pack yet: recognizer errors 13 and 12), and a
  second, smaller phone choosing a smaller tier (unit-tested only).

## Model files on a phone

```
adb shell mkdir -p /sdcard/Android/data/com.lifeline.mesh/files/models
adb push gemma-4-E4B-it.litertlm /sdcard/Android/data/com.lifeline.mesh/files/models/
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.lifeline.mesh/files/models/
```
Then Settings → On-device AI → Re-test (or just reopen the app). From Git Bash, prefix with `MSYS_NO_PATHCONV=1`.

## Simulation mode: the disaster drill (rehearsal, and the demo without spare phones)

Settings → Mesh → Simulation mode. This phone plus 10 virtual phones around the venue, every one run by the real
router over an in-memory mesh. A red "Simulated mesh" banner stays on every screen and every number says Simulated.

| Phone | Role | Where | Story |
|---|---|---|---|
| Ravi | Volunteer | 150 m N | relays; checks in |
| Divya | Volunteer | 550 m SE | relays; asks for an ambulance; reports a shelter |
| Meena | Needs help | 800 m NE | flood: 4 on a roof, one elderly |
| Joseph | Needs help | 1.3 km N | tree on the road, car stuck |
| Farhan | Needs help | 1.4 km E | kitchen fire, 3 inside |
| Lakshmi | Needs help | 1.2 km SE | no drinking water for 8 |
| Kiran | Ambulance | 1.8 km SW | answers ambulance requests and medical alerts |
| Arjun | Fire service | 2.3 km NE | answers fire |
| Priya | NDRF / SDRF | 2.1 km S | answers trapped people and anything else |
| Suresh | Police | 2.1 km NW | answers blocked roads; reports a flooded underpass |

Links (who hears whom): this phone — Ravi, Divya; Ravi — Meena, Joseph; Meena — Farhan — Arjun; Divya — Lakshmi —
Priya; Divya — Kiran; Joseph — Suresh. Everything is at most 4 hops from this phone.

What happens: from 8 s, one incident every 15 s (flood, fire, water, ambulance request, blocked road), then a shelter
report, a road report and a check-in; after that a new incident every 50 s (or tap "Next incident" on the Mesh tab).
The matching team answers each alert with a travel-time estimate from where it is, then drives there on the map in
time-lapse (12× real speed). When it arrives, the person checks in safe. Your own alerts are answered the same way.

Show it: Mesh tab (11 phones, packets hopping), Rescue tab (ranked alerts, "For you" if your role is a team), Map (tap
any marker: alert → alert sheet; person, team, check-in or report → its details), SOS tab (send your own alert or an
ambulance request and watch the team answer and drive to you). Switch a phone off on the Mesh tab to show store and
forward. Choose your own role in Settings first: as a team you see everyone on the map; as "I need help" you see only
the teams. Each simulation session starts clean.

## Phase 10 phone checks (3 phones; laptop server for the last two)

Roles: A "I need help", B "I'm a volunteer", C "I'm on a rescue team → Ambulance". Location on everywhere. Simulation off.

1. Live positions: after ~30 s, C's Map (or Rings) shows A and B with names; A's Map shows only C (the team).
   Walk B 50 m: within ~30 s B's marker moves on C.
2. Travel time: A sends an alert. On C's Rescue tab the card says "About N min by road · estimate"; Acknowledge offers
   that N as an ETA choice. A's status card says "Nearest rescue team: Ambulance (name), X away, about N min".
3. Team view: C opens on "For you (Ambulance)"; a fire-only alert appears under All, not under For you.
4. Ambulance request: on B, SOS tab → "Someone needs an ambulance" → 1 patient, "fell, head injury" → Send. C sounds
   an alarm "Ambulance requested · 1 patient · distance" (and reads it aloud if Read aloud is on). A and B do not.
5. Internet relay: laptop server running; C and B on the laptop's Wi-Fi with Settings → Sync URL set; A on the mesh
   only (Wi-Fi off). A sends an alert: it reaches B over the mesh, B uploads it, the server assigns it to C (dashboard:
   "sent to Ambulance …"), C gets it "via internet" even if C is out of mesh range of B. C acknowledges: the reply
   reaches A through the mesh, or through the server when A finds a connection.

## Phase 9 phone checks

Sync (laptop and phone on the same Wi-Fi):
1. On the laptop: `cd server`, `pip install -r requirements.txt`, `python -m uvicorn main:app --host 0.0.0.0 --port 8000`.
   Allow Python through the Windows firewall if asked. Find the laptop address with `ipconfig` (IPv4).
2. Phone: Settings → Sync → Server URL `http://<laptop IPv4>:8000` → Save. Simulation mode off. Send something real
   (a test alert from the Mesh debug panel, a check-in, or a report). "Waiting to upload" counts it.
3. Expected within a few seconds (the Wi-Fi has internet): "Waiting to upload: 0", "Last upload: <time> · N packets".
   On the laptop open `http://localhost:8000`: the packets are there; an acknowledged alert says "Help is coming".
4. Stop the server, send another alert: Settings shows a plain error ("Cannot reach the server…") and the count stays;
   start the server again and tap Upload now: it goes through. Same packet from two phones: stored once, "Phones that
   uploaded it" = 2.
5. Laptop hotspot without internet: automatic upload does not start (Android does not call it internet); Upload now works.

Community report: Rescue tab → Add a report → Shelter open, note "School hall" → Send report. It appears under
Community reports on this phone and, through the mesh, on the others (and on the map as a help point).

Battery: relay on battery (not charging) for at least 15 minutes, then Benchmarks → Mesh shows "Battery drain: X %/h"
with the start and end level. Before that it says "Not measured". Use runs of an hour or more for numbers you quote.

Export: Settings → Measurements → Export measurements → the share sheet opens with a CSV (send it to yourself).

First aid: SOS tab → First aid. Today it says the guides have not been added (no file yet). Once firstaid.json is in
app/src/main/assets: each guide opens word for word with its source; "What happened?" → Find a guide picks one ("Picked
on device" or "Picked by keywords"); a question about something not covered says "No matching guide." The emergency
line is always on top and under every guide.

firstaid.json format (you supply the text, from a trusted source; the app never writes medical text):
```
[
  {"id": "bleeding", "title": "Heavy bleeding", "keywords": ["bleeding", "cut", "खून", "ರಕ್ತ"],
   "text": "<vetted text, shown exactly as written>", "source": "<organisation, document, year>"}
]
```
Keywords in all three languages help the keyword fallback. Entries without text or source are ignored.

## Phase 8 phone check (one phone is enough)

On the iQOO 15 with the models installed and "AI ready" in the status strip:

1. Settings → Mesh → Simulation mode on, then the Rescue tab → "Load sample alerts". The situation report shows the
   computed lines at once; within a few seconds they change and "Written on device" appears. Every number in the lines
   must match the alert list. Tap the speaker: it reads the report.
2. Tap "Ask the mesh". Tap each quick chip. Expected: an answer with #n chips and "Answered from N stored alerts · Written
   on device"; tapping #1 opens that alert. Type a question with no answer (e.g. "Is anyone on the roof of the temple?"):
   "No matching alerts." or a rules answer, never invented alerts.
3. Set the app language to Hindi, open an alert with an English note, tap "अनुवाद करें": a Hindi line plus "मशीन से
   अनुवाद".
4. Send tab: type "please help us" → review sheet reads the question aloud (Read aloud on); the speaker button repeats it.
5. Fallback: move the models away (adb shell mv .../files/models .../files/models_off), reopen. Status "Rules only". The
   report shows the computed lines with no "Written on device"; Ask answers carry the "Rules" label; no Translate button
   appears; nothing waits or crashes. Move the models back afterwards.

## Phase 3 phone check (needs 3 phones)

Same placement as test 2 below, Mesh tab open on C. Someone who has not seen the app should be able to say, unprompted,
"the alert went from A to B to C" while watching C's screen. Turn on Demo mode for a projector (bigger, screen stays on).

## Phase 2 phone tests (needs 3 phones)

Setup on every phone: install, finish onboarding with a name (A: "Asha", B: "Ravi", C: "Meena"; set C's role to Rescuer),
grant every permission, Bluetooth and location on, battery row allowed, and on vivo/iQOO also "Allow background activity".
Open the Mesh tab on each.

1. Discovery: all three together on a table. Within ~30 s each lists the others under "Direct neighbors" as connected,
   and "Mesh from Hellos" fills in.
2. A to C through B: put A and C far apart (two rooms; check C does NOT list A as a direct neighbor), B between them.
   On A tap "Send test alert". Expected: A "Passed to 1 nearby phone"; C's packet list "From Asha-xxxx · alert · 2 hops";
   C's events "...alert arrived · 2 hops".
3. Ping: on A tap "Send ping". Expected under Measured on A: "Round trip: N ms (to <C> over 2 hops)" (B may also echo,
   over 1 hop).
4. Reply: on C tap "Reply to latest alert". Expected: A vibrates, shows a "Help is coming, ETA 20 min" notification,
   and "Your last alert: Help is coming".
5. B off/on: turn B's Bluetooth and Wi-Fi off (or tap Stop mesh on B). On A send a test alert: "Waiting" / not at C.
   Turn B back on: within ~30 s C gets it (events on A: "Sent 1 stored packet to Ravi-xxxx").
6. Screen off: lock B's screen, wait 1 minute, send from A. C must still get it through B.
7. Duplicates: put all three close (triangle). Send from A. Each phone stores it once; events show "Dropped a copy".

## Profiles and sign-in (added 10 Oct 2026, on request)

- Settings (gear, top right) → You: profile card with name, role and Sign out. Sign out opens "Who is using this
  phone?": pick a profile, enter its password, or Create a new profile (the usual onboarding, now with a password).
- Profiles live only on this phone (DataStore); passwords are stored as salted PBKDF2-SHA256 hashes, never in plain
  text (ProfilesTest). A profile holds name, phone, role and travel; language and theme stay per phone.
- An install that onboarded before this change has no password yet: Settings → You shows "Set password" first.
- Removed on request: the AI label in the top strip (it now shows only phones nearby), and Settings → On-device AI and
  Settings → Measurements. The Benchmarks screen has no entry point any more. CLAUDE.md demo step 7 (AI card,
  Benchmarks) and Export measurements need a new way in if they are still wanted for the demo.

## First aid guides (added 10 Oct 2026, on request)

- SOS tab → First aid: 17 English guides (most serious first), search box ("What happened?", on-device pick or keywords,
  English plus some Hindi/Hinglish/Kannada words), every guide shown word for word with its source and "Contact
  emergency services if you can." Checked on the iQOO 15: list, search ("twisted my ankle" → Sprains, picked on device),
  opening CPR from the list.
- **Before the demo: have a doctor, nurse or certified first-aid trainer read all 17 guides** in
  `app/src/main/assets/firstaid.json`, fix anything they want changed, then set `"reviewed": true` on each checked
  guide. Until then each guide says "Not yet reviewed by a medical professional". Emergency numbers used: 112 (and 108
  for an ambulance).

## Rescue queue and finished tasks (added 10 Oct 2026, on request)

- A team works most urgent first (severity 3 before 0), then in the order it accepted jobs; a new urgent job makes it
  turn (real teams on the map and in the sender's ETA, and the simulated teams). Responders.queues, RespondersTest.
- ETA through the queue: the sender's live estimate goes from the team's position through every job ahead of theirs,
  then to them (urgent leg + the leg on). The rescuer's own estimate on a card and in Acknowledge does the same with
  their own open jobs and says "Includes your N more urgent or earlier jobs first".
- "Are you safe?" → I'm safe after an alert closes it: the alert leaves the Rescue list, the counts and the map, and
  that person's check-in row and map marker go too (MergedAlert.closedBy). A newer report of the same emergency opens it
  again. Reaching the place alone does not close it (arriving is not the same as rescued).
- Checked on the iQOO 15 in simulation: alerts appear, are acknowledged, and leave the list after the team arrives and
  the person checks in; the Check-ins section stays empty. Real 3-phone check still open.

## Range test: farthest direct link (added 10 Oct 2026, on request)

Google publishes no range for Nearby Connections (Bluetooth, BLE and Wi-Fi together), so the app measures it: when a
Position packet arrives over one link, the GPS distance between the two phones is a working link distance. The Mesh tab
shows the farthest one under the scoreboard ("Farthest direct link: N m to Ravi ±U m (GPS)"), recorded as
`link_range_m` (in Export measurements). Only fresh fixes (≤ 60 s) with both accuracies adding up to ≤ 50 m count.
Checked on the iQOO 15 in simulation: "544 m to Divya ±34 m (GPS) · Simulated" (Divya is placed 550 m away).

Walk-apart test (2 phones, simulation off, location on, outdoors, Mesh tab open on both):
1. Start side by side until each lists the other as a connected direct neighbor.
2. One person stays. The other walks away in a straight line, stopping for 30 s every ~25 m (a phone on the move sends
   its position every 30 s).
3. When the stationary phone shows the other as lost, walk back. The Mesh tab keeps the farthest distance measured.
4. Repeat indoors, and with Wi-Fi off (Bluetooth only), and write the numbers in docs/MEASUREMENTS.md.

## Phones without the app (added 10 Oct 2026, on request)

A phone without the app cannot receive mesh messages, so the SOS tab offers two things instead (card "Phones without
this app"):
- Show my SOS in Bluetooth lists: the phone's Bluetooth name becomes e.g. "SOS · trapped+medical · 3 ppl · Asha"
  (from my latest alert, at most 40 characters, English on purpose) and the system asks to make it visible. Android
  caps that at 300 s per request (SDK 36.1 BluetoothAdapter docs), so the card counts down and offers Show again. Stop,
  or I'm safe, puts the original name back (kept in SharedPreferences, so it survives the app closing). One-way: the
  other person can only see the name. Checked on the iQOO 15: name "SOS · needs help · Piyush", scan mode
  CONNECTABLE_DISCOVERABLE, still set after 30 s, Stop restored "iQOO 15".
  With simulation off, see "Bluetooth SOS with the real mesh" below. Still to do: look for the SOS name from a second
  phone (Bluetooth → pair new device) for the full 5 minutes.
- Share this app: copies the installed APK (about 69 MB debug) and opens the share sheet. Quick Share is fast;
  Bluetooth takes several minutes. The receiver must allow installing apps from that source. Checked on the iQOO 15:
  share sheet with LifelineMesh.apk, Quick Share, iQOOshare, Bluetooth (nothing sent).
- The card's Hindi and Kannada texts are machine-drafted: check them with a native speaker.

## Official disaster warnings (added 10 Oct 2026, on request)

- The laptop server polls NDMA SACHET's public CAP feeds (default Karnataka; LIFELINE_SACHET_FEEDS=karnataka,india,...
  or off; every LIFELINE_SACHET_EVERY=300 s). Only warnings the issuer rated Severe or Extreme, status Actual, not
  cancelled and not expired go to phones, in GET /api/feed. Every online phone turns each into a Warning packet (type 8)
  and passes it into the mesh; the message ID comes from the CAP identifier, so copies from several phones are one.
- Phones that get one (from the server or over the mesh): strong vibration, an alarm notification (own channel, alarm
  sound), read aloud if on, and a red banner on the SOS and Rescue tabs until it expires. Each says how it arrived:
  "From the rescue server" or "Passed on by nearby phones, cannot be verified" (links are not authenticated, so any
  phone with the app could fake one). Real ones add "Source: NDMA SACHET"; drills say "DRILL, not real".
- Feeds are per state, not per district: the official headline names the districts. The per-warning polygon file
  answers scripts with 403, so it is not used.
- Drill for rehearsal and the demo: dashboard on the laptop (http://localhost:8000) → "Send a drill warning to phones"
  (only from the laptop itself).
- Checked 10 Oct: a live poll read 10 real Karnataka warnings (all Moderate thunderstorms, so none went to phones).
  On the iQOO 15 (simulation off, laptop server): a drill reached the phone on its next poll, the alarm notification
  "DRILL, not real: Flood" showed, and the SOS banner said "In force until 1:06 PM · From the rescue server".
  NOT checked: a warning travelling over the mesh to a phone with no internet (needs a second phone).
- Hindi and Kannada warning texts are machine-drafted: check them.

Bluetooth SOS with the real mesh (checked 10 Oct on the iQOO 15): Nearby Connections renamed the adapter to its own code
about 90 s in. The card now puts the SOS name back every 3 s while the phone is visible; with that the name held for
3.5 minutes with the mesh running, and Stop restored "iQOO 15", which then stayed. Trade-off: during those minutes
Nearby's classic-Bluetooth discovery may be weaker (BLE and Wi-Fi are not affected). Stop cannot end the visible window
early (apps cannot), it runs out by itself.

## Watch vitals alarm (added 10 Oct 2026, on request)

- Settings → Safety → Watch vitals alarm (off by default) and Health Connect (tap to allow). While the mesh runs, the
  service reads blood oxygen, blood pressure and heart rate that a watch saved in Health Connect, every minute, for the
  last 30 minutes. A reading outside the limits starts the same 15 s alarm as a fall ("Are you OK? Your watch read:
  Blood oxygen 86%"); no answer sends an urgent ambulance request (medical + ambulance, severity 3, position, note
  "Watch: ..."), which rings Ambulance phones. Each reading alarms once.
- Limits (VitalsCheck, unit-tested): oxygen below 90 %; blood pressure 180/120 or more, or systolic below 90; heart rate
  under 40 or over 150 on two readings within 5 minutes. **Commonly published thresholds, not yet reviewed by a
  doctor: have a medical professional check them before the demo**, as with the first-aid guides. The Settings text
  says they are not reviewed.
- Not real time: the watch's own app (Samsung Health, Fitbit, Pixel Watch, ...) must write to Health Connect, and data
  often arrives minutes late. Most watches do not measure blood pressure at all. vivo/iQOO watches may not write to
  Health Connect: check with the watch you will use.
- Checked on the iQOO 15: Health Connect available (first use opened its own setup), the permission screen listed the
  three readings plus background access, all granted; the service then read Health Connect ("Last read 1:02 PM: 0
  readings", no watch paired). In simulation, "Try the watch alarm" ran out and sent the request: "Medical · Ambulance
  needed", answered by Kiran (Ambulance), ETA 4 min, "They are here". "I'm OK" cancels it.
  NOT checked: a real watch reading.
- Left on the iQOO: Health Connect permissions granted, the Watch vitals alarm switch off.

## Simulated weather (added 10 Oct 2026, on request)

- Simulation on: the SOS weather card shows a storm story instead of the real weather, labelled "Simulated weather":
  Partly cloudy 29 °C → Heavy rain (150 mm/24 h) → Thunderstorm with hail → Violent showers (210 mm, gusts 120 km/h)
  → Mainly clear, a step every 75 s or on "Next weather". Dangerous steps send simulated forecast warnings (same
  thresholds as server/weather.py, SimWeatherTest) into the simulated mesh: alarm and banner titled "Weather warning: …
  · Simulated", hopping through the virtual phones. No server or internet needed. The on-device model still phrases the
  numbers ("Written on device").
- Simulation off and online: the real weather from the server, as before. In simulation the real server's forecast
  warnings are ignored (they would contradict the story); official warnings and drills still come in.
- Checked on the iQOO 15: Partly cloudy → Heavy rain (alarm "Weather warning: Very heavy rain · Simulated", events
  "The rescue server's weather warning arrived …", "Passing … on to 2 phones") → hail → violent showers (two warnings).
- Known: in simulation the event log still says the warning "arrived over the internet" (the simulated server uses the
  same route as the real one). Simulated warnings last 6 h; switching simulation off and on starts a clean session.

## Live Bluetooth watch (added 10 Oct 2026, on request)

- SOS tab → Your health (watch) → Connect a Bluetooth watch: scans for devices advertising the standard Bluetooth health
  services (Heart Rate 0x180D, Pulse Oximeter 0x1822, Blood Pressure 0x1810), keeps the chosen one connected while the
  mesh runs and shows each reading as it arrives (heart rate about once a second). Decoders: BleVitals (tested).
- Link lost: red "Connection to … lost at …. Your health is not being watched", a vibration and a notification; Android
  reconnects by itself when the watch is back in range (autoConnect), and the notification clears. A first attempt that
  fails stays "Connecting…" and keeps retrying (no false "lost").
- The alarm switch covers both the live stream and Health Connect. Live rules: heart rate outside 40–150 for 30 s,
  oxygen below 90 % for 15 s (a spot check still counts at once), then a 5-minute quiet period after any alarm, so one
  bad minute rings once, not every second. VitalsCheckTest.
- Most Samsung, Apple and vivo watches do not expose these services: they stay on Health Connect. Many sports watches
  and bands do when "heart rate broadcast / sharing" is on.
- Simulation mode: the picker offers "Simulated watch · Simulated" (only there; a real watch waits until simulation is
  off). Buttons: Simulate low oxygen / Back to normal, Simulate losing the watch (20 s, then it reconnects).
- Checked on the iQOO 15 (10 Oct): simulated watch live (98 %, 71/min, "now"); Simulate low oxygen → alarm "Your watch
  read: Blood oxygen 86%" after ~15 s → no answer → "Medical · Ambulance needed" → Kiran (Ambulance) ETA 2 min, "They
  are here"; Simulate losing the watch → red line + "Watch connection lost · Simulated" notification → back after 20 s,
  notification cleared. Real device: a Galaxy Fit3 nearby advertised the heart rate service; the first connection
  failed (status 62), the automatic retry connected and it streamed 98, 96, 141 /min live. Disconnected afterwards.
  NOT checked: a pulse oximeter or blood pressure cuff; a real link loss and reconnect.
- Left on the iQOO: no watch saved, alarm off, simulation on.

## Simulation mode with the internet on (added 10 Oct 2026, on request: stage demo)

- In simulation mode the phone still polls the rescue server when online, but only for real-world, read-only things:
  live weather (SOS tab card) and warnings (official, forecast check, drill). A warning enters the simulated mesh, is
  stored as simulated (it shows only while simulation is on) and hops through the virtual phones: the Mesh tab events
  show "The rescue server's drill warning arrived over the internet", "Passing … on to 2 phones", "Dropped a copy …".
- Still kept apart: real alerts and replies from the server never enter the simulated mesh; simulated packets are never
  uploaded; in simulation the phone polls as a plain phone (role 1, no position for teams), so the server never
  registers it as a team or assigns it real alerts; the server cursor is kept, so nothing real is skipped afterwards.
- Demo: laptop server running (python -m uvicorn main:app --host 0.0.0.0 --port 8000 in server/), phone on the same
  Wi-Fi with Settings → Sync set, simulation on. Weather appears within ~15 s. For a warning, open http://localhost:8000
  on the laptop → "Send a drill warning to phones" (use a few minutes, e.g. 5): the phone rings, shows "DRILL, not real",
  and the Mesh tab shows it hopping. Checked on the iQOO 15 (10 Oct).

## SOS tab: live weather and watch readings (added 10 Oct 2026, on request)

- Weather here (SOS tab, under Speak/Type): when the phone is online and the rescue server is set, each feed poll
  sends a coarse position (rounded to 0.1 degree, about 11 km) and the server returns Open-Meteo's current conditions
  and 24-hour numbers for that cell (cached 10 min per cell). The card shows temperature, condition, one sentence, the
  source and the time; "Not updated since …" in amber after 30 min without a connection. Off in simulation mode.
- The sentence: in English the on-device model phrases the numbers ("Written on device"); it is used only if it is one
  line and every number in it is one of the facts. Numbers written as words are rejected too (on the iQOO the model
  first wrote "thirty degrees"; the prompt now asks for digits). Otherwise, and in Hindi/Kannada, the computed sentence.
- Dangerous weather is decided by fixed rules on the server (weather.py, tested), never by the model: 24 h rain >= 115.6
  mm (IMD very heavy), gusts >= 89 km/h (IMD severe cyclonic storm winds), thunderstorm with hail, temperature >= 45 C
  (IMD heat wave). Each becomes a warning with the forecast flag (packet type 8, flags bit 1): same alarm, banner and
  mesh relay as official warnings, titled "Weather warning: …" and labelled "Forecast check (Open-Meteo), not an
  official warning". Weather never sends an SOS by itself; only a person (or the watch alarm) does.
- Your health (watch): latest blood oxygen, heart rate and blood pressure from Health Connect (last 24 h, read every
  minute while the SOS tab is open), red when outside the limits, "Connect your watch" if not allowed, and the alarm
  switch (the 15 s alarm then an urgent ambulance request, see "Watch vitals alarm"). The model is not used for health.
- Checked on the iQOO 15 (simulation off, laptop server): real Bengaluru weather "30 °C Partly cloudy", model sentence
  "It is partly cloudy with a temperature of 30 °C and wind gusts up to 42 km/h." (Written on device); the server log
  showed the phone sent only wlat=13.0&wlon=77.6. With a test server faking 150 mm of rain: alarm notification
  "Weather warning: Very heavy rain", red banner with the forecast label, card "24 °C Heavy rain". The test warnings
  were then deleted from the phone. Health card: connected, "No readings from your watch in the last 24 hours".
  NOT checked: real watch readings; a forecast warning relayed to a phone without internet.

## Round trip on the scoreboard (fixed 10 Oct 2026)

- Before: only the Mesh debug panel's "Send ping" measured a round trip, so the Mesh tab tile stayed "Not measured".
- Now: the sender pings its alert as soon as a neighbor takes it (answered by the first receiver and any team phone),
  and a team phone pings each new alert back to its sender (the sender answers). At most one automatic ping per 30 s.
  Checked on the iQOO 15 in simulation: "Round trip to Ravi: 247 ms over 1 hop", "to Meena: 1005 ms over 2 hops",
  tile shows ms with "Simulated". Real 3-phone check still open: A and C each show a round trip after A's alert.

## Presentation features (added 10 Oct 2026, on request)

- How it works (Demo mode only; top-bar gauge icon next to Settings, or Settings -> Demo mode -> How it works): the last
  alert step by step with this phone's measurements (AI model and time, bytes, hops, round trip), the On-device AI card
  (model, why, speed, languages, Benchmarks) and Export measurements. Remove model now needs a second tap.
  Checked on the iQOO 15: "Gemma 4 E4B on GPU read the message in 4095 ms", 61 bytes, 2 hops (Simulated), en/hi/kn ok.
- Fall and crash detection (Settings -> Safety, off by default; "Try the fall alarm" for stage): impact + stillness ->
  15 s alarm (ringtone, vibration, full-screen "I'm OK"), then a panic SOS with position. Checked on the iQOO 15: test
  alarm shows over the app, "I'm OK" cancels (no SOS), no answer sends the SOS, the switch registers the accelerometer
  at 50 Hz. NOT checked: a real drop (needs you: switch it on, drop the phone onto a cushion, leave it still ~3 s),
  the notification's "I'm OK" button (vivo's shade could not be opened over adb), the alarm over the lock screen.
- Live ETA after "Help is coming": live estimate from the team's position, "They are here" on arrival, else the ETA they
  gave counted down. Checked on the iQOO 15 in simulation: 15 min / 1.9 km -> 8 min / 1.0 km -> 1 min / 80 m -> They are here.
- Rescue tab shows my own check-in ("Piyush is safe · you"). Checked on the iQOO 15.
- Map: dashed line from each responder to its job; tap a team or volunteer for where it is heading. Non-team phones see
  only teams coming to them. Checked on the iQOO 15: line for a team phone; "On the way to you · about 1 min" and
  "On the way to help someone else" sheets. NOT checked on the phone: the team-phone sheet text "Heading to: ...".

## Demo steps

Not ready. The 3-minute script in CLAUDE.md section 14 needs phases 2 to 7.

## Needs native-speaker review

Machine-drafted translations. Please have a native speaker check them before the demo:

- `app/src/main/res/values-hi/strings.xml` (Hindi): SOS flow, need and severity words, delivery status, onboarding, permissions checklist.
- `app/src/main/res/values-kn/strings.xml` (Kannada): same set.

Phase 2 and 4 additions to check: status strip and notification texts, and the whole send flow (sos_type_*, review_*,
gate_q_*, gate_ready, status_title/eta/panic/send_another, checkin_*).

Words to check first, because they appear on stage: "SOS के लिए दबाकर रखें" / "SOS ಗಾಗಿ ಒತ್ತಿ ಹಿಡಿಯಿರಿ" (Hold for SOS), "मदद आ रही है" / "ಸಹಾಯ ಬರುತ್ತಿದೆ" (Help is coming), "पास के फ़ोन का इंतज़ार" / "ಹತ್ತಿರದ ಫೋನ್‌ಗಾಗಿ ಕಾಯಲಾಗುತ್ತಿದೆ" (Waiting for a nearby phone).

Settings and developer screens are English only on purpose (DESIGN.md section 9).

## Known issues

- Profiles: the mesh origin ID is per install, not per profile, so after switching profiles this phone's earlier alerts
  and replies still show as "yours". Signed out, the SOS screen is behind the sign-in screen; the mesh keeps relaying only
  if it was already running. There is no change-password, delete-profile or forgotten-password reset yet.
- Voice: needs the offline speech pack for the app language. On the iQOO both recognizers report the language as
  unavailable. Fix before the demo: Settings → (Google) Speech / "Speech Recognition & Synthesis from Google" → offline
  speech recognition → download English (India) and Hindi; or use Speak → "Download offline voice" (Android 13+) on Wi-Fi.
  Kannada offline voice may not exist; Kannada works by typing.
- First launch after installing models or updating the app re-runs the probes (~30 s with E4B); the status strip says
  "Testing AI" meanwhile and the rules are used.
- The AI card, Benchmarks, situation report and Ask the mesh texts are English only and written in the code (rescuer
  and developer screens, DESIGN.md section 9).
- Sync uses plain HTTP to a laptop on the local network; cleartext is allowed app-wide because the address is typed at
  runtime. Only ServerUploader opens connections.
- First aid has no guides until firstaid.json is supplied. Its Hindi and Kannada screen texts (including "Contact
  emergency services if you can.") are machine-drafted: check them with a native speaker first.
- Live positions are hidden from non-team phones by the app, not by encryption: any phone relays Position packets.
  Team roles are self-declared ("not verified" is shown when choosing one).
- Live position with the screen off needs the location foreground-service type, which Android allows only when the
  mesh starts while the app is open (or with background-location permission, which the app does not ask for). If the
  system restarts the service in the background it falls back to relaying only; positions resume when the app opens.
- The internet relay brings no real alerts or replies into simulation mode (real and simulated packets never mix); only
  weather and warnings work there (see "Simulation mode with the internet on").
- Phase 10 Hindi and Kannada strings (team names, ambulance request, nearest team) are machine-drafted: check them.
- The sender's "Help is coming" card no longer quotes the rescuer's reply note (it named the vehicle, e.g. "Rescue boat
  on the way"); it says "They are on the way" (hi: "वे रास्ते में हैं", kn: "ಅವರು ದಾರಿಯಲ್ಲಿದ್ದಾರೆ", machine-drafted). The reply
  notification and read-aloud leave the note out too.
- Read aloud uses the phone's TextToSpeech voices. If the phone has no Hindi or Kannada voice, it reads with the default
  voice (check Settings → Text-to-speech on each phone).

- The offline map covers central Bengaluru (77.55–77.65 E, 12.93–13.01 N), not a confirmed venue box. Give me the venue
  and I will re-cut it (one command, see SETUP.md section 6). Outside the box the map says so; Rings still works.
- My position on the map is a dot, not an arrow: the app does not read the compass, so an arrow would claim a heading
  it does not know.

- RuleExtractor was written from scratch: the web prototype's source was not on this laptop. If you have it, the rules
  can be aligned with it. Behaviour is pinned by RuleExtractorTest.
- "Approximate" location is shown to the sender only; the packet format has no flag for it (the accuracy byte carries the
  fix's own accuracy).
- Notes longer than 60 bytes are trimmed on whole characters; the review sheet shows exactly what is sent ("Sent as").

- iQOO 15 (measured 9 Oct): without the battery-optimization exemption the OS freezes the app ~10 s after the screen turns
  off, even with the foreground service running (`isFrozen=true`, wake lock disabled). With the exemption it stays a
  foreground service and keeps its wake lock. The Mesh tab shows a warning with a fix button when the exemption is missing.
- A packet arriving over several hops is animated along the path it most likely took (shortest known path with the
  same hop count, from Hellos). The hop count is measured; the path drawn upstream of my neighbor is inferred.
- Endpoint name is fixed when the mesh starts; after changing your name, Stop and Start the mesh.
- Links are not authenticated (auto-accept). Fine for an open emergency mesh; anyone with the app can join.

- Phase 0 "done when" is only partly checked: phones B and C are not installed yet (you chose to move on, 9 Oct). Phone B (vivo T4x 5G) did not expose an ADB interface over USB; try PTP mode, another cable, or Wireless debugging.
- The Hinglish demo sentence is 64 bytes, over the 60-byte note limit, so the sent note ends at "...ek bujurg beemar". Needs, people and severity travel as structured fields, so nothing important is lost; the review sheet (Phase 4) must show the trimmed note so the sender sees exactly what goes out.

- On vivo/iQOO, "Allow background activity" in the app's battery settings is a vendor switch the app cannot read. The checklist tick only reflects Android's battery-optimization exemption; the row tells the user to also open the app's battery settings.

## Needs real phones

- Phase 2 tests above (need phones B and C).
- Install on phones B and C, complete onboarding, grant every permission (Phase 0 "done when"). iQOO 15 done.

## Build notes

- Use Android Studio's bundled JDK: `JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"`. The `Android Studio/jbr` folder on this laptop is incomplete (no `jvm.cfg`).
