# Parked blinker discovery — Dash 0.1.14

This checks the older Hyundai **raw CAN 0x541 / CGW1** candidate using the existing Bluetooth ELM dongle. It is separate from Watcher's U-blocks and Dash's unsuccessful `770/22BC08` query. No ESP32 or wiring change is needed for this check.

1. Install [Dash 0.1.14](https://github.com/EukkMaru/OBDMaru/releases/download/dashboard-v0.1.14/OBDMaru-Dash-v0.1.14.apk) over the existing Dash. Stop other OBD apps.
2. Park in P, apply the parking brake, and turn the ignition ON. The engine may stay OFF. Start with indicators and hazards OFF.
3. Choose **Connect → select your paired adapter → Parked blinker test → Start blinker test**. Wait for the stage display and Next button.
4. Hold the initial OFF stage for 5–10 seconds. Tap **Next** immediately before each change, then hold each state for 5–10 seconds: **LEFT → OFF → RIGHT → OFF → HAZARDS → OFF**. Use the normal latched left/right stalk positions rather than only a one-touch tap; center it explicitly for OFF.
5. After the final OFF stage, tap **Finish test**. **Stop** also ends early and saves partial evidence. The monitor ends automatically after two minutes. Return indicators and hazards to OFF even if the test fails or ends early.
6. Wait until stopped, then **Share log** and send the JSONL privately in the project chat. If an adapter setting is rejected or no frames arrive, share that log too. If cleanup cannot confirm the command boundary/reset, unplug and reconnect the dongle before ordinary polling.

The screen shows total matching frames, average received Hz, last-frame age, raw two-bit left/right lamp fields, one-bit stalk fields and a raw two-bit hazard field. Values 0–3 are retained without assuming that every nonzero value means ON. No synthesized blinking or automatic interpretation is added to normal Dash arrows. During this test the usual dashboard polling and gauges are paused. There are on-screen instructions, no spoken cues.

## What the capture establishes

The useful result is a repeatable change in specific raw fields during left, right and hazard stages, including distinction between the stalk being held and actual lamp flashing. User stage markers record when Next was pressed; they do not prove the car's controls changed at that instant. Ignition, P, parking brake and continuous stationary state are user-controlled, not inferred from unknown bits.

No frames means this candidate was not observed through this adapter on the OBD-accessible bus in that session. It does not establish that the car has no blinker messages, that they live on pin 9, or that an ESP32 on the same wires will necessarily see them. A clone may also acknowledge commands without implementing them correctly. Hardware support remains unverified until the first real capture.

## Protocol and logging

Required checked setup: `ATZ`, `ATE0`, `ATL0`, `ATH1`, `ATS0`, `ATSP6`, `ATCAF0`, `ATD1`, `ATCSM1`, `ATCRA541`; then `ATMA`. Protocol is 11-bit CAN at 500 kbit/s, matching the existing diagnostic connection. Silent monitoring and exact-ID filtering are requested before entering monitor mode. No broad-bus fallback, active-monitor fallback, actuator command or diagnostic CAN request is issued during monitoring. Setup rejection stops the test.

`ATMA` is a serial stream, not a normal prompt-terminated request. A dedicated reader path handles fragmented lines, eight-byte raw CAN frames and a two-minute limit. Wrong IDs, unexpected DLC, malformed lines, adapter errors, an early prompt, oversized lines/output or receive overflow stop the test. Limits: 128 characters per line, 2 MiB processed monitor output, 20,000 frames, and a shared 256-chunk receive queue. These are protective limits, not claimed sampling rates.

A carriage return aborts monitoring; cleanup requires a prompt followed by a quiet interval within two seconds. On successful completion the app resets the adapter with `ATZ`, confirms its identity, closes Bluetooth and releases the cooperative OBDMaru guard. Uncertain boundaries are closed without issuing further setup/diagnostic commands. Other third-party apps must still be stopped manually.

The shared raw `rx` records retain exact bytes/base64. New events include `monitor_tx`, `monitor_stop_boundary`, `blinker_test_start`, `blinker_stage`, `blinker_frame`, `blinker_test_end` and `blinker_adapter_reset`. Raw frames retain their eight-byte hex data and candidate fields with the current stage. Timestamps describe phone stream receipt/processing, not electrical CAN timestamps. Failed sessions retain raw data and errors; logs remain private until shared.

Candidate offsets and provenance: [diagnostic notes](diagnostics.md#blinker-investigation--older-hyundai-can-candidate-2026-10-27), pinned commaai/opendbc `1bce882c6330f8cc591f368f415ae5fa241ae225`. Adapter behavior: [ELM327 manufacturer datasheet](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf), receive filtering, DLC display, silent monitoring and monitor-all commands. Neither source validates this exact pre-facelift Tucson's broadcast visibility.
