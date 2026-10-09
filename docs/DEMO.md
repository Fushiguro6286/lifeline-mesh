# Lifeline Mesh: demo notes

Living document. Updated at the end of every phase.

## Current state (Phase 5 built; phone tests for Phases 2 to 5 still open)

- Phases 0 to 4: app, codec, mesh core + Nearby + foreground service, Live Mesh, simulation/demo modes, SOS send flow.
- Phase 5: Rescue tab. Merger (same needs, within 60 m and 15 min; positionless reports merge only from the same phone)
  and Ranker (severity, reports, distance), header numbers (active alerts, people, urgent, acknowledged), ranked alert
  cards with distance and direction from the rescuer's GPS, detail sheet with every merged report, ETA picker
  (10/20/30/60 min), optional 40-byte note and "Send help is coming" (one Ack per merged report, max 5), check-ins and
  community reports. "Load sample alerts" (simulation mode only; samples are simulated rows). Situation report and
  Ask the mesh: placeholder card until Phase 8.
- 110 unit tests pass.
- Verified on the iQOO 15 in simulation mode (9 Oct): samples + Meena's alert give 6 ranked alerts; the two flood reports
  merge into one card (5 people, 2 reports, 300 m N); Kiran's reply to Meena's alert shows that card as acknowledged;
  acknowledging rank 1 with ETA 30 min and a note sends two replies on the mesh and the card dims.

## Phase 5 phone test (needs 2 or 3 phones)

1. C: Settings → role Rescuer. A and C (and B between them) on the Mesh tab show each other.
2. A: SOS tab → Type "Water is rising, 5 people on the roof, one elderly person is ill" → Continue → Send alert.
3. C: Rescue tab shows it as rank 1 (Trapped · Medical, 5 people, Urgent, distance from C). Tap Acknowledge → 20 min →
   Send help is coming.
4. A: vibrates, "Help is coming · ETA 20 min" notification, and the SOS card turns green with ETA 20 min.

## Simulation mode (rehearsal and stage fallback)

Settings → Mesh → Simulation mode. This phone plus 3 virtual phones in a line, all on the real router:
You — Ravi (volunteer) — Meena (needs help, battery 18%) — Kiran (rescuer). Meena sends an alert 8 s after start and
then every 40 s something new happens (check-in, report, another alert); Kiran replies "help is coming" to every alert
4 s after it arrives and echoes pings. Buttons under the mesh: switch Ravi / Meena / Kiran off and on (store and
forward), "Alert from Meena". Each simulation session starts clean. Simulated packets, events and metrics are stored
apart from real ones and never shown together; every number carries a Simulated tag.

Phase 3 check in simulation: switch "Ravi off", tap "Alert from Meena" (nothing reaches you; Meena holds it), switch
"Ravi on" (it arrives through Ravi, events show "Sent N stored packets").

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

- RuleExtractor was written from scratch: the web prototype's source was not on this laptop. If you have it, the rules
  can be aligned with it. Behaviour is pinned by RuleExtractorTest.
- Speak opens the typing panel and says voice is not ready yet (speech arrives in Phase 7). The speaker button on the
  gate question arrives with ReadAloud in Phase 8.
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
