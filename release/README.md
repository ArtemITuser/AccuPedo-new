# Stepper v1.2.1 (build 21, preview)

## Состав архива
- `app-release-unsigned.apk` — релизная сборка (needs signing before install)
- `SHA256SUMS.txt`

## Критический фикс этого билда
Crash-on-launch исправлен: `androidx.startup.InitializationProvider -> androidx.work.impl.WorkDatabase`.
Приложение НЕ использует WorkManager — он попадал в APK через манифесты транзитивных библиотек,
а R8 ломал его рефлексивную инициализацию Room-БД. Исправлено на уровне корневого графа зависимостей:
- `configurations.configureEach { exclude(group="androidx.work"); exclude(group="androidx.startup") }`
- `tools:node="remove"` для всех компонентов work/startup в merged manifest
- `isMinifyEnabled=false` для release (дополнительная страховка; keep-правила подготовлены)

Верификация финального APK (бинарный парсинг): 0 упоминаний androidx.work/startup в AndroidManifest.xml, класс WorkDatabase отсутствует в dex.

## Иконка
Заменена на загруженную (источник: m.media-amazon.com/images/I/418hY1ycvGL.png): полный набор mipmap + adaptive layers + monochrome.

## Установка
APK unsigned. Подпишите любой утилитой (apksigner / mobile apk-signer) перед установкой.
