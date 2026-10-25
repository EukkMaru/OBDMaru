# Parked adapter bottleneck test

Dash 0.1.13 adds **Connect → Parked bottleneck test**. It uses the existing Bluetooth ELM327, with no laptop or new hardware at the car. This is a measurement tool for the current setup, not a promised 10 Hz fix.

## Run it

1. Install [Dash 0.1.13](https://github.com/EukkMaru/OBDMaru/releases/download/dashboard-v0.1.13/OBDMaru-Dash-v0.1.13.apk) over the existing Dashboard. Stop Magic, Watcher and other OBD apps.
2. Park in P, engage the parking brake, keep the engine idling in a ventilated place, and leave the car controls alone. Select the paired adapter in Dash.
3. Tap **Parked bottleneck test → Start parked test**. Progress appears above the dashboard. This usually takes several minutes; a 12-minute budget is checked between requests. Bluetooth connection and an in-flight request can extend that boundary briefly.
4. Wait for **Benchmark complete · log saved**, then **Share log**. Stop can end it early; the partial log is still useful. If it stops with an error, share that log too.

No guided drive is needed. Speed zero is required before measurement and checked between experiments. Missing speed aborts rather than assuming the car is parked; P and parking-brake state remain user instructions, not verified sensor states. During the final regular-scheduler test, an observed nonzero speed also aborts. The test cannot continuously verify speed while talking to another ECU. A second, separately labeled ignition-on/engine-off run can be useful after the idle run, but is optional and some ECUs may reject reads with the engine off.

## Experiments

There are 73 fixed experiment slots followed by a 45-second normal Dashboard scheduler window. Three warmup repetitions precede each measured experiment. Unsupported settings are skipped; three consecutive invalid replies stop that experiment. Raw failures, skips, timeouts and partial replies remain in the recording. Optional probes therefore do not need to work for the test to be useful. Setup/reset/zero-speed checks are logged separately from timed sample windows.

| Comparison | What it can show |
| --- | --- |
| Repeated `ATI`, `ATRV`, `ATDP`, `ATPPS`, with a final `ATI` baseline | Phone/Bluetooth/dongle transaction cost without an ECU reply; longer local output and drift. `ATPPS` reads parameter status; it does not write parameters. |
| Physical engine `7E0`: speed, RPM, accelerator position D, coolant; functional `7DF` speed | Individual request ceilings, addressing differences and multiple responders. |
| `7E1/2201A4`, `7E1/2201A5`, `7D1/22C101`, `7E0/22ED06`, optional `770/22BC08` | Different ECUs, reply lengths and multi-frame delivery patterns. A positive complete reply of the expected minimum length is required. |
| Unrestricted reads versus learned response counts | ELM final response-wait cost. Counts are learned from complete single-ECU replies, never guessed. Invalid hinted replies disable the hint for that experiment. |
| Spaces/compact output, linefeeds, echo | ASCII output-size and formatting cost, each with a repeated baseline. |
| Headers on/off/on for unrestricted single-frame speed | Header-display cost without changing response-count policy. Headerless multi-frame parsing is deliberately excluded. |
| `ATAT0/1/2` × `ATST08/20/64`, plain and learned-count reads | Adapter waiting-policy changes, retained alongside completeness/failure rates. Hexadecimal timeout values are adapter settings; they do not change ECU processing or CAN bitrate. |
| Four individual engine PIDs, combined RPM/speed, combined four PIDs, separate baseline again | Vehicle/clone batching support and cost per set of requested values. Batches must contain every requested PID in one complete ECU payload. |
| Cached header, redundant identical `ATSH`, cached baseline again | Avoidable local command overhead. |
| Alternating TCM/ABS/engine versus grouped requests, alternating baseline again | Address-switching cost and throughput/freshness tradeoff. Both measured workloads request the same number of each value; grouping produces bursts and older intervening values. |
| 0/5/15/30/0 ms post-prompt gaps | Cost of deliberate application pacing and any observed reply-reliability difference. |
| Regular Dashboard scheduler, 45 seconds | Real scheduled rates, lateness and header costs, compared with isolated ceilings. The normal scheduler settings and backoff remain in force. |

Experiments restore the known baseline before changing a setting. The baseline uses echo/linefeeds off, headers on, adaptive timing mode 1, timeout `32`, CAN 11-bit/500 kbit/s, automatic CAN formatting/flow control, compact output if accepted and no fixed transport gap. Repeated baselines expose drift but cannot eliminate all vehicle/adapter state differences. A failed experiment is not a speed improvement. The app resynchronizes after a transport timeout and abandons that experiment; losing a clean command boundary stops the benchmark. The shared OBDMaru guard prevents simultaneous sessions from updated OBDMaru apps; Android does not let it prove that an unrelated third-party app is idle.

## Timing and raw evidence

The benchmark retains exact Base64 TX/RX bytes and adds timestamps immediately before/after socket writes and blocking reads, before RX logging, and when the consumer dequeues each stream chunk. `request_timing` reports write/flush duration, first/last stream-read delivery, queue delivery lag, RX logging time, TX logging/preparation time, request-end logging time, and the actual post-prompt gap. `log_sync` measures buffered flush plus filesystem sync under the log lock. RX logging and consumer lag can overlap; they must not be blindly added to transaction duration. The instrumentation itself has a cost and is enabled only for this mode, including its final scheduler window.

Bluetooth provides a byte stream. One read can contain several CAN text lines, and one line can span reads. A completed line is timestamped when its delimiter reaches the phone. These are **phone delivery times**, not ECU/CAN arrival times, ISO-TP separation guarantees, RFCOMM packet timestamps, UART bit measurements or radio airtime. Nanosecond fields provide a common clock, not a claim of nanosecond timing accuracy. ASCII bytes and decoded frame counts are observable; exact CAN wire bits, stuffing, arbitration, retransmissions and internal queues are not.

The test does not change the dongle's internal UART baud, write programmable parameters, force ISO-TP flow-control timing, switch vehicle buses/pins, issue actuator commands or start an unbounded bus monitor. Those changes either require independent hardware evidence or can break the current Bluetooth connection. It cannot exactly split ECU time from dongle/Bluetooth buffering; subtracting the adapter-only median from another median does not establish that split.

## Desktop report

Keep the capture private. Run:

```sh
python3 tools/bottleneck.py /path/to/capture.jsonl
python3 tools/bottleneck.py /path/to/capture.jsonl --json > /persistent/private/path/report.json
```

The report preserves per-phase completion/skip/abort status, warmups and failures. Valid latency statistics exclude warmups and invalid replies. It includes median/p10/p90 values for query/header/cycle duration, output size, stream chunk count, phone frame-line delivery spread, prompt tail, logger/dequeue/write costs, filesystem sync and the final scheduler. Returned report fields omit device addresses and raw payloads; the source capture still contains private session metadata. A partial last JSON line without its newline can be ignored; interior corruption is reported. No true CAN timing or ECU latency is inferred.

Host tests cover the full fixed plan, parked checks/fallback, hint truncation, batching completeness, rejected settings, cancellation, timeout recovery and lost-boundary aborts. Python tests cover fragmented lines, read-time versus log-time, echo filtering, invalid/warmup exclusion, partial logs and privacy of report fields. APK/lint/test compilation does not establish performance on the real vehicle; that remains the purpose of the new capture.
