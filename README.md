# OBDMaru — Magic App 2.0

Dash **0.1.13** adds a [parked adapter bottleneck test](docs/bottleneck.md): automatic read-only experiments, raw replies/failures, detailed phone-side transport/logging timestamps and a standard-library Python report. [Download Dash](https://github.com/EukkMaru/OBDMaru/releases/download/dashboard-v0.1.13/OBDMaru-Dash-v0.1.13.apk).

Android diagnostic capture for a **2018 Hyundai Tucson TL, pre-facelift, 1.7 CRDi with dry 7DCT**, a generic **ELM327 Bluetooth Classic** adapter, and a **Galaxy S23+ on Android 16 / One UI 8.5**. No root required.

[Download and instructions](https://EukkMaru.github.io/OBDMaru/) · [Releases](https://github.com/EukkMaru/OBDMaru/releases)

Capture evidence in the car, then investigate available telemetry and develop a dashboard against replay at a desk. Clutch temperatures, pedal input, brake state, and cruise data are investigation targets; captured replies alone do not validate a decoding. The phone app is primary; Android Auto remains optional future work.

## Two modes

**Magic** is a broad free-drive recorder. It prioritizes speed, RPM, accelerator channels, transmission blocks and selected engine blocks while discovering standard and manufacturer-specific read replies. It probes all Mode 01 PID addresses except support pages (queried separately), the bundled enhanced list, and bounded read-only neighboring identifiers (`7E0/22ED00..3F`, `7E0/22ED90..9F`, `7E1/2201A0..AF`). It is not exhaustive enumeration of every Hyundai identifier or CAN broadcast.

**Guided** prioritizes the same useful blocks, captures supported standard channels and the bundled enhanced requests, and speaks a continuous optional route. Start while parked in P with parking brake on and engine idling. Parked foot-brake patterns come first, followed by gentle departure toward 60 where appropriate, cruise 60→70, braking to cancel, varied pedal depth/coasting/braking, and optional adjacent manual shifts 7→6→5→6→7. The scenario assumes a suitable 90 km/h road; actual limits and conditions always take priority. There is no forced gear, target RPM, emergency stop, or sustained parked accelerator test. A full-pedal observation, if appropriate, is optional and about one second, followed by easing back and lifting.

Spoken guidance starts automatically after connection checks and fresh standardized speed data. Motion-stage timers pause at stops or stale speed data; departure is announced before moving. A stronger-input prompt only becomes eligible at a naturally lower pace, with a 75-second automatic skip if that opportunity never appears. Moving before the parked section finishes skips the remaining parked instructions. Prompts, speech start/finish/error callbacks, pauses and skips are recorded as evidence, not assertions that the driver followed them.

After the final instruction **recording continues**. Keep driving until you can park, then stop and export. All phone controls during the drive are for a passenger; otherwise use them only while parked.

## Install and use

1. Install the release APK over the previous version to preserve local sessions. Allow installation from your browser/My Files if Android asks. Export sessions before uninstalling.
2. Grant Nearby devices and notification permissions. Try **Test spoken instructions** and check media/navigation audio volume and an installed English TTS voice. Speech is Android TTS, not bundled human audio. If speech is unavailable, guided progression waits while raw recording continues.
3. Optionally run **Run demo capture (no car)** and export it. It uses synthetic data explicitly marked `demo: true`. A guided desk demo cannot complete its driving phases because its speed stays zero.
4. Pair the adapter in Android Bluetooth settings. While parked, refresh paired devices, select the adapter and choose Magic or Guided. Start capture; for Guided wait for the first spoken instruction while parked.
5. Take an ordinary Magic drive or follow the optional guided route. The screen may be off; a connected-device foreground service and partial wake lock keep capture working. Android/Samsung can still terminate the process. Captures stop at two hours.
6. Once parked, **Stop and save**, then **Save a session to Files** or **Share a saved session**. Send the `.jsonl` privately for analysis; ideally one Magic and one Guided session.

The full route is visible in the app and on the download page. Timings describe instruction windows, not exact action timestamps; the raw data is the evidence of what happened.

## Polling and logging

One request is outstanding at a time. Turnaround is 15 ms instead of v0.1.0's 100 ms, and redundant `ATSH` commands are avoided. A deadline scheduler reserves background work (one slot after at most three ready fast requests in Magic, seven in Guided), so discovery cannot be starved. Priority intervals are targets, not guaranteed update rates; actual intervals and throughput are logged every five seconds. Zero-only background payloads are revisited at 120 seconds, failed background tasks at 60 seconds, and unsuccessful fast tasks back off to 10 seconds. Zeros are retained as evidence, not interpreted as absent hardware.

TX commands, RX byte chunks, completed replies, failures, addressing, per-sample classification, metadata, guide events and cadence are preserved. Writes are buffered and synced roughly every 500 ms; markers, guide steps, session start and normal stop sync immediately. Abrupt termination can lose the latest unsynced tail; earlier complete JSONL lines remain recoverable. Export before uninstalling.

Timeout recovery closes the link before another request, reinitializes the adapter and restores the selected header. A twice-failing diagnostic query is logged and skipped; six unsuccessful connection attempts end the session. Read-only diagnostic services are used; no fault clearing, ECU writes, security access, extended ECU sessions or actuator tests.

## Build and checks

Requires JDK 17 and Android SDK platform 35/build-tools 35.0.0. Set `ANDROID_HOME` or create untracked `local.properties` with `sdk.dir=/path/to/sdk`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest # emulator/device attached
python3 -m unittest discover -s tools -v
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Supports Android 12+, targets API 35. Classic SPP/RFCOMM only; no BLE transport. Secure connection first, insecure fallback on recovery. Gradle 8.11.1, AGP 8.9.1, Kotlin 2.1.10. Debug release updates require the same local signing key; never publish that key.

## Analysis and replay

```sh
python3 tools/session.py /path/to/capture.jsonl
python3 tools/session.py /path/to/capture.jsonl --replay --speed 4
python3 tools/session.py /path/to/capture.jsonl --serve 35000 --speed 1
```

Summary includes outcomes, discovery, errors, guide windows, speech events and measured request intervals. `--replay` emits exact received bytes; speed 0 removes delays. `--serve` provides strict chronological request/response replay on localhost TCP: send the same commands terminated with CR. Mismatches close replay rather than inventing answers. Reconnect and lifecycle faults remain in the log but are not automatically simulated by the TCP server.

## Privacy, evidence and hosting

Logs may contain VIN, identifiers, Bluetooth address and driving telemetry. Captures stay local until exported and are ignored by Git. Android TTS is a separate system service; install an offline voice if desired. The recorder itself has no Internet permission.

The GitHub Pages site serves public installation instructions from `/docs`, with the APK stored in GitHub Releases. No drive recordings are hosted. See [diagnostic sources](docs/diagnostics.md), [log format](docs/log-format.md), [verification](docs/verification.md) and [third-party notices](THIRD_PARTY.md). Source mappings and plausible correlations are not independent sensor validation.

## Watcher app

A separately installable portrait raw-byte monitor for the 20 observed unknown blocks, with live bars, ZERO/NONZERO indicators, bit inspection, 5/10 Hz targets and measured sample rates. [Usage and rate limits](docs/watcher.md). Build with `./gradlew :watcher:assembleDebug`.

## Dashboard app

[OBDMaru Dash](docs/dashboard.md) is a separate portrait display with throttle/brake arcs around an RPM circle that fills upward, numeric OBD speed, a sliding gear strip, prominent D/M mode, subtle provisional b24 highlighting, sampled indicators, standard coolant and provisional clutch temperatures. Includes synthetic demo, sample freshness and raw logs. Build with `./gradlew :dashboard:assembleDebug`.

## Magic 2.0.1: continuous control instructions

The 28-stage guide explicitly covers the parked setup, cruise master on/off while parked, holding the foot brake to select D and release/check the parking brake, departure, separate moving cruise operations, pedal windows, adjacent manual shifts, return to D, and the full final parking sequence. There are 17 minutes of observation windows, plus speech/waiting time. Observation timers wait for speech completion. Full words appear in the in-app route preview and project page.

Each prompt logs expected-before/after states as intentions. Timestamped `guide_control_observation` events retain U19 selector, mode, gear, pedal and brake-switch candidates, and independent U03 brake-strength bytes. Guided mode prioritizes U03 captures. Parking-brake and cruise state are explicitly unknown; neither prompt playback nor elapsed time confirms an action.

## One OBD connection at a time

Update every installed OBDMaru app to **Magic 2.0.2**, **Watcher 0.1.1** and **Dash 0.1.3** (or newer). Real captures acquire a shared phone-wide OS lock before connecting. A second app stays disconnected and names the active app. Stop recording in that app before switching. The guard remains held through reconnect attempts and is released on stop or process death. Demo mode does not use the connection lock.

Older installed OBDMaru versions cannot honor the lock, so updated apps block real capture until those peers are updated or uninstalled. Android's public API does not expose reliable ownership/enumeration of arbitrary third-party OBD serial connections: stop unrelated OBD apps yourself. This guard coordinates updated OBDMaru apps, not the entire Android system.
