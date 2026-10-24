# OBDMaru Dash 0.1.1

A primitive portrait F1-style telemetry display, installed separately as `dev.obdmaru.dashboard` alongside Magic and Watcher. No cruise interpretation is included.

## Install and use

Install the Dashboard APK, grant Nearby devices and notification permissions, and try **Demo** first. Demo is synthetic and explicitly labeled. For the car, stop other OBD apps, pair the ELM327 in Android Bluetooth settings while parked, then choose **Connect → Refresh → adapter → Start**. Connection controls collapse to leave room for the dashboard. Stop before sharing a completed raw JSONL log.

## Signals

| Display | Mapping | Status |
| --- | --- | --- |
| Speed | Standard `010D`, data byte 0 in km/h | Standard definition; not calibrated to dashboard or GPS. No guessed U19 speed byte. |
| Throttle | U19 `7E1/2201A4` b08 /254 ×100 | Owner-observed pedal candidate. Also shows raw b07 and its separately calibrated `(b07−32)/213 ×100` percentage for comparison. |
| Brake | U03 `7D1/22C101` b32 /140 ×100 | Relative input, not physical depth or pressure. Numeric percentages can exceed 100%; bar fills at 100%. Raw b32 and b33 remain separate. |
| Gear | U19 b23 low nibble, 1–7 forward /14 reverse; selector b21 supplies P/R/N | Candidate mapping supported by owner observations and community gear definition. |
| Mode | U19 b22: P=1, R=3, N=5, D=7, M=10 | Owner-observed codes. |
| Secondary gear | U19 b24 low nibble | Explicitly uncertain; database calls it next gear. Not labeled as other-clutch preselection. |
| Brake switch | U19 b26 bit 0 | On/off candidate, not pressure. |
| Clutch 1/2 | U19 b29–b30 /b31–b32, unsigned big-endian ×2−40 °C | Provisional community mapping, not independently validated on this car. Outside the source's −40 to 300 °C declared range, display unavailable. |

All b-indices are zero-based after removing response service/identifier. Clutch definitions are from [OBDb Tucson](https://github.com/OBDb/Hyundai-Tucson/blob/b1306dc2c54e32ba4344fcbb5fd098ef66db50e7/signalsets/v3/default.json); see [diagnostic provenance](diagnostics.md) and [third-party notices](../THIRD_PARTY.md). No manufacturer temperature warning thresholds or overheating alarms are asserted.

## Timing and logs

The serial scheduler targets U19 and U03 every 100 ms (10 Hz each), with standard speed every 333 ms (approximately 3 Hz). U19 includes throttle, gear **and** clutch temperatures in one ECU reply: the clutch display is updated from that stream only once per second, with no extra temperature request. Header switches and adapter/ECU response times reduce achieved rates. These are targets, not guaranteed bandwidth. On-screen per-channel Hz and sample ages show actual performance; no catch-up bursts or interpolation conceal sparse input. U19/U03/speed expire after 1.5 seconds; the 1 Hz clutch sample expires after 2.5 seconds. Fixed protocol: CAN 11-bit 500 kbit/s, as in the earlier real capture.

Failed, rejected or short replies remove only the affected sample (a U19 failure also clears its clutch display) and back off that channel for 1–5 seconds while others continue. A query/header I/O failure first attempts an adapter resync over the existing Bluetooth socket: send a carriage return to abort, drain through an ELM prompt plus a quiet boundary, then require a fresh recognizable `ATI` identity reply. A late diagnostic prompt alone is not sufficient. If resync fails, close the socket and retry Bluetooth after two seconds, alternating secure/insecure SPP and reapplying settings. Successful reconnections avoid repeated `ATZ` reboots; optional `ATPC` rejection is tolerated. Existing samples retain their original timestamps and age out, rather than being relabeled as fresh. The status and raw logs identify the failed stage and recovery path.

Watcher held one selected header; Dash must change between transmission, ABS and standard OBD requests. Dash 0.1.0 also treated every request timeout as a Bluetooth disconnect, clearing every sample and resetting the adapter on reconnect. The changes address that fragile recovery behavior; without the failing session log, they do not establish why this particular adapter stopped responding after about three seconds.

Exact real TX/RX and request outcomes are recorded through the shared transport. Mapping metadata and channel query phases are retained. Synthetic demo uses separate `dashboard_demo_sample` events and does not fabricate Bluetooth traffic. Logs remain private until shared.

## Build and verification

Build with `./gradlew :dashboard:assembleDebug`. JVM tests cover unsigned raw data, pedal endpoints, brake overflow, gear nibble masking, two-byte temperature conversion, short payloads and freshness. The Android instrumentation test exercises all three synthetic streams and completed-log export. Emulator tests do not establish actual Bluetooth throughput or physical signal calibration. Android 12+, experimental debug-signed APK.

Version 0.1.1: nine JVM tests cover decoding, target rates, the independent 1 Hz clutch display, rejected reads, timeout resync, broken-socket reconnect, header failure, and setup retry. Existing recorder and Watcher JVM tests are also run. This update is verified with a simulated adapter; no real-vehicle disconnect/reconnect reproduction has been performed. The Android smoke test is updated, but was not rerun on an emulator for this update.
