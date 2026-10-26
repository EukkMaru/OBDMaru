#!/usr/bin/env python3
"""Rebuild Scanner's small offline generic-description subset from a pinned MIT source.

No manufacturer-specific fallback: a foreign maker's definition must not label a Hyundai code.
The source is community-maintained, not a verified Tucson TL service catalog.
"""
from pathlib import Path
from urllib.request import urlopen
import re

REVISION = "04c43d72e7db7197658b6f72fe582c5076d9eee8"
SOURCE = f"https://raw.githubusercontent.com/Wal33D/dtc-database/{REVISION}/"
DESTINATION = Path(__file__).resolve().parents[1] / "scanner/src/main/assets"


def generic(code):
    if code[0] == "P":
        return code[1] in "02" or (code[1] == "3" and int(code[2:], 16) >= 0x400)
    return code[1] in "03"


def main():
    codes = {}
    for family in "pbcu":
        with urlopen(SOURCE + f"data/source-data/{family}_codes.txt") as response:
            source = response.read().decode("utf-8-sig")
        for line in source.splitlines():
            match = re.fullmatch(r"([PCBU][0-3][0-9A-F]{3})\s*-\s*(.+)", line.strip())
            if not match:
                raise ValueError(f"Unrecognized source row: {line!r}")
            code, description = match.groups()
            if re.fullmatch(r"[PCBU][0-3][0-9A-F]{3}", description):
                continue  # Source range heading, not a code description.
            if not generic(code) or "reserved" in description.lower():
                continue
            if code in codes and codes[code] != description:
                raise ValueError(f"Conflicting source definition: {code}")
            codes[code] = description.replace("\t", " ")
    DESTINATION.mkdir(parents=True, exist_ok=True)
    (DESTINATION / "dtc-generic.tsv").write_text(
        f"# Community generic reference; not exact-TL service data.\n# Wal33D/dtc-database {REVISION}; MIT; see DTC-LICENSE.txt\n"
        + "".join(f"{code}\t{description}\n" for code, description in sorted(codes.items())), encoding="utf-8")
    with urlopen(SOURCE + "LICENSE") as response:
        (DESTINATION / "DTC-LICENSE.txt").write_bytes(response.read())
    print(f"Imported {len(codes)} generic community descriptions")


if __name__ == "__main__":
    main()
