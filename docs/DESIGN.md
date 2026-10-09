# Lifeline Mesh: design guide

The app is used by frightened people in the dark, and by tired volunteers in the sun. Every design choice serves that: calm, high contrast, big targets, few words, and one striking element (the live mesh) that makes the invisible network visible.

## 1. Design direction: "Night operations"

- **Dark-first.** Disasters often happen at night and power is scarce. A dark theme is easier on the eyes in the dark and saves battery on OLED screens. A **Daylight** theme (light, very high contrast) is available for outdoor use and switches with the system setting or a toggle in Settings.
- **Signal, not decoration.** Color carries meaning only: amber = a packet or alert moving, teal = healthy links and safe states, red = SOS and urgent, green = acknowledged or safe. Everything else is ink and gray.
- **One motif: signal rings.** Concentric rings that pulse outward from a point. Used on the SOS button while holding, on phones in the live mesh when they send or receive, and on the discovery scan. Nowhere else.
- **Numbers are heroes.** Hops, bytes, people and ETAs are shown large in the condensed heading font, with small labels.

Avoid: gradients, glassmorphism, neon glow, emoji, stock illustrations, every-card-the-same layouts.

## 2. Color tokens (`ui/theme/Color.kt`)

| Token | Night (default) | Daylight | Use |
|---|---|---|---|
| `bg` | `#0B1620` | `#F4F7F8` | App background |
| `surface` | `#132330` | `#FFFFFF` | Cards, sheets |
| `surfaceRaised` | `#1A2E3D` | `#EAF0F2` | Selected rows, chips |
| `outline` | `#27404F` | `#C9D6DA` | Hairlines, inactive links |
| `ink` | `#E8EEF2` | `#0E1E28` | Primary text |
| `inkMuted` | `#8FA3B0` | `#4E626D` | Secondary text |
| `signal` (amber) | `#F2A900` | `#9A6A00` | Packets in flight, alert accents |
| `link` (teal) | `#2BB3A8` | `#0B6E75` | Connected links, primary buttons |
| `sos` (red) | `#E5483B` | `#C8251D` | SOS button, urgent |
| `safe` (green) | `#5BC27A` | `#2E7D32` | Acknowledged, "I'm safe" |
| `warn` | `#F0C14B` | `#8A5F00` | Warnings, queued state |

Text on `sos` and `link` fills is white in Daylight and `#06131A` in Night. Check every pairing for at least 4.5:1 contrast (7:1 for body text in Daylight).

Severity colors: 3 Urgent = `sos`, 2 High = `signal`, 1 Moderate = `link`, 0 Info = `inkMuted`.

## 3. Typography (`ui/theme/Type.kt`)

Bundle fonts in `res/font` (no downloadable fonts, the app must work offline). Both are free under the SIL Open Font License; get them from Google Fonts (see `SETUP.md`).

