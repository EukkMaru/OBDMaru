# OBDMaru Watcher 0.1.1

A separate Android app (`dev.obdmaru.watcher`) that installs alongside Magic. It watches the 20 manufacturer-specific response blocks that returned data in the first real capture. These are still unknown raw bytes, including bytes with candidate interpretations in the analysis. There are 531 byte positions across those blocks; byte positions are not necessarily independent measurements.

## Use

1. Install the Watcher APK. Grant Nearby devices and notification permissions. Try **Demo** at your desk first; it is explicitly synthetic.
2. Park, plug in and pair your Bluetooth ELM327 adapter in Android settings. Close/stop Magic and any other OBD app so only Watcher uses the adapter.
3. Tap **Connect**, **Refresh**, select your adapter, then **Start**. Connection controls collapse to give the graph space.
4. Select U01–U20 and a **5 Hz** or **10 Hz** target. Default U19 is `7E1/2201A4`, the transmission block that contains the clutch temperature candidates. Each successful reply updates all bytes in the selected block together. Select blocks while parked or have a passenger operate the screen.
5. Scroll vertically for longer blocks. Each byte has a fixed 0–255 bar, decimal and hexadecimal readings, and ZERO/NONZERO text. A yellow index means it changed on the last display update. Optionally hide bytes only observed as zero since selecting this block; an unseen byte may still change later and will then appear.
6. Tap a byte and scroll to the bottom of the byte panel for its live bits, ordered 7 to 0. A nonzero byte is not automatically a binary signal: several flags, counters, or a multi-byte number may share it.
7. **Stop**, then **Share log**. Private JSONL recordings include exact real Bluetooth TX/RX chunks, requests, failures, selections, timestamps and the app version. Share only sessions you intend to disclose; diagnostics can contain identifiers.

## Rate and freshness

Only the selected block is polled. 5/10 Hz is the request target for that block, not 5/10 Hz for all 20 blocks. Requests are strictly serial and never queue catch-up bursts. ELM/ECU response time, multi-frame replies, Bluetooth latency and a 15 ms transport gap limit the achieved rate. The live screen reports successful sample Hz over a rolling three-second window, query duration and sample age. A response failure clears values immediately; samples older than 1.5 seconds are shown as unavailable. Rejected/no-data reads back off to about once per second; transport failures reconnect after two seconds and reapply setup. The protocol is fixed to the prior capture's 11-bit 500 kbit/s CAN.

Indices such as `U19 b29` are **zero-based data offsets with the positive-response service and identifier removed**. They match the raw-byte indices in the indexed analysis. No temperature, pressure, pedal percentage or cruise interpretation is asserted by this app. Bars never auto-normalize; zero remains visible as a label even though its bar has zero length.

## Build and verification

```sh
./gradlew :watcher:assembleDebug :watcher:testDebugUnitTest :watcher:lintDebug
./gradlew :watcher:connectedDebugAndroidTest
```

The shared Bluetooth transport, ISO-TP parser, session writer and export provider live in `shared/src/main/java`. The recorder retains its own package and behavior. The watcher APK is an experimental debug-signed build for direct installation, Android 12+.

Verification on API 35 emulator: three JVM tests check unsigned byte/bit indexing, request pacing and the stable catalog; an instrumented test runs the synthetic service at 10 Hz, switches to a 64-byte block at 5 Hz, checks fresh data and measured rates, stops, and reads the finished log through the export provider. The existing 21 recorder JVM tests also pass after sharing transport sources. Real adapter throughput and real vehicle readings require the next car-side run.

## Exclusive connection guard

This version shares a process-lifetime OS socket-name lock with Magic 2.0.2, Watcher 0.1.1 and Dash 0.1.3. Update every installed OBDMaru peer; older builds cause a clear update-required message before Bluetooth connects. While one real session owns the lock, another app names that owner and stops its start attempt. The lock remains held during reconnects and is released after closing Bluetooth, or automatically if the process dies. Demo sessions do not connect and bypass the lock. Arbitrary third-party apps are not controllable or reliably detectable; stop them manually.
