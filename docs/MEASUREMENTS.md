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

## On-device LLM (iQOO 15, Snapdragon SM8850, 12 GB RAM, Android 16) — 10 Oct 2026

Model: Gemma 4 E4B instruct (`gemma-4-E4B-it.litertlm`, 3.66 GB, LiteRT-LM 0.18.0). Measured by the app on the phone.
Method: fixed prompt ("Count from 1 to 40"), 32 output tokens, 5 runs per backend (Benchmarks → Run benchmark).
LiteRT-LM's `getBenchmarkInfo()` is empty outside benchmark mode, so the app times calls itself: TTFT = first
streamed chunk; decode rate = (chunks − 1) / time after the first chunk (the stream yields one token per chunk:
32 chunks for a 32-token cap). Prompt tokens = conversation token count − output.

| Backend | Time to first token (avg of 5) | Decode (avg of 5) |
|---|---|---|
| GPU | 336 ms | 22 tok/s |
| CPU | 1,392 ms | 17 tok/s |

Selector probe (first launch): Gemma 4 E4B on GPU, TTFT 814 ms, 16.6 tok/s (includes cold caches) → chosen.
Language probe: English, Hindi and Kannada all passed (2 sentences each, not the demo sentences).
Model load (initialize): E4B GPU 6.8–11.8 s; with the XNNPack weight cache warm, E4B CPU 0.35 s, E2B CPU 0.22 s.
Demo sentences (Benchmarks → Test demo sentences, prompt v2): on-device AI 4/4 correct (needs and people), rules 4/4.
NPU: not tried (no SM8850 build of the model; see Benchmarks screen).

## Mesh on real phones

Hops, round trip, battery: not measured yet (needs phones B and C).
