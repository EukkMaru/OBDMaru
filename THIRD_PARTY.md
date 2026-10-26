# Third-party notices

The enhanced diagnostic candidate data, including the TCM candidate list and `app/src/main/assets/extended-probes.json`, is adapted from **OBDb/Hyundai-Tucson contributors**, [Hyundai-Tucson](https://github.com/OBDb/Hyundai-Tucson), revision `b1306dc2c54e32ba4344fcbb5fd098ef66db50e7`.

Source data and these adapted candidate lists are licensed under [Creative Commons Attribution-ShareAlike 4.0 International](https://creativecommons.org/licenses/by-sa/4.0/). The complete source license is bundled at `app/src/main/assets/OBDb-LICENSE.txt` and is accessible in the app's diagnostic data notices.

Changes: extracted a finite selection of header/command pairs, separated priority transmission requests, removed signal definitions/formulas/model filters, and added provenance/candidate labels. These lists serve discovery across variants and do not assert support on the target car. OBDb contributors do not endorse this app.

## Scanner generic code descriptions

`scanner/src/main/assets/dtc-generic.tsv` is adapted from **Wal33D (Waleed Judah), dtc-database**, revision [`04c43d72e7db7197658b6f72fe582c5076d9eee8`](https://github.com/Wal33D/dtc-database/tree/04c43d72e7db7197658b6f72fe582c5076d9eee8), `data/source-data/{p,b,c,u}_codes.txt`.

Copyright (c) 2024 Wal33D (Waleed Judah). MIT License. The complete copyright/permission/warranty notice is bundled in `scanner/src/main/assets/DTC-LICENSE.txt` and accessible through **Data & license** in Scanner.

Changes: filtered to generic code ranges, omitted reserved descriptions and range headings, deduplicated and sorted as a two-column UTF-8 TSV. 9,341 descriptions are included. `tools/import_scanner_codes.py` reproduces the subset from the pinned source. No other-brand manufacturer definitions or repair recommendations are bundled. These are community descriptions, not a certified Hyundai TL-specific catalog. The source authors do not endorse OBDMaru.

## Parked blinker candidate

The five `CGW1` bit positions used by Dash's parked blinker test come from **commaai/opendbc**, revision [`1bce882c6330f8cc591f368f415ae5fa241ae225`](https://github.com/commaai/opendbc/blob/1bce882c6330f8cc591f368f415ae5fa241ae225/opendbc/dbc/hyundai_2015_ccan.dbc#L903), `hyundai_2015_ccan.dbc`.

Copyright (c) 2020, Comma.ai, Inc. MIT License. The complete copyright/permission/warranty notice is bundled in `dashboard/src/main/assets/opendbc-LICENSE.txt`.

Changes: extracted raw left/right lamp, left/right stalk and hazard positions into a small candidate decoder. Raw enums are retained; no exact pre-facelift Tucson applicability or source-author endorsement is claimed.
