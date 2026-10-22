# Session schema 1

UTF-8 newline-delimited JSON (`.jsonl`), one event per line. A partial last line from process/power loss can be ignored; interior corruption must be reported. Each completed event is flushed and synced to storage. Request/response logging is at the adapter's Bluetooth serial interface: original ECU CAN timing and electrical bus frames are not available through this transport.

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
