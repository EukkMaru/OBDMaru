# OBDMaru Scanner 0.1.0

A separate Android 12+ app for the existing Bluetooth Classic ELM327 adapter. Package: `dev.obdmaru.scanner`. It installs alongside Magic, Watcher and Dash.

[Download Scanner APK](https://github.com/EukkMaru/OBDMaru/releases/download/scanner-v0.1.0/OBDMaru-Scanner-v0.1.0.apk) · [Release](https://github.com/EukkMaru/OBDMaru/releases/tag/scanner-v0.1.0)

## Use

1. Install the APK and grant Nearby devices permission. Notification permission lets you see the background scan notification.
2. Try **Demo** at your desk. Its display and exports are explicitly synthetic; sample faults are examples, not observations of your car.
3. Park, apply the parking brake, plug in the adapter and switch the ignition ON. The engine may be off. Pair the adapter in Android Bluetooth settings if needed.
4. Stop other OBD apps. In Scanner, **Refresh**, select the paired adapter and tap **Scan car**.
5. Wait for the finite scan to finish. It saves and disconnects automatically. **Stop** preserves a partial scan.
6. Use **Share report** for readable text and **Share raw** for the diagnostic JSONL. **Saved scans** opens older reports and their matching raw logs. Send logs privately.

The shared connection guard excludes updated Magic/Watcher/Dash while Scanner is connected. Existing guard-enabled releases still block Scanner through the same OS lock, although they may identify its owner as “Another OBDMaru app.” They do not need reinstalling just to honor exclusion. Installed pre-guard versions must be updated or uninstalled. Arbitrary third-party OBD apps are not covered by this cooperative lock.

## What it reads

15 bounded requests on the Tucson's known CAN 11-bit / 500 kbit/s protocol, using functional request ID `7DF` and retaining separate responding ECU IDs `7E8`–`7EF`:

| Request | Information |
| --- | --- |
| `0101` | Check-engine lamp request, stored emissions DTC count and readiness since last clear, including the diesel monitor layout |
| `03` | Stored emissions DTCs |
| `07` | Pending emissions DTCs |
| `0A` | Permanent emissions DTCs, when supported |
| `020200` | Triggering DTC for freeze frame 0 |
| `020400`, `020500`, `020B00`, `020C00`, `020D00`, `020F00`, `021000`, `021100`, `022300`, `024200` | Available frame-0 load, coolant, intake pressure, RPM, speed, intake temperature, air flow, throttle position, rail pressure and ECU supply voltage |

Freeze frame is recorded fault-time information, not a live gauge. Some PIDs or the entire frame may be unavailable. This version reads frame 0 only. Actual reply and module coverage require testing on the car.

This is standard **emissions OBD-II** coverage, not a full Hyundai GDS scan of ABS, airbags, body electronics or every transmission fault. A transmission ECU may answer standard requests, but its presence or zero returned standard codes does not prove complete transmission coverage. No code clearing, ECU writes, actuator tests, security access or extended diagnostic sessions are performed.

## Fault meanings: what is public?

The encoding and generic definitions are standardized in SAE J2012/J2012DA. Generic code descriptions are available in public community datasets, but availability does not make every description Hyundai-verified or establish applicability to a particular engine/ECU.

Scanner bundles **9,341 generic community descriptions** filtered from [Wal33D/dtc-database](https://github.com/Wal33D/dtc-database/tree/04c43d72e7db7197658b6f72fe582c5076d9eee8), under its MIT license. The pinned source, license and filtering are reproducible with `python3 tools/import_scanner_codes.py`. Reserved descriptions and range headings are excluded. Manufacturer-controlled ranges never borrow a different brand's definition. The dataset's repair advice and other-brand definitions are not bundled.

**A complete, verified, freely downloadable catalog for the exact 2018 Tucson TL 1.7 CRDi / 7DCT variant has not been established.** Unknown or manufacturer-controlled codes retain their identifier and ECU, with an explicit missing-definition label. A public code-search link is provided for manual follow-up; it does not certify search results.

[Hyundai's Korean GSW service-information portal](https://gsw.hyundai.com/) requires a login. [Hyundai's US service-information offering](https://www.hyundaitechinfo.com/external/files/HyundaiTechInfo_Service_Information_Subscriptions.pdf) explicitly includes DTC information by subscription and is limited to US-market vehicles; it is not a verified reference for this diesel variant. No restricted Hyundai catalog has been copied into this app.

## Evidence and failure handling

- “ECU explicitly reported 0 codes” requires a complete positive response and a consistent DTC count. It applies only to that responding ECU and that category.
- `NO DATA`, missing/negative replies, inconsistent counts and incomplete ISO-TP messages remain unknown/unresolved. No overall “healthy vehicle” verdict is generated.
- Frames are assembled within one request and one ECU. Missing or out-of-order frames are rejected. Identical replies are deduplicated; conflicting replies are unresolved.
- Fixed conservative adapter waits are used instead of high-rate adaptive polling. An incomplete packet or response-pending negative reply requires prompt/identity recovery before another diagnostic request. A transport timeout stops the scan rather than assigning late bytes to a new request.
- Reports are saved after each completed diagnostic query and finalized at a normal end/stop. Raw TX/RX, failures and decoded findings remain in the local JSONL. An abrupt process kill can leave an in-progress report and lose the most recent buffered raw tail.
- Readiness describes whether ECU tests have completed, not whether the car is healthy. A DTC describes a detected condition, not a confirmed failed part.

No Internet permission is used. Lookup data works offline; **Search web** explicitly opens your browser with the code and model name. Files remain in app-private storage until exported. The read-only content provider exposes only granted `.txt`/`.jsonl` files inside the sessions directory. Export before uninstalling.

## Build and verification

Use the repository's resource constraints: persistent temp directory/toolchain, one worker, no retained daemon, in-process Kotlin and 768 MiB Gradle heap. No emulator is needed.

```sh
TMPDIR=/home/lab/.local/share/OBDMaru/build-tmp \
JAVA_HOME=/home/lab/.local/share/OBDMaru/toolchain/jdk-17.0.20.1+1 \
ANDROID_HOME=/home/lab/.local/share/OBDMaru/toolchain/sdk \
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/home/lab/.local/share/OBDMaru/build-tmp' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :scanner:testDebugUnitTest :scanner:assembleDebug :scanner:lintDebug
```

Initial validation: 30 Scanner/shared JVM tests, APK compilation and lint pass (zero errors; five English-text internationalization warnings). All 94 existing-module JVM tests and 13 desktop Python tests also pass. No emulator, phone, adapter or vehicle scan was run. Bluetooth interoperability, multi-ECU flow control, actual code/freeze-frame coverage, Android sharing and visual rendering remain device checks.

Protocol reference: [ELM327 datasheet](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf), diagnostic modes and trouble-code interpretation. [SAE J2012DA](https://saemobilus.sae.org/standards/j2012da_072010-digital-annex-diagnostic-trouble-code-definitions-failure-type-byte-definitions) is the maintained definitions reference; the app's community subset is not an official copy.
