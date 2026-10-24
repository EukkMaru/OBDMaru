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

## U19 gear-state observation — uncertain

For Watcher block **U19 (`7E1/2201A4`)**, byte indices are zero-based data offsets after removing the positive-response service and identifier (`62 01 A4`). The cited OBDb definition labels the low four bits of **b23** as “Current gear” and the low four bits of **b24** as “Next gear” (`b23 & 0x0F` / `b24 & 0x0F`).

Owner observation reported 2026-10-24: b23 and b24 appeared identical, showing 1–7 for forward gears and 14 in reverse. Brief differences during shifts have not been established from a recording.

**b24 remains an uncertain gear-state field.** “Next gear” is the community database label, not a confirmed interpretation on this vehicle. Whether it represents a predicted/commanded upcoming gear, a gear physically preselected on the other DCT shaft, or another gear state is unresolved. Do not label it as the other clutch's prepared gear without further evidence. The observed agreement with b23 does not establish its distinct role.

## Documented alternatives to observed fields

Cross-check performed 2026-10-24 against the cited OBDb revision and the owner's 2026-10-22 real drive capture. All offsets below use data bytes after removing the positive-response service and identifier. A definition in a different request is an alternative to test, not permission to reuse its formula on an observed field.

| Quantity | Documented alternative | Evidence from the earlier drive |
| --- | --- | --- |
| Brake pressure | `7D1/220104`, b32–b33, signed 16-bit big-endian divided by 86.7; database unit is percent despite its pressure label | One attempt, negative reply `7F 22 31`; no pressure samples. Same offsets as the owner's U03 observation, but **U03 is `7D1/22C101`, a different identifier**. Do not transfer the formula or unit. |
| Accelerator pedal | `7E1/2201A0` or `7E1/21A0`, b08 × 100/255; also `7E0/2114`, b16 × 0.390625 | Both TCM requests rejected on all 21 attempts each; engine request rejected on its one attempt. These do not validate U19 b07/b08. Standard `0149`/`014A` remain available comparison signals. |
| Brake pedal state | `7E1/2201A0` or `7E1/21A0`, b24, scalar with no pressure unit | Same rejected TCM requests. This definition is not evidence for analog brake strength. |
| Brake light | `770/22BC06`, b04 scalar | Not attempted in the earlier capture. No established relation to U19 b26, U06 b10 or U12 b11. |
| Vehicle speed | TCM `2201A0`/`21A0` b06; U03 `7D1/22C101` b11–b14 are individually labeled wheel speeds | TCM alternatives rejected. U03 returned 15 samples. Wheel speeds and standard `010D` are comparison signals, not independent ground truth. |
| Torque | U11 `7E0/22ED05` b30 × 600/255 Nm, labeled crankshaft torque; U07 `7E0/22ED01` b30 × 23529/10000 Nm, labeled actual engine torque | Both returned 12 complete positive payloads. U11 b30 varied 0–64 (candidate decode 0–150.6 Nm); U07 b30 stayed at 3 (candidate decode about 7.06 Nm). U11 is a useful comparison candidate for U19 b20; constant U07 provides little validation. Neither proves what U19 b20 means. |

The earlier U03 samples also show **b32 and b33 are not exact duplicates**: they differ in 4 of 15 replies (observed ranges 0–10 and 0–8 respectively). The owner's newer observation that they appeared mirrored is retained, but their distinct roles and physical units are unresolved. Keep both raw channels. Normalizing either by the owner's approximately 140 reference press is a UI calibration only, not a documented maximum or percentage of physical braking capacity.

`app/src/main/assets/extended-probes.json` is a configurable-at-build-time list of additional finite read-only candidates. It extracts header/command pairs for `7E0`, `7C6`, `7D1`, `7D4`, `7D6`, `7B3`, and the `7A0/22B009` candidate from the cited database; signals/decoding formulas and model filters are omitted deliberately. Including a candidate does not imply compatibility. The plan is embedded in each session log. Only services `21` and `22` appear in this enhanced plan. There is no arbitrary command input in the app.

Standard Mode 01 polling uses functional OBD addressing. The recorder retains response headers and raw frames. Additional physical headers are used only after an `ATDPN` result confirms 11-bit CAN, and `7DF` is restored before standard requests. ELM default automatic CAN formatting/flow control is retained; no raw CAN injection or custom flow-control overrides are used. Multiple simultaneous OBD apps should not share the adapter during capture.

Successful discovery is not exhaustive ECU enumeration. The recorder doesn't search every CAN address/DID, enter extended diagnostic sessions, or monitor every broadcast frame. Unsupported replies, timeouts and failures are preserved distinctly; they may reflect adapter limitations or vehicle operating state as well as absent readings.

## Magic 2.0 discovery and priorities

The fast schedule samples `010D`, `010C`, `0149`, `014A`, TCM `2201A4`/`2201A5`, and engine `22ED06`/`22ED04`/`22ED05`. These are capture priorities, not verified field labels. Standard coolant/intake temperature and additional channels run in the background. Guided progression uses only fresh standardized `010D` speed to gate timing; candidate brake, cruise or manual bits never assert that an instruction was completed.

Magic also probes bounded neighboring read-data identifiers: engine `22ED00..22ED3F`, `22ED90..22ED9F`, and transmission `2201A0..2201AF`, once each before promoting positive replies to background polling. This is speculative read-only discovery, not source-backed decoding. Guided mode uses the existing finite bundle without these additional ranges. Entire zero data payloads remain logged and are retried slowly, never classified as missing hardware.