- **Archivo** (variable, use condensed width ~75–85% via a static condensed cut if variable width isn't supported) for headings, numbers, buttons.
- **Atkinson Hyperlegible** for body text, designed for legibility for low-vision readers. It supports Latin only, so Hindi and Kannada text falls back to the system Noto fonts automatically. Do not force a Latin font on Indic strings.

| Style | Font | Size / line height | Weight |
|---|---|---|---|
| Display (big numbers) | Archivo condensed | 44 / 48 sp | 800 |
| Headline | Archivo condensed | 28 / 32 sp | 700 |
| Title | Archivo | 20 / 26 sp | 700 |
| Body | Atkinson Hyperlegible | 17 / 25 sp | 400 |
| Body strong | Atkinson Hyperlegible | 17 / 25 sp | 700 |
| Label | Atkinson Hyperlegible | 14 / 18 sp | 700 |
| Caption | Atkinson Hyperlegible | 13 / 18 sp | 400 |

Body is 17 sp, larger than usual, because readers are stressed. Respect the user's font scale; layouts must not break at 1.3x.

## 4. Shape, spacing, elevation

- Corners: 6 dp for chips and small controls, 12 dp for cards, 20 dp for bottom sheets, full circle for the SOS button and mesh nodes.
- Spacing scale: 4, 8, 12, 16, 24, 32 dp. Screen side padding 16 dp.
- Elevation: flat. Separate layers with `surface` vs `bg` tone, not shadows.
- Touch targets: at least 56 dp for emergency actions, 48 dp for everything else.

## 5. Motion (`ui/theme/Motion.kt`)

- Durations: fast 120 ms, base 220 ms, slow 400 ms, packet hop 700 ms.
- Easing: emphasized decelerate for things arriving, standard for everything else.
- Signal ring: 3 rings, each scales 1.0 → 2.2 and fades 0.5 → 0 over 1,200 ms, staggered 400 ms.
- Respect "Remove animations" (reduced motion): replace movement with instant state changes and a brief color flash.
- Never animate text that the user needs to read.

## 6. Shared components (`ui/components/`)

Build these first with Compose previews in both themes.

- **SignalRings(color, active)**: the motif.
- **HoldToSendButton**: 200 dp circle, `sos` fill, label "Hold for SOS". While held: a progress ring fills over 3 s, signal rings pulse, haptic tick each second, label counts "3, 2, 1". Release early: cancels with "Cancelled". Complete: strong haptic, label "Sent", then shows Delivery Status. Accessible: TalkBack action "Send SOS" with a confirmation dialog instead of holding.
- **NeedChip(type, selected)**: icon + label (trapped, medical, water or food, fire, road blocked). Selected = `surfaceRaised` with `link` outline.
- **SeverityBadge(level)**: small pill with severity color and word.
- **StatTile(value, label, simulated)**: big Display number, small label; shows a small "Simulated" tag when needed; shows "Not measured" in `inkMuted` when value is null.
- **PacketBytesStrip(bytes, fields)**: the colored hex strip from the web prototype, each field a different tint, with a legend. Tapping a field highlights it and shows its name.
- **StatusTimeline(steps)**: vertical steps with dots: Created → Waiting for a nearby phone / Passed to N phones → Help is coming. Current step pulses once.
- **BigIconButton**: 64 dp icon + label for one-handed actions.
- **EmptyState(title, body, action)**: always an action, never "Nothing here".
- **SimulatedBanner**: red strip "Simulated mesh: numbers are not real" when simulation mode is on.

Icons: Material Symbols (outlined, filled when selected). Suggested: `sos`, `mic`, `cell_tower`, `hub`, `person_pin_circle`, `local_hospital`, `water_drop`, `local_fire_department`, `block`, `verified_user`, `map`, `memory` (AI), `speed`, `battery_5_bar`.

## 7. Navigation

Bottom bar with 4 destinations, plus Settings from the top bar:

1. **SOS** (send flow)
2. **Mesh** (live mesh)
3. **Rescue** (rescuer list, report, ask)
4. **Map**

Role chosen in onboarding sets the start tab: "I need help" → SOS; "I'm a volunteer or rescuer" → Rescue. Both roles can use every tab.

A thin top status strip on every screen: mesh state ("3 phones nearby" in `link`, or "Searching…" with a scanning ring), AI state dot ("AI ready", "AI loading", "Rules only"), and the Simulated banner if active.

## 8. Screens

### 8.1 Onboarding (3 short pages)
1. "Help that travels phone to phone." One sentence on what the app does, a small illustration made of three node circles and links (drawn in Compose, not an image).
2. Choose name (optional), language (English, हिन्दी, ಕನ್ನಡ shown in their own scripts), role.
3. Permissions: a checklist, each with one plain sentence ("Bluetooth: to reach phones near you without internet"), a "Grant" button, and a green tick when done. Final row: "Allow background activity" with a button to the battery settings and a sentence about why.

### 8.2 SOS (send)
Top to bottom:
- Greeting line: "No network? You can still be heard."
- **HoldToSendButton** centered, the most prominent thing on the screen.
- Below it, two BigIconButtons side by side: **Speak** (mic) and **Type**.
- A compact **I'm safe** card at the bottom.

Speak: full-width listening panel with a live transcript and an animated level meter; "Done" stops. Then the **Review sheet** opens.

**Review sheet** (bottom sheet, 90% height):
- Transcript in a quote block, with a source tag: "Understood by: On-device AI" or "Rules".
- Need chips (multi-select), people stepper (big − and + buttons, number in Display style), severity segmented control, location row ("Exact, 12 m" or "Approximate, last known").
- Quality gate: if something is missing, a `warn` card with the clarifying question in the user's language and a speaker button to hear it.
- PacketBytesStrip with "91 bytes" in Display style.
- Primary button "Send alert" (`sos`), disabled-looking but still tappable; tapping when incomplete scrolls to the question.

After sending: **Delivery Status** card replaces the button area with StatusTimeline. When an ack arrives: the card turns `safe`, big text "Help is coming", "ETA 20 min", vibrate, and read aloud if enabled.

### 8.3 Mesh (the showpiece)
Full-bleed canvas on `bg`, the star of the demo.

**Layout:**
- Me: center node, 56 dp, `link` ring, my name below.
- Direct neighbors: arranged on an inner orbit (radius ~30% of the shorter screen side), evenly spaced, 44 dp nodes.
- Two-hop phones (known from Hello packets): outer orbit, 36 dp, slightly muted.
- Links: straight lines; connected = `link` 2 dp; connecting = dashed `warn`; lost = dashed `outline` for 10 s, then removed.
- Each node shows: initial or short name, a tiny role icon, and a battery arc around it (green above 50%, amber 20–50%, red below 20%).
- Gentle idle motion: nodes drift 2–3 dp in a slow loop so the network feels alive (disabled in reduced motion).

**Packets:**
- When a packet is sent or received on a link, a dot travels along that link in 700 ms. Color by type: alert amber (urgent alerts red), ack green, check-in teal, ping white, hello not animated.
- On arrival the receiving node emits SignalRings once in the packet's color.
- A small floating label rises from the node for 1.5 s: "Alert · hop 2" or "Help is coming".
- Store-and-forward: queued packets show as a small amber badge with a count on my node ("2 waiting").

**Discovery:** when no neighbors, a slow radar sweep from my node and the text "Searching for phones nearby… Keep Bluetooth and location on."

**Bottom area (draggable sheet, collapsed by default):**
- Collapsed: a Scoreboard row of 3 StatTiles: Hops (last alert), Round trip, Packet bytes.
- Expanded: Event timeline (newest first) with icons and plain sentences: "Received alert 3fa1c2 from B · hop 2", "Dropped duplicate 3fa1c2", "Phone B disconnected", "Sent 2 waiting alerts to B". Filter chips: All, Alerts, Network.

**Tap a node:** small sheet with name, role, battery, last heard, links, and packets exchanged.

**Demo mode** (Settings): bigger nodes and labels, screen kept awake, event labels larger, for showing on a projector or to judges.

Implement with Compose `Canvas` + `Animatable`s keyed by packet ID. Keep it at 60 fps: precompute node positions when topology changes, not every frame; cap active packet animations at 20.

### 8.4 Rescue
- Header row of StatTiles: Active alerts, People, Urgent, Acknowledged.
- **Situation report card** at top: three lines, "Written on device" tag, refresh button, time generated. Falls back to the computed version with no tag.
- **Alert list**: AlertCard per merged alert, ranked. AlertCard = rank number in Display style, need icons, people, distance and direction arrow, SeverityBadge, "3 reports merged", time ago, note in quotes (with a translate icon), and an "Acknowledge" button. Acknowledged cards dim slightly and show "ETA 20 min sent".
- Tap a card → **Alert detail sheet**: all fields, all merged reports, mini map, translate, ETA picker (10, 20, 30, 60 min) and "Send help is coming".
- Check-ins section: "Asha is safe · 400 m · 2 min ago".
- Community reports section.
- Floating "Ask" button → **Ask the mesh** screen: chat layout, quick-question chips, answers cite alerts as tappable `#n` chips that open the alert, tag "Answered from N stored alerts". If fallback was used, tag "Rules".

### 8.5 Map
- MapLibre with the offline venue map and a dark map style in Night theme, light in Daylight.
- Alert markers: circles sized by people, colored by severity, rank number inside. Help points: square markers with an icon. My position: arrow.
- Tap marker → same Alert detail sheet.
- Attribution "© OpenStreetMap contributors" always visible.
- If the map file is missing: the Canvas fallback map (rings around "You", markers by distance and bearing), with a small note "Offline map file not found".

### 8.6 Settings and Benchmarks
- Sections: You (name, role, language), Mesh (service on/off, simulation mode, demo mode, battery settings shortcut), AI (model status, backend CPU/NPU, warm-up), Sync (server URL, last upload), Privacy ("Works without internet. Speech and messages are processed on this phone."), Export measurements.
- **Benchmarks screen**: a table of LLM calls with backend, time to first token, tokens/s, total; a simple bar comparison CPU vs NPU when both exist; packet sizes; round trips; battery drain. "Not measured" where empty. A "Run benchmark" button that runs a fixed prompt 5 times on the selected backend.

## 9. Writing in the UI

- Short, calm, plain. Verbs on buttons: "Send alert", "Hold for SOS", "Acknowledge", "Send help is coming".
- Tell people what is happening and what to do: "Waiting for a nearby phone. Stay where you are; it will send automatically."
- Errors say what happened and how to fix it: "Bluetooth is off. Turn it on so nearby phones can reach you." with a button.
- No exclamation marks, no jargon on the SOS side. Network terms (TTL, hop) appear only on the Mesh and Benchmarks screens.
- All user-facing strings in `strings.xml`, with Hindi and Kannada translations for the SOS flow, onboarding and replies. Mark them for native-speaker review in `docs/DEMO.md`.

## 10. Accessibility and stress-proofing

- TalkBack labels on every control; the SOS hold has an accessible alternative.
- Read-aloud toggle: reads questions, replies and situation reports.
- Works one-handed: main actions in the lower half of the screen.
- No information by color alone: severity has a word, links have dash patterns, packets have labels.
- Survive rotation and process death without losing state.

## 11. Definition of "looks done"

Before calling a screen done, compare it with this file in both themes and at font scale 1.3: no clipped text, no overlapping elements, consistent spacing, contrast checked, motion respects reduced-motion, empty and error states designed.
