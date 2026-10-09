# Lifeline Mesh: demo notes

Living document. Updated at the end of every phase.

## Current state (Phase 1)

What exists: project, theme, shared components, onboarding with permissions checklist, bottom navigation, Settings, component gallery.
Phase 1: binary packet codec for all 7 types (`packet/Packet.kt`, `packet/PacketCodec.kt`), 31 unit tests passing.
What does not exist yet: mesh, sending, rescue, map, AI. The four tabs say which phase brings them.
The status strip says "Mesh not running" and "Rules only" because neither the mesh service nor a model exists yet.

Phone A (iQOO 15, I2501, Android 16, SM8850): installed, app launches, onboarding reached the main screen, component gallery renders (screenshots checked 9 Oct). Phones B and C: not yet installed.

## Demo steps

Not ready. The 3-minute script in CLAUDE.md section 14 needs phases 2 to 7.

## Needs native-speaker review

Machine-drafted translations. Please have a native speaker check them before the demo:

- `app/src/main/res/values-hi/strings.xml` (Hindi): SOS flow, need and severity words, delivery status, onboarding, permissions checklist.
- `app/src/main/res/values-kn/strings.xml` (Kannada): same set.

Words to check first, because they appear on stage: "SOS के लिए दबाकर रखें" / "SOS ಗಾಗಿ ಒತ್ತಿ ಹಿಡಿಯಿರಿ" (Hold for SOS), "मदद आ रही है" / "ಸಹಾಯ ಬರುತ್ತಿದೆ" (Help is coming), "पास के फ़ोन का इंतज़ार" / "ಹತ್ತಿರದ ಫೋನ್‌ಗಾಗಿ ಕಾಯಲಾಗುತ್ತಿದೆ" (Waiting for a nearby phone).

Settings and developer screens are English only on purpose (DESIGN.md section 9).

## Known issues

- Phase 0 "done when" is only partly checked: phones B and C are not installed yet (you chose to move on, 9 Oct). Phone B (vivo T4x 5G) did not expose an ADB interface over USB; try PTP mode, another cable, or Wireless debugging.
- The Hinglish demo sentence is 64 bytes, over the 60-byte note limit, so the sent note ends at "...ek bujurg beemar". Needs, people and severity travel as structured fields, so nothing important is lost; the review sheet (Phase 4) must show the trimmed note so the sender sees exactly what goes out.

- On vivo/iQOO, "Allow background activity" in the app's battery settings is a vendor switch the app cannot read. The checklist tick only reflects Android's battery-optimization exemption; the row tells the user to also open the app's battery settings.

## Needs real phones

- Install on phones B and C, complete onboarding, grant every permission (Phase 0 "done when"). iQOO 15 done.

## Build notes

- Use Android Studio's bundled JDK: `JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"`. The `Android Studio/jbr` folder on this laptop is incomplete (no `jvm.cfg`).
