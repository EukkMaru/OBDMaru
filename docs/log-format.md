# Session schema 1

UTF-8 newline-delimited JSON (`.jsonl`), one event per line. A partial last line from process/power loss can be ignored; interior corruption must be reported. Events are buffered and synced roughly every 500 ms; session boundaries and selected markers force a sync. Request/response logging is at the adapter's Bluetooth serial interface: original ECU CAN timing and electrical bus frames are not available through this transport.

Common fields:

- `seq`: increasing event sequence within the session, synchronized across writer threads.
- `wall_ms`: UTC Unix milliseconds, for human/calendar correlation.
- `mono_ns`: Android elapsed realtime nanoseconds, including deep sleep.
- `elapsed_ns`: monotonic nanoseconds since opening this session; use this for replay.
- `type`: event kind.

Important events:

| Type | Content |
| --- | --- |
| `session_start` | Schema/app version, vehicle context, adapter address/name, phone/Android details, explicit `demo` flag. |
| `plan`, `extended_plan` | Exact probe selection/configuration at capture time. |
| `connect_attempt`, `connected`, `reconnect_error`, `link_recovery` | Connection lifecycle and errors. |
| `tx` | `request_id`, command, phase, deadline, exact transmitted bytes as Base64 (including CR). |
| `rx` | Exact received Bluetooth byte chunk as Base64 plus convenience ASCII, current `request_id` or null for unsolicited data. Chunk boundaries are stream-read boundaries, not CAN frames. |
| `request_end` | Raw accumulated response, classification, request ID and normally request duration. A prompt-completed response may still contain an ECU rejection or adapter error. |
| `request_error`, `request_abandoned` | Error/timeout/recovery evidence. Partial input remains in `rx` even if no complete reply exists. |
| `discovery` | Union of Mode 01 supported PIDs across received ECU responses; raw bitmaps remain in earlier events. |
| `cycle` | Poll counts and remaining discovery work. |
| `marker` | User-supplied label with event timestamps. |
| `capture_error`, `session_end` | Terminal error and/or reason. Missing end means interrupted capture. |

TX/RX Base64 is authoritative; ASCII and classified outcomes are conveniences. Request IDs are unique within a session, including reconnects. Physical addressing is recorded through the `ATSH...` commands and recovery events; interpret a response with the preceding adapter setup. Discovery intentionally retains failures and unsolicited input. Logs contain no assumed clutch-temperature decoding.

## Magic 2.0 additions

Schema stays 1; `app_version` is `2.0.0`. `session_start.durability_interval_ms` documents the roughly 500 ms buffered-sync interval. Complete prior lines remain usable after abrupt termination; the latest unsynced tail may be lost. A normal stop syncs the footer. Stopping is no longer logged as a capture error.

- `plan.capture_mode`: `MAGIC` or `GUIDED`; desk simulation is independently marked by `session_start.demo`.
- `poll_plan`: desired strategy and initial job counts, with `targets_are_not_guarantees`.
- `neighbor_discovery_plan`: exact bounded additional read-only request list for Magic mode.
- `sample_classification`: source/header+command, positive-response match, payload size, all-zero data classification and elapsed request time. ISO-TP is reassembled per ECU before matching. Raw Base64 is still authoritative.
- `poll_metrics`: actual data requests per second and per-source interval count/mean/min/max for the reporting window. Setup/addressing commands are excluded from data-request throughput.
- `guide_plan`: exact stage IDs, words, eligible durations and speed gates. `prompt_is_not_action` is true.
- `guide_step`: instruction window beginning, words, index and speed context; `driver_action_confirmed` is false.
- `speech_queued`, `speech_started`, `speech_finished`, `speech_error`: TTS engine lifecycle, correlated by utterance ID. Callback timestamps show actual speech playback more precisely than step entry; they do not establish driver response time.
- `guide_pause`, `guide_skipped`, `guide_wait`: manual pause or contextual wait/skip evidence.
- `guide_complete`: sequence ended while `capture_continues` is true. The recording's real end is still `session_end`.

## Magic 2.0.1 guided control continuity

`app_version` is `2.0.1`. `guide_plan` includes `guide_version`, `completion_words`, `timers_wait_for_speech`, `parking_brake_observable=false` and `cruise_state_observable=false`. Each plan step and `guide_step` adds `expected_before`/`expected_after`: these are requested states, never confirmations.

`guide_control_observation` records the response source, sample completion time and raw owner-observed candidate fields: U19 b07/b08, b21/b22, b23/b24 and b26, plus masked gear/brake candidates; U03 b32/b33 are preserved independently. These events continue after guide completion until recording stops and do not claim parking-brake or cruise knowledge. Raw TX/RX remains the authoritative recording.

Speech callbacks now include `speech_stopped`. Observation-window clocks pause while the current prompt is queued/speaking. Actual `speech_started`/`speech_finished` timestamps remain distinct from instruction issuance and observed controls. Session summaries retain the requested before/after states and observation counts by source.

## Dash 0.1.13 parked benchmark

Detailed transport timing is opt-in for the [parked bottleneck test](bottleneck.md); normal captures retain their existing scheduling.

- `benchmark_start`, `benchmark_phase_setup/start/end/skip/abort`, `benchmark_plan_end`, `benchmark_complete`: experiment lifecycle, exact applied settings, desired repetitions, outcomes and terminal completion. Finishing an experiment does not imply all requests succeeded. `benchmark_complete` requires reaching the end of the final scheduler window.
- `benchmark_sample`: logical and actual command, physical request header, warmup status, positive/completeness validation, raw-output byte count, headered CAN-line count and query/header/cycle durations. Full raw bytes remain in TX/RX.
- `benchmark_io_error`: command/phase/error before resync. Recovery abandons that phase; an unclean boundary stops the test.
- `rx.read_start_ns/read_end_ns`: timestamps immediately around the blocking stream read, before copying/encoding/logging. A blocked read can span idle time and several internal transport stages. Absent/null fields mean detailed timing was disabled.
- `request_timing`: `call_ns`, `write_start_ns`, `write_end_ns`, first/last read/dequeue timestamps, chunk/byte counts, RX logger duration, maximum delivery-to-dequeue lag, TX logging/preparation duration, request-end logging duration and post-prompt gap. Emitted for prompt-completed requests; timed-out requests retain partial RX and failure events instead. Timestamp durations use `_ns`; they are phone elapsed-realtime timestamps, never ECU/CAN/radio clocks.
- `log_sync`: start/end of buffered flush plus filesystem sync while holding the logger lock. This timing event is itself buffered; it does not recursively sync.

The benchmark uses the same schema and exact raw-byte replay. Nanosecond storage does not establish nanosecond measurement precision. RX logger duration overlaps delivery-to-dequeue lag, and read chunks are not CAN/RFCOMM packet boundaries. See the benchmark guide for comparisons and limitations.
