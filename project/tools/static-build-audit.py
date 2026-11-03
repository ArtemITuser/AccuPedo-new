from pathlib import Path
import re
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
main = root / 'app/src/main/java/com/stepper/app/MainActivity.kt'
build = root / 'app/build.gradle.kts'
root_build = root / 'build.gradle.kts'
wrapper = root / 'gradle/wrapper/gradle-wrapper.properties'
backup = root / 'app/src/main/java/com/stepper/app/backup/BackupManager.kt'
stepper_db = root / 'app/src/main/java/com/stepper/app/data/StepperDb.kt'
location_db = root / 'app/src/main/java/com/stepper/app/location/LocationDb.kt'
location_recorder = root / 'app/src/main/java/com/stepper/app/location/LocationRecorder.kt'
track_exporter = root / 'app/src/main/java/com/stepper/app/location/TrackExporter.kt'
manifest = root / 'app/src/main/AndroidManifest.xml'
widget = root / 'app/src/main/java/com/stepper/app/widget/StepperWidget.kt'

texts = {p: p.read_text(encoding='utf-8') for p in [main, build, root_build, wrapper, backup, stepper_db, location_db, location_recorder, track_exporter, manifest, widget]}
s = texts[main]
b = texts[build]
r = texts[root_build]
w = texts[wrapper]
bk = texts[backup]
sdb = texts[stepper_db]
ldb = texts[location_db]
lr = texts[location_recorder]
te = texts[track_exporter]
m = texts[manifest]
wi = texts[widget]
errors = []
checks = []

def check(name, ok, detail):
    checks.append((name, ok, detail))
    if not ok:
        errors.append(f'{name}: {detail}')

check('version', 'versionCode = 19' in b and 'versionName = "1.1.1"' in b, 'Ожидалась версия 1.1.1 build 19.')
check('agp', 'id("com.android.application") version "9.3.1"' in r, 'AGP 9.3.1 отсутствует.')
check('kotlin', 'org.jetbrains.kotlin.plugin.compose") version "2.4.20"' in r, 'Compose compiler plugin/Kotlin 2.4.20 отсутствует.')
check('sdk', 'compileSdk = 37' in b and 'targetSdk = 36' in b and 'minSdk = 33' in b, 'SDK 37/36/33 не закреплены.')
check('gradle', 'gradle-9.7.0-bin.zip' in w, 'Gradle wrapper не закреплён на 9.7.0.')
check('compose bom', 'compose-bom:2026.08.00' in b, 'Compose BOM 2026.08.00 отсутствует.')
check('glance', 'glance-appwidget:1.2.0' in b, 'Glance 1.2.0 отсутствует.')
check('health connect', 'connect-client:1.1.0' in b, 'Health Connect 1.1.0 отсутствует.')
check('chart point import', 'import com.stepper.app.data.ChartPoint' in s, 'Нет явного импорта ChartPoint.')
check('no experimental bottom sheet', 'ModalBottomSheet' not in s, 'Экспериментальный ModalBottomSheet всё ещё используется.')
check('edge-to-edge', 'WindowCompat.enableEdgeToEdge(window)' in s, 'Нет WindowCompat.enableEdgeToEdge.')
check('progress indicator', 'progress = { animatedProgress }' in s, 'Не найден именованный progress lambda.')
check('wal main', 'setWriteAheadLoggingEnabled(true)' in sdb, 'Основная SQLite БД не включает WAL.')
check('wal location', 'setWriteAheadLoggingEnabled(true)' in ldb, 'GPS SQLite БД не включает WAL.')
check('backup vacuum rw', 'SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE)' in bk, 'Backup snapshot должен использовать read-write соединение для VACUUM INTO.')
check('backup input limit', 'input.copyBoundedTo(out, MAX_TOTAL_BYTES)' in bk, 'Входящий backup не ограничен по размеру.')
check('settings hash', 'settingsSha256' in bk and 'settingsSize' in bk, 'settings.json не защищён hash/size.')
check('backup duplicate guard', 'несколько settings.json' in bk and 'несколько файлов GPS-базы' in bk, 'Нет защиты от дубликатов архивных записей.')
check('gps worker', 'HandlerThread("StepperLocation")' in lr and 'locationThread.looper' in lr, 'GPS callback не вынесен на собственный поток.')
check('unfinished gps recovery', 'recoverUnfinishedSessions' in ldb, 'Нет восстановления незавершённых GPS-сессий.')
check('gpx valid close', te.count('</gpx>') == 1 and te.count('</trk>') == 1, 'GPX содержит некорректное число закрывающих тегов.')
check('no stale time receiver', 'ACTION_TIME_SET' not in m and 'TIMEZONE_CHANGED' not in m, 'BootReceiver содержит устаревшие time broadcast.')
health_text = (root / 'app/src/main/java/com/stepper/app/health/HealthConnectManager.kt').read_text(encoding='utf-8')
check('health aggregate import', 'AggregateRequest' in health_text and 'androidx.health.connect.client.request.AggregateRequest' in health_text, 'Health Connect AggregateRequest отсутствует или импортирован неверно.')
check('export formats', all(x in sdb for x in ['fun exportCsv()', 'fun exportJson()', 'fun exportTsv()', 'fun exportAccupedoCsv()']), 'Не все форматы экспорта присутствуют.')
importer_text = (root / 'app/src/main/java/com/stepper/app/importer/AccupedoImporter.kt').read_text(encoding='utf-8')
check('imports', 'object AccupedoCsvImporter' in importer_text and 'object AccupedoSqliteImporter' in importer_text, 'Оба Accupedo импортёра должны присутствовать.')
check('widget responsive', 'SizeMode.Responsive' in wi and 'DpSize(110.dp, 56.dp)' in wi, 'Responsive-виджет не найден.')
check('no workmanager', 'work-runtime' not in b, 'Неиспользуемая зависимость WorkManager всё ещё присутствует.')
check('no todo', not any('TODO' in v or 'FIXME' in v for v in texts.values()), 'В production исходниках остались TODO/FIXME.')

