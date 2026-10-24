# OBDMaru Dash 0.1.0

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

The serial scheduler targets U19 and U03 about every 200 ms (5 Hz each), with standard speed every 1 second. Header switches and adapter/ECU response times reduce achieved rates. On-screen per-channel Hz and sample ages show actual performance; no interpolation conceals sparse input. U19/U03 expire after 1.5 seconds, speed after 3 seconds. Failed/short replies clear only the affected sample; transport faults clear all samples and reconnect. Rejected channels retry after 2 seconds. Fixed protocol: CAN 11-bit 500 kbit/s, as in the earlier real capture.

Exact real TX/RX and request outcomes are recorded through the shared transport. Mapping metadata and channel query phases are retained. Synthetic demo uses separate `dashboard_demo_sample` events and does not fabricate Bluetooth traffic. Logs remain private until shared.

## Build and verification

Build with `./gradlew :dashboard:assembleDebug`. JVM tests cover unsigned raw data, pedal endpoints, brake overflow, gear nibble masking, two-byte temperature conversion, short payloads and freshness. The Android instrumentation test exercises all three synthetic streams and completed-log export. Emulator tests do not establish actual Bluetooth throughput or physical signal calibration. Android 12+, experimental debug-signed APK.
