# Changelog

## 1.1.1 (build 19) — Stable architecture and data safety

- Зафиксирован стабильный native Android toolchain: AGP 9.3.1, Gradle 9.7.0, Kotlin 2.4.20, JDK 17, compileSdk 37, targetSdk 36, minSdk 33.
- Добавлен WAL через `setWriteAheadLoggingEnabled(true)` для обеих SQLite-баз.
- Исправлен резервный snapshot: `VACUUM INTO` выполняется на read-write соединении; добавлены busy timeout и контроль SHA-256/размера `settings.json`.
- Входящий backup теперь ограничивается по общему размеру до распаковки; усилена защита от дубликатов записей архива.
- `gradlew.bat` корректно использует Java из PATH, если JAVA_HOME не задан.
- GPS recorder полностью вынесен с главного потока на собственный `HandlerThread`; время точки берётся из GPS при наличии валидной метки.
- Добавлено автоматическое восстановление незавершённых GPS-сессий после аварийного завершения процесса.
- Исправлен GPX: ровно один закрывающий `</gpx>`.
- Добавлены регрессионные тесты и документация по проверяемым инвариантам.

## 1.1.0 (build 18)

Предыдущая стабильная Android-основа.