# A lightweight structural check for Kotlin files: count lexical delimiters while ignoring
# comments and strings. It is intentionally conservative and supplements the compiler.
def balanced_kotlin(text: str) -> bool:
    pairs = {'{': '}', '(': ')', '[': ']'}
    stack = []
    i = 0
    state = 'code'
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ''
        if state == 'code':
            if ch == '/' and nxt == '/': state = 'line'; i += 2; continue
            if ch == '/' and nxt == '*': state = 'block'; i += 2; continue
            if ch == '"': state = 'string'; i += 1; continue
            if ch == "'": state = 'char'; i += 1; continue
            if ch in pairs: stack.append(pairs[ch])
            elif ch in '})]':
                if not stack or stack.pop() != ch: return False
            i += 1
        elif state == 'line':
            if ch == '\n': state = 'code'
            i += 1
        elif state == 'block':
            if ch == '*' and nxt == '/': state = 'code'; i += 2
            else: i += 1
        elif state == 'string':
            if ch == '\\': i += 2
            elif ch == '"': state = 'code'; i += 1
            else: i += 1
        else:
            if ch == '\\': i += 2
            elif ch == "'": state = 'code'; i += 1
            else: i += 1
    return state == 'code' and not stack

for path, text in texts.items():
    if path.suffix == '.kt':
        check(f'balanced Kotlin {path.name}', balanced_kotlin(text), 'Нарушена балансировка Kotlin-разделителей.')

check('notification formatter unique',
      (root / 'app/src/main/java/com/stepper/app/service/StepTrackingService.kt').read_text(encoding='utf-8').count('private fun formatStepsForNotification') == 1,
      'Ожидалось ровно одно определение formatStepsForNotification.')

for name, ok, detail in checks:
    print('[{}] {}: {}'.format('OK' if ok else 'FAIL', name, detail))
if errors:
    print('\nFAILURES:')
    for e in errors:
        print(e)
    sys.exit(1)
print('\nSTATIC BUILD AUDIT: PASS')
