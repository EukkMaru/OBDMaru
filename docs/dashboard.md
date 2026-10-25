# OBDMaru Dash 0.1.10

A portrait circular telemetry display, installed separately as `dev.obdmaru.dashboard` alongside Magic and Watcher. No cruise interpretation is included.

## Install and use

Install the Dashboard APK, grant Nearby devices and notification permissions, and try **Demo** first. Demo is synthetic and explicitly labeled. For the car, stop other OBD apps, pair the ELM327 in Android Bluetooth settings while parked, then choose **Connect → Refresh → adapter → Start**. Connection controls collapse to leave room for the dashboard. Stop before sharing a completed raw JSONL log.

## Signals

| Display | Mapping | Status |
| --- | --- | --- |
| Coolant | Standard `0105`: unsigned data byte A −40 °C | Standardized definition; 1 Hz target, 2.5-second freshness limit. Missing/stale replies show —. |
| Engine RPM | Standard `010C`: `(256*A+B)/4` RPM | Canonical standardized engine-speed signal; unsigned bytes, unavailable on missing/short replies. |
| Turn indicators | Candidate `770/22BC08` b04: left mask `0x02`, right mask `0x04` | Community definition; not yet validated on this vehicle. Sampled state only, no synthetic blink animation. |
| Speed | Standard `010D`, data byte 0 in km/h | Standard definition; not calibrated to dashboard or GPS. No guessed U19 speed byte. |
| Throttle | U19 `7E1/2201A4` b08 /254 ×100 | Owner-observed pedal candidate, shown as an arc. Raw b07/b08 remain in diagnostics; no numeric percentage readout. |
| Brake | U03 `7D1/22C101` sqrt(b32 /150) ×100 | Nonlinear relative arc, not physical depth or pressure. Raw 150 fills the arc; larger raw readings remain in diagnostics/logs. No numeric percentage readout. Raw b32 and b33 remain separate. |
| Gear | U19 b23 low nibble, 1–7 forward /14 reverse; selector b21 supplies P/R/N | Candidate mapping supported by owner observations and community gear definition. |
| Mode | U19 b22: P=1, R=3, N=5, D=7, M=10 | Owner-observed codes. |
| Secondary gear | U19 b24 low nibble | Explicitly uncertain; database calls it next gear. Not labeled as other-clutch preselection. |
| Brake switch | U19 b26 bit 0 | On/off candidate, not pressure. |
| Clutch 1/2 | U19 b29–b30 /b31–b32, unsigned big-endian ×2−40 °C | Provisional community mapping, not independently validated on this car. Outside the source's −40 to 300 °C declared range, display unavailable. |

