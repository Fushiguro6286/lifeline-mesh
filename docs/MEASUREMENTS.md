# Lifeline Mesh: measurements

What was measured, how, and on what. Nothing here is estimated. Simulated values are never mixed in.

## Packet sizes (encoded bytes)

Source: `PacketCodecTest.printSampleSizes` (unit test, 9 Oct 2026). The codec is deterministic, so these are the exact sizes sent over Nearby Connections.

| Packet | Bytes |
|---|---|
| Alert, no note | 31 |
| Alert, Hinglish demo sentence (64 bytes typed, trimmed to 60) | 91 |
| Alert, Hindi note (146 bytes typed, trimmed to 58 on a whole character) | 89 |
| Check-in, name "Asha" | 32 |
| Ack, ETA 20, no note | 28 |
| Ack, note "Boat coming, stay on roof" | 53 |
| Ping | 28 |
| Hello, 3 neighbors, name "Asha" | 38 |
| Summary, 100 ids (largest possible packet) | 820 |

Every packet is under the 1 KB payload limit.

## On-device numbers

Hops, round trip, LLM timings, battery: not measured yet.
