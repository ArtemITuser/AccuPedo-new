from __future__ import annotations
import sqlite3
import tempfile
from pathlib import Path

schema = '''
CREATE TABLE diaries(
    _id INTEGER PRIMARY KEY,
    year INTEGER, month INTEGER, day INTEGER,
    hour INTEGER, minute INTEGER,
    steps INTEGER, distance REAL, calories REAL, steptime INTEGER
)
'''
with tempfile.TemporaryDirectory() as td:
    db_path = Path(td) / 'Accupedo.db'
    con = sqlite3.connect(db_path)
    con.executescript(schema)
    rows = [
        (1, 2026, 8, 14, 18, 00, 4000, 3.00, 120, 1800000),
        (2, 2026, 8, 14, 21, 30, 4669, 3.56, 138, 2280000),
        (3, 2026, 8, 15, 12, 00, 0, 0.00, 0, 0),
    ]
    con.executemany('INSERT INTO diaries VALUES(?,?,?,?,?,?,?,?,?,?)', rows)
    con.commit()
    con.close()

    con = sqlite3.connect(db_path)
    integrity = con.execute('PRAGMA integrity_check').fetchone()[0]
    assert integrity == 'ok'
    result = list(con.execute('''
        SELECT year, month, day, hour, minute, _id, steps, distance, calories, steptime
        FROM diaries ORDER BY year, month, day, hour, minute, _id
    '''))
    latest = {}
    for row in result:
        latest[row[0:3]] = row
    assert latest[(2026, 8, 14)][6] == 4669
    assert latest[(2026, 8, 14)][7] == 3.56
    assert latest[(2026, 8, 15)][6] == 0
    con.close()
print('ACCUPEDO DB REGRESSION: PASS')
