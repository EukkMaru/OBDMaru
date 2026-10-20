# OBDMaru

An Android diagnostic recorder for a **2018 Hyundai Tucson TL, pre-facelift, 1.7 diesel with dry DCT**, using a purchased generic **ELM327 Bluetooth Classic** adapter and a **Galaxy S23+ running Android 16 / One UI 8.5**. No root required.

The first milestone is a recorder, not a gauge dashboard: capture a useful diagnostic session in the car, then investigate supported readings and develop the eventual dashboard against replay at a desk. DCT clutch temperatures are a priority to investigate, not a confirmed available signal. Android Auto is a future optional experiment; the phone app is primary.

## Use

1. Install the debug APK from `app/build/outputs/apk/debug/app-debug.apk` on the phone. Allow installation from the app opening the APK if Android asks.
2. Optionally run **Run demo capture (no car)**, stop it, and export it to verify the app and file workflow at your desk. Demo sessions are explicitly marked and contain synthetic data.
3. With the car parked, plug in the adapter and pair it through Android Bluetooth settings, using the PIN supplied with the adapter. Return to OBDMaru and refresh paired devices.
4. Grant Nearby devices and notification permissions, select the adapter, and tap **Start vehicle capture**. Start the engine and leave the recorder running during an ordinary drive. Configure/interact while parked; a passenger can add markers.
5. Stop from the app or notification. **Save a session to Files** or **Share a saved session**. Transfer the `.jsonl` file for analysis. All interrupted captures remain in the session chooser too.

The app records with the screen off using a connected-device foreground service and a partial wake lock. It stops after two hours. Android/Samsung process termination can still interrupt recording; completed events are synced incrementally. An interrupted file has no `session_end` and can still be analysed. Keep the app installed until sessions are exported; uninstalling deletes app-private recordings.

## What it does

- Identifies the adapter, captures voltage and the selected OBD protocol.
- Queries every standard Mode 01 support page and merges supported PIDs across responding ECUs.
- Captures VIN/calibration/name requests, stored/pending/permanent DTC reads and a freeze-frame support snapshot, including failures.
- Polls supported Mode 01 PIDs round-robin, and probes undeclared Mode 01 PIDs once in interleaved batches.
- On confirmed **11-bit CAN**, probes five transmission candidates immediately, including `7E1 / 2201A4`, and repeats positive candidates. Periodically retries unsuccessful transmission candidates.
- Interleaves a bundled, finite list of additional engine/vehicle read candidates, preserving positive and negative outcomes. These candidates span Tucson variants; compatibility with this exact car is unverified. It does not scan every possible manufacturer identifier.
- Records exact TX bytes, RX byte chunks, timestamps, request IDs, raw complete replies, errors, markers, addressing changes, and the exact discovery plan.
- Uses one outstanding request at a time. Timeouts close the link; recovery reinitializes the adapter and restores the selected request header. A twice-failing diagnostic request is recorded and skipped. Six unsuccessful reconnect attempts end the session with an error rather than looping forever.
- Does **not** clear faults, write ECU data, perform security access, change ECU diagnostic sessions, or run actuator tests. It changes adapter-local settings to issue read requests.

Discovery can take several minutes on a slow adapter, and a short drive may end before all probes complete. Status reports the remaining probe count. Useful supported readings and TCM candidates are collected while the remaining probes run. Failed requests document what happened in that session, not proof that a reading can never be supported.

## Build and checks

Requires JDK 17 and Android SDK platform 35/build-tools 35.0.0. Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir=/path/to/sdk`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s tools -v
```

The Gradle wrapper pins Gradle 8.11.1; AGP is 8.9.1 and Kotlin is 2.1.10. The APK supports Android 12 onward and targets API 35. Bluetooth is Classic SPP/RFCOMM; no BLE transport is implemented. Secure RFCOMM is attempted first, with insecure RFCOMM fallback during recovery.

## Desktop analysis and replay

```sh
python3 tools/session.py /path/to/capture.jsonl
python3 tools/session.py /path/to/capture.jsonl --replay --speed 4
python3 tools/session.py /path/to/capture.jsonl --serve 35000 --speed 1
```

The default report summarizes coverage, outcome counts, errors and discovery. `--replay` emits the original received bytes on stdout with recorded timing; `--speed 0` removes delays. `--serve` provides a strict, chronological request/response replay over localhost TCP. The client must send the same commands terminated with CR; a mismatch closes replay rather than inventing an answer. This is a desktop transport for future development, not Bluetooth emulation. Reconnect events and app lifecycle events remain in the original log for later simulation; the TCP server does not emulate those faults automatically.

## Data and sources

Captures stay local until explicitly exported; the app has no Internet permission. Logs can contain VIN, calibration identifiers, fault codes, the Bluetooth address, and timestamped driving telemetry. They are excluded from Git. Share captures privately rather than committing them to the public repo.

See [diagnostic sources](docs/diagnostics.md), [log format](docs/log-format.md), and [third-party notices](THIRD_PARTY.md). Vehicle readings, Bluetooth compatibility and clutch-temperature decoding require actual-car verification.
