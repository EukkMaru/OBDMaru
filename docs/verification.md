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
