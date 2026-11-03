from __future__ import annotations

import csv
from pathlib import Path

EXPECTED_ROWS = 43
EXPECTED_STEPS = 113_973
EXPECTED_DISTANCE_KM = 86.83
EXPECTED_CALORIES = 3381
EXPECTED_DURATION_SECONDS = 16 * 3600 + 34 * 60

root = Path(__file__).resolve().parents[2]
source = root.parent.parent / "Accupedo.csv"
if not source.exists():
    source = Path("/mnt/data/Accupedo.csv")

rows = []
with source.open("r", encoding="utf-8-sig", newline="") as fh:
    for row in csv.reader(fh):
        if not row:
            continue
        if len(row) != 8:
            raise AssertionError(f"Expected 8 fields, got {len(row)}: {row}")
        year, month, day = map(int, row[:3])
        steps = int(row[3])
        distance_km = int(row[4]) + int(row[5]) / 100.0
        calories = float(row[6])
        h, m = map(int, row[7].split(":"))
        rows.append((year, month, day, steps, distance_km, calories, h * 3600 + m * 60))

assert len(rows) == EXPECTED_ROWS, (len(rows), EXPECTED_ROWS)
assert sum(r[3] for r in rows) == EXPECTED_STEPS
assert abs(sum(r[4] for r in rows) - EXPECTED_DISTANCE_KM) < 1e-9
assert int(round(sum(r[5] for r in rows))) == EXPECTED_CALORIES
assert sum(r[6] for r in rows) == EXPECTED_DURATION_SECONDS
print("ACCUPEDO CSV REGRESSION: PASS")
print(f"rows={len(rows)} steps={sum(r[3] for r in rows)} distance_km={sum(r[4] for r in rows):.2f} calories={sum(r[5] for r in rows):.0f} duration_seconds={sum(r[6] for r in rows)}")
