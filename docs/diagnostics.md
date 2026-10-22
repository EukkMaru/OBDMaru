# Diagnostic coverage and provenance

Adapter commands follow the [ELM327 data sheet](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf). The clone's reported firmware string is logged, not trusted as a capability guarantee. Required formatting settings are checked; unsupported optional identity commands remain evidence.

Enhanced requests are extracted from [OBDb/Hyundai-Tucson signalsets/v3/default.json](https://github.com/OBDb/Hyundai-Tucson/blob/b1306dc2c54e32ba4344fcbb5fd098ef66db50e7/signalsets/v3/default.json), revision `b1306dc2c54e32ba4344fcbb5fd098ef66db50e7`, retrieved 2026-10-21. That database is community-maintained and covers multiple model years, engines, and transmissions. Definitions are **candidates**, not validation on this particular 2018 1.7D.

Transmission candidates, request header `7E1` (expected response `7E9`):

| Request | Reason to capture |
| --- | --- |
| `2201A4` | Database associates it with gear and clutch 1/2 temperatures on older Tucson variants. |
| `2201A5` | Additional DCT data candidate; interpretation can differ by transmission. |
| `2201A0` | Transmission data candidate. |
| `21A0` | Older read-local-identifier alternative. |
| `21A1` | Older gear-related candidate. |

No temperature formula is applied in this recorder. In particular, transmission oil temperature must not be presented as dry clutch temperature. Even a positive reply requires checking payload layout, units, scaling and applicability before a dashboard labels it.

`app/src/main/assets/extended-probes.json` is a configurable-at-build-time list of additional finite read-only candidates. It extracts header/command pairs for `7E0`, `7C6`, `7D1`, `7D4`, `7D6`, `7B3`, and the `7A0/22B009` candidate from the cited database; signals/decoding formulas and model filters are omitted deliberately. Including a candidate does not imply compatibility. The plan is embedded in each session log. Only services `21` and `22` appear in this enhanced plan. There is no arbitrary command input in the app.

Standard Mode 01 polling uses functional OBD addressing. The recorder retains response headers and raw frames. Additional physical headers are used only after an `ATDPN` result confirms 11-bit CAN, and `7DF` is restored before standard requests. ELM default automatic CAN formatting/flow control is retained; no raw CAN injection or custom flow-control overrides are used. Multiple simultaneous OBD apps should not share the adapter during capture.

Successful discovery is not exhaustive ECU enumeration. The recorder doesn't search every CAN address/DID, enter extended diagnostic sessions, or monitor every broadcast frame. Unsupported replies, timeouts and failures are preserved distinctly; they may reflect adapter limitations or vehicle operating state as well as absent readings.

## Magic 2.0 discovery and priorities

The fast schedule samples `010D`, `010C`, `0149`, `014A`, TCM `2201A4`/`2201A5`, and engine `22ED06`/`22ED04`/`22ED05`. These are capture priorities, not verified field labels. Standard coolant/intake temperature and additional channels run in the background. Guided progression uses only fresh standardized `010D` speed to gate timing; candidate brake, cruise or manual bits never assert that an instruction was completed.

Magic also probes bounded neighboring read-data identifiers: engine `22ED00..22ED3F`, `22ED90..22ED9F`, and transmission `2201A0..2201AF`, once each before promoting positive replies to background polling. This is speculative read-only discovery, not source-backed decoding. Guided mode uses the existing finite bundle without these additional ranges. Entire zero data payloads remain logged and are retried slowly, never classified as missing hardware.
