# Verification — 2026-10-21

- Debug APK built successfully (JDK 17, SDK 35, AGP 8.9.1).
- Five JVM protocol tests passed: ECU bitmap union, continuation bits, headerless replies, truncated/negative input, and enhanced-positive detection.
- Four desktop tests passed: exact raw replay, negative coverage reporting, interrupted-tail recovery, and interior-corruption rejection.
- One Android API 35 emulator integration test passed: demo capture, recorded TCM rejection/NO DATA, timestamp marker, continued background recording, clean stop, unique request IDs, exact TX bytes, and byte-identical content-provider export.
- App-generated demo recording also passed desktop summary, exact-byte playback, and strict TCP request/response replay.
- Android lint: zero errors; two informational dependency-version warnings for pinned Android test libraries.

Not verified: the physical generic ELM327 unit, Bluetooth interoperability with the S23+, Samsung/Android 16 background behavior, the car's actual diagnostic coverage, or clutch-temperature payload interpretation. Emulator demo data is synthetic and does not establish vehicle support.

Reproduce Android checks with an emulator/device connected:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
python3 -m unittest discover -s tools -v
```

# Magic App 2.0 — 2026-10-22

- Debug APK `2.0.0` / version code 2 built successfully.
- 21 JVM tests passed (7 protocol, 5 scheduler, 9 guide). Coverage includes complete ISO-TP assembly and rejection of incomplete frames; background fairness; zero-only backoff; a representative adapter-latency scheduling simulation; departure announced while parked; stopped/stale-speed pauses; stronger-input speed gating and timed skip; and adjacent manual-shift route ordering.
- 4 Python analysis/replay tests passed.
- 2 API 35 Android emulator integration tests passed: Magic capture with raw failures/background operation/export; Guided plan, cadence metrics, paused guide with continuing raw recording, normal stop without a spurious capture error.
- Lint: zero errors; two dependency-version notices for pinned Android test libraries.
- Old release and new APK signing certificates match. Installing v2 over v0.1.0 on the emulator preserved a file in the app's private storage.

This does not validate hardware throughput, physical signal meanings, audible voice routing on the S23+, or completion of driving actions. Android TTS must have a usable English voice and be checked with the app's voice-test button. Guidance timestamps and speech callbacks document instructions, not ground-truth driver behavior. Actual per-source polling intervals are measured in each recording; configured intervals are targets.

## Magic 2.0.1 control continuity update

- 28 guide stages with 1,020 seconds of eligible observation windows, plus speech/waiting time. Exact words and intended before/after states are embedded in each log.
- Explicit parked cruise checks, foot-brake/D/parking-brake departure handoff, separate moving cruise controls, conditional adjacent manual shifts, return to D and complete final parking instructions.
- Observation clocks wait for TTS completion; speech errors pause guidance while raw recording continues.
- 26 JVM tests pass, including transition ordering, speech-time accounting and raw candidate observations; 5 Python replay/summary tests pass.
- Android app and instrumented-test APKs compile; lint has no errors. No emulator or vehicle run performed for this update. Physical audio output and the full real drive still need verification on the phone.
- Build uses persistent disk storage, one Gradle worker, 768 MiB Gradle heap and no retained daemon. No emulator or large files are placed in /tmp.

## Shared connection guard

- Builds: Magic 2.0.2 / code 4; Watcher 0.1.1 / code 2; Dash 0.1.3 / code 4.
- Shared atomic abstract-socket bind is acquired before any real Bluetooth connect and held through reconnection. Owner marker identifies the active OBDMaru app; kernel closes descriptors on process death. Unexpected lock errors fail closed.
- Installed legacy peers are visible through explicit package queries and block real captures until upgraded/uninstalled. Third-party serial ownership cannot be guaranteed by this cooperative guard.
- 58 JVM tests pass across modules, including each app pairing, simultaneous acquisition, cleanup, and idempotent release. All three app/test APKs build and lint passes.
- Android cross-app integration tests are compiled but were not executed. The first isolated emulator boot stopped at its 2 GiB cgroup cap; a bounded retry was stopped on user feedback. No device/cross-UID runtime verification is claimed. No emulator or Gradle daemon remains running.
- Persistent project instructions now prohibit emulator startup without explicit user request and large artifacts on RAM-backed /tmp.

## Dash 0.1.12 — 2026-10-26

- 82 JVM tests across all three modules pass, including 42 Dashboard/shared tests.
- All three app APKs and the Dashboard instrumentation-test APK compile; Dashboard lint passes.
- New cases cover compact CAN parsing, strict multi-PID pairing and fallback, timeout resynchronization, optional indicator fairness under load, normal stop and manual gear selection.
- Synthetic host previews execute the actual drawing code and verify M primary b24 with secondary b23, D, parked and missing states.
- No emulator, phone, adapter or vehicle check was run. The next capture must validate combined-PID support, indicator mapping and achieved rates.
- Build used persistent storage, one worker, in-process Kotlin, a 768 MiB Gradle heap and no retained daemon.
