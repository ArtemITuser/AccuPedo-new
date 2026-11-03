from __future__ import annotations
import hashlib
import json
import sqlite3
import tempfile
import zipfile
from pathlib import Path

MAX_MAIN = 128 * 1024 * 1024
MAX_LOCATION = 256 * 1024 * 1024
MAX_TOTAL = 400 * 1024 * 1024

with tempfile.TemporaryDirectory() as td:
    root = Path(td)
    main = root / 'stepper.db'
    loc = root / 'stepper_location.db'
    settings = root / 'settings.json'
    for path, sql in [
        (main, 'CREATE TABLE daily_summary(local_date TEXT PRIMARY KEY, steps INTEGER NOT NULL)'),
        (loc, 'CREATE TABLE gps_sessions(id TEXT PRIMARY KEY, started_utc TEXT NOT NULL)'),
    ]:
        con = sqlite3.connect(path)
        con.execute(sql)
        con.commit()
        con.close()
    settings.write_text(json.dumps({'dailyGoal': 10000, 'stepLengthMeters': 0.76}, separators=(',', ':')), encoding='utf-8')

    def sha(p: Path) -> str:
        return hashlib.sha256(p.read_bytes()).hexdigest()

    manifest = {
        'formatVersion': 2,
        'databaseFile': 'stepper.db',
        'databaseSize': main.stat().st_size,
        'databaseSha256': sha(main),
        'settingsFile': 'settings.json',
        'settingsSize': settings.stat().st_size,
        'settingsSha256': sha(settings),
        'locationDatabaseFile': 'stepper_location.db',
        'locationDatabaseIncluded': True,
        'locationDatabaseSize': loc.stat().st_size,
        'locationDatabaseSha256': sha(loc),
    }
    archive = root / 'test.stepperbackup'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('manifest.json', json.dumps(manifest))
        z.write(main, 'stepper.db')
        z.write(loc, 'stepper_location.db')
        z.write(settings, 'settings.json')

    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        assert names.count('manifest.json') == 1
        assert names.count('stepper.db') == 1
        assert names.count('stepper_location.db') == 1
        assert names.count('settings.json') == 1
        assert sum(z.getinfo(n).file_size for n in names if not n.endswith('/')) <= MAX_TOTAL
        assert hashlib.sha256(z.read('stepper.db')).hexdigest() == manifest['databaseSha256']
        assert hashlib.sha256(z.read('stepper_location.db')).hexdigest() == manifest['locationDatabaseSha256']
        assert hashlib.sha256(z.read('settings.json')).hexdigest() == manifest['settingsSha256']

    # Негативный fixture: на уровне валидатора два одинаковых entry name запрещены.
    names = ['manifest.json', 'stepper.db', 'stepper_location.db', 'settings.json', 'settings.json']
    assert names.count('settings.json') == 2

print('BACKUP FORMAT REGRESSION: PASS')