All b-indices are zero-based after removing response service/identifier. Clutch definitions are from [OBDb Tucson](https://github.com/OBDb/Hyundai-Tucson/blob/b1306dc2c54e32ba4344fcbb5fd098ef66db50e7/signalsets/v3/default.json); see [diagnostic provenance](diagnostics.md) and [third-party notices](../THIRD_PARTY.md). No manufacturer temperature warning thresholds or overheating alarms are asserted.

## Timing and logs

The serial scheduler targets U19/U03 at 10 Hz each, standard speed at 3 Hz, RPM at 5 Hz, coolant at 1 Hz and optional indicators at up to 2 Hz. U19 already contains throttle, gear and the provisional clutch temperatures; clutch display samples that stream at 1 Hz without another request. Header switches and adapter/ECU response times reduce achieved rates. On-screen per-channel Hz and sample ages show actual performance; no catch-up bursts conceal sparse input. Motion and indicator readings expire after 1.5 seconds; coolant and clutch after 2.5 seconds. Fixed protocol: CAN 11-bit 500 kbit/s. Speed, RPM and coolant initially use engine address `7E0`; failure of one switches all three to functional `7DF`. Due speed/RPM/coolant requests can share the same header in a bounded group.

Failed, rejected or short replies remove only the affected sample (a U19 failure also clears its clutch display) and back off that channel for 1–5 seconds while others continue. A query/header I/O failure first attempts an adapter resync over the existing Bluetooth socket: send a carriage return to abort, drain through an ELM prompt plus a quiet boundary, then require a fresh recognizable `ATI` identity reply. A late diagnostic prompt alone is not sufficient. If resync fails, close the socket and retry Bluetooth after two seconds, alternating secure/insecure SPP and reapplying settings. Successful reconnections avoid repeated `ATZ` reboots; optional `ATPC` rejection is tolerated. Existing samples retain their original timestamps and age out, rather than being relabeled as fresh. The status and raw logs identify the failed stage and recovery path.

The owner subsequently confirmed that Watcher was still polling in the background during the disconnecting Dash run; stopping Watcher resolved disconnections. That is the established explanation for that run. The recovery changes remain, but must not be cited as proof of an adapter fault.

Watcher repeats one full block at 5/10 requests per second without changing headers. Before RPM/indicator additions, Dash at its requested targets sent 10 full U19 blocks plus 10 full U03 blocks and 3 speed requests per second, frequently with a separate `ATSH` command before each. Decoding fewer visible fields does not reduce those full ECU replies. At the captured lengths this is approximately 799 diagnostic payload bytes/s versus 370 bytes/s for Watcher U19 at 10 Hz, before CAN/ASCII/header-switch overhead. The selected targets can therefore exceed available serial throughput even though a single Watcher block reaches 10 Hz.

Exact real TX/RX and request outcomes are recorded through the shared transport. Mapping metadata and channel query phases are retained. Synthetic demo uses separate `dashboard_demo_sample` events and does not fabricate Bluetooth traffic. Logs remain private until shared.

## Build and verification

Build with `./gradlew :dashboard:assembleDebug`. JVM tests cover unsigned raw data, pedal endpoints, brake overflow, gear nibble masking, two-byte temperature conversion, short payloads and freshness. The Android instrumentation test exercises all current synthetic channels and completed-log export. Emulator tests do not establish actual Bluetooth throughput or physical signal calibration. Android 12+, experimental debug-signed APK.

Version 0.1.1: nine JVM tests cover decoding, target rates, the independent 1 Hz clutch display, rejected reads, timeout resync, broken-socket reconnect, header failure, and setup retry. Existing recorder and Watcher JVM tests are also run. This update is verified with a simulated adapter; no real-vehicle disconnect/reconnect reproduction has been performed. The Android smoke test is updated, but was not rerun on an emulator for this update.

## Version 0.1.2 throughput changes

The scheduler reserves due speed reads under saturation, with no consecutive speed reads while pedal channels are also due. This prioritizes speed freshness without claiming that all three requested rates can fit the adapter.

To remove ELM's final wait for extra replies, a channel learns its complete single-ECU ISO-TP response shape from three successful unrestricted reads. Only then does it append the ELM response-count digit to the serial command (for the original captured shapes: U19 six frames, U03 seven, speed one). This suffix configures the adapter; it is not a changed vehicle DID. Full ISO-TP reassembly remains mandatory. Multiple-ECU replies and incomplete payloads never train a count. Every 30 successful replies the app makes an unrestricted check. Rejected/truncated hinted reads disable the hint for that connection and retry without it; timed-out reads still use the resync path. Shapes are relearned after reconnecting. Reference: [ELM327 manufacturer datasheet, pages 32 and 52](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf).

Every completed read emits `dashboard_poll_timing` with logical `command`, exact `wire_command`, `header_ms`, `query_ms`, `total_ms` and scheduler `late_ms`. Raw TX records contain the actual optional count suffix. These separate address-switch overhead from query/reply waits. This is evidence for the next car-side run, not a diagnosis of the reported 0.7 Hz measurement without its log.

Seventeen Dashboard JVM tests now cover the above behavior, including slow-link speed scheduling, frame-count learning, periodic unrestricted checks, multiple-ECU/incomplete-reply rejection, clone suffix rejection, and functional speed fallback after rejection/timeout. APK/test-APK compilation and lint pass. No emulator or vehicle run was performed for 0.1.2; actual throughput remains to be measured.

## Exclusive connection guard

This version shares a process-lifetime OS socket-name lock with Magic 2.0.2, Watcher 0.1.1 and Dash 0.1.10. Update every installed OBDMaru peer; older builds cause a clear update-required message before Bluetooth connects. While one real session owns the lock, another app names that owner and stops its start attempt. The lock remains held during reconnects and is released after closing Bluetooth, or automatically if the process dies. Demo sessions do not connect and bypass the lock. Arbitrary third-party apps are not controllable or reliably detectable; stop them manually.

## Version 0.1.4 brake response

Brake display uses `100 * sqrt(raw_b32 / 150.0)`: raw 0→0%, 6→20%, 24→40%, 54→60%, 96→80%, 150→100%. The slope is steep near release and progressively flatter as input rises. Raw b32/b33 remain separate and unchanged. Values above raw150 remain above100 numerically; only the bar fill clips at 100. This is a user-selected display curve, not a physical percentage, pressure conversion, deceleration or grip estimate. No automatic recalibration changes the reference between sessions. Session metadata records the curve, exponent and raw reference.

Dashboard JVM tests cover the low/high response relationship, exact calibration landmarks, monotonicity, zero, overflow and untouched raw bytes. No emulator or vehicle check performed for this display-only update.

## Version 0.1.5 engine RPM and turn indicators

Standard engine RPM (`010C`) targets 5 Hz, sharing physical engine address `7E0` with speed. When both are due, RPM can immediately follow speed without an extra header switch. Failure of either physical engine request switches both to functional `7DF` for subsequent reads. RPM uses the [ELM327 documented standard conversion](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf), unsigned16-bit big-endian divided by4. Short or stale readings are unavailable, not zero.

The [OBDb Tucson indicator candidate](https://github.com/OBDb/Hyundai-Tucson/blob/b1306dc2c54e32ba4344fcbb5fd098ef66db50e7/signalsets/v3/default.json#L59-L63) requests `770/22BC08` with expected response `778`. Source bit offsets37/38 are MSB-indexed: data byte4 bit2 (`0x04`) right and bit1 (`0x02`) left. Both bits set lights both arrows, without asserting a separate hazard-switch state. The raw b04 hex value remains visible for validation. This request was not in the original real capture and remains unverified on the user's car.

Indicator probes begin after the first main telemetry attempts, target2 Hz when core jobs are not badly overdue, and back off30–120 seconds on no-data/rejection. Indicator I/O timeout is capped at 1.2 seconds before normal adapter recovery; a rejected reply does not reconnect Bluetooth or reset motion samples. This optional job can run below2 Hz under saturation. There is no invented blink animation or assumption that a lever rather than lamp phase is reported. Polling may miss flashing transitions. Missing/stale replies display question-mark arrows and unavailable status, distinct from two reported-off bits.

Current requested data rates total about30 requests/s (U19 10, U03 10, speed3, RPM5, indicators up to 2), plus ECU-address commands; actual rates depend on shared adapter throughput. Clutch display still uses existing U19 replies at 1 Hz. All per-channel measured rates remain on the portrait screen.

Twenty-six Dashboard/shared JVM tests cover RPM byte order/scaling, indicator masks and unknown/off distinction, 5 Hz RPM scheduling, same-header engine grouping and indicator failure backoff. APK/test-APK compilation and lint pass. No emulator or vehicle validation was performed for this update.

## Version 0.1.6 circular layout

Historical layout; version 0.1.7 below replaces the RPM arc and reduces the highlights.

Three concentric bar arcs run clockwise from roughly 7 o'clock to 5 o'clock. Outer green is throttle; middle red is the existing nonlinear relative brake percentage; inner cyan fills with canonical engine RPM. The RPM scale is 0–6000, with dotted red from 4500–5000 and solid red from 5000–6000, matching the owner's instrument-cluster description. Filled portions of the red zone become brighter red. Arc fill clips at its scale limit while numeric RPM and pedal readings retain overflow. These zones are display markings, not new ECU limits or an inferred shift recommendation.

The upper interior shows plain numeric OBD speed. The lower interior has a large `D AUTO` / `M MANUAL` badge (or P/R/N/unknown) and a horizontal gear strip. The current forward gear is largest in white; neighboring gears slide past it on adjacent shifts. b24's gear is amber with an underline and a persistent `NEXT? … · b24` label, even when it lies outside the visible strip. If b23 and b24 agree, the current gear remains white and gains the amber underline. b24 is still provisional; the display does not establish clutch preselection. P/R/N and missing gear data show their literal state instead of an invented forward gear. Non-adjacent jumps and recovery from missing data snap directly to the reported gear; only the strip position is animated for 180 ms, never speed, pedal, gear values or blink timing.

Clutch readouts, sampled indicator arrows, raw-byte comparisons, measured per-channel rates, stale-data handling and logging remain available. All polling targets and the brake curve are unchanged. Synthetic demo now alternates D/M and provides a separate b24 value to exercise the highlights; this is generated data, not evidence about the real field.

Validation: APK and test-APK compilation, lint and the 26 existing JVM tests pass. Layout previews execute the drawing code with a lightweight host graphics adapter using synthetic states (D, M, P, seventh gear, brake overflow and unavailable data); font metrics can differ on Android. No emulator or vehicle check was performed.

## Version 0.1.7 circular RPM fill

RPM now fills the entire inner disk upward behind speed and gear, with a subdued teal fill and horizontal surface. The fill's vertical level maps linearly from 0 RPM (empty) to 6000 RPM (full). Numeric RPM retains values above the fill scale; missing/stale data shows unavailable with no filled area. There is no RPM arc, tick scale, redline marking or color change at 4500/5000. The owner's redline description was reference information, not a request for those markings.

Current gear keeps its larger white number without the rectangular highlight. b24 uses a smaller, muted amber number and a thin underline; the persistent `NEXT? … · b24` caption is muted and remains provisional. When b24 equals the current gear, only the subtle underline is added. D/M stays prominent with a calmer manual-mode color. The outer throttle/brake arcs, sliding behavior, telemetry, polling targets, brake curve and logging are unchanged.

Validation: the 26 existing JVM tests, APK/test-APK compilation and lint pass. Synthetic host renders cover empty, partial and full RPM fill, matching b23/b24, D/M, P, seventh gear and missing data. No emulator or phone validation was performed.

## Version 0.1.8 compact mode badge

The mode badge now shows only D, M, P, R or N (— when unknown). Removed the redundant AUTO/MANUAL/PARK/REVERSE/NEUTRAL words and narrowed the badge around its centered letter. Telemetry, polling and the rest of the display are unchanged.

## Version 0.1.9 pedal arcs without percentage readouts

Removed the throttle/brake percentage numbers and their cards. Compact color legends identify the two pedal arcs. The clutch readouts and diagnostics move up to use the space. Raw b07/b08 and b32/b33 remain in diagnostics and logs; the brake curve and arc clipping are unchanged. No extra temperature channels were added in this display update.

## Version 0.1.10 standard coolant temperature

A new COOLANT card joins CLUTCH 1/2 in the temperature row. It uses standard PID `0105`, unsigned byte A minus 40 °C, and targets 1 Hz. The 2.5-second freshness limit, missing reply handling and per-channel measured rate prevent an old value or a missing reply from becoming a live zero. The clutch mappings remain provisional and are labeled separately from standard coolant. No guessed warning thresholds or overheat alarms were added.

Coolant is polled using the same engine address as speed and RPM, reusing that header when due. A failed coolant response removes only its own sample and backs off normally; other channels continue. Speed/RPM/coolant share functional-address fallback from 7E0 to 7DF. Current data request targets total about 31/s before header commands. These remain targets, not guaranteed rates. Raw TX/RX, timings, coolant target/freshness and decoding metadata are recorded. Synthetic demo supplies an independent coolant value.

Validation: all 29 Dashboard/shared JVM tests pass, covering unsigned coolant scaling, missing data, 1 Hz scheduling, same-header grouping, coolant failure isolation and functional fallback, alongside existing regressions. APK/test-APK compilation and lint pass. Synthetic host layouts include coolant, missing values and full encoded temperature extremes; no emulator or vehicle validation was performed.
