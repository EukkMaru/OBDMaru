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
