# Lifeline Mesh: demo notes

Living document. Updated at the end of every phase.

## Current state (Phase 2, waiting for real-phone tests)

- Phase 0: app, theme, components, onboarding, navigation. Phase 1: packet codec (31 tests).
- Phase 2: mesh core. Router, SeenCache, Outbox, PeerSync, RateLimiter, HelloBeacon, VirtualMesh: 30 simulator/unit tests pass.
  NearbyTransport (P2P_CLUSTER), MeshService (connectedDevice foreground service, partial wake lock), Room persistence,
  health checks, and a debug panel on the Mesh tab.
- Verified on the iQOO 15 alone (9 Oct): service runs as a foreground service, advertises and discovers, a test alert with
  no neighbors shows "Waiting for a nearby phone", queued alerts survive force-stop and are restored, alerts created while
  the mesh is stopped are saved and picked up on restart.
- NOT yet verified: anything with two or more phones (A to C via B, B off/on, B screen off).

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

Words to check first, because they appear on stage: "SOS के लिए दबाकर रखें" / "SOS ಗಾಗಿ ಒತ್ತಿ ಹಿಡಿಯಿರಿ" (Hold for SOS), "मदद आ रही है" / "ಸಹಾಯ ಬರುತ್ತಿದೆ" (Help is coming), "पास के फ़ोन का इंतज़ार" / "ಹತ್ತಿರದ ಫೋನ್‌ಗಾಗಿ ಕಾಯಲಾಗುತ್ತಿದೆ" (Waiting for a nearby phone).

Settings and developer screens are English only on purpose (DESIGN.md section 9).

## Known issues

- iQOO 15 (measured 9 Oct): without the battery-optimization exemption the OS freezes the app ~10 s after the screen turns
  off, even with the foreground service running (`isFrozen=true`, wake lock disabled). With the exemption it stays a
  foreground service and keeps its wake lock. The Mesh tab shows a warning with a fix button when the exemption is missing.
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
