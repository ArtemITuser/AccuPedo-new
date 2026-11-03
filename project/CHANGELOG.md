## [1.2.2] - 2026-11-03 (build 22, preview)

### Fixed
- Crash-on-launch: v1.2.1 hotfix removed the entire androidx.startup.InitializationProvider
  from the merged manifest, breaking all auto-init libraries (emoji2/profileinstaller/
  lifecycle-process) and causing an immediate new crash. The provider is now kept
  (tools:node=merge); only WorkManagerInitializer meta-data and unused WorkManager
  components are stripped. Zero androidx/work classes remain in DEX (binary verified).
- App icon fully regenerated for ALL densities (mdpi..xxxhdpi): legacy square+round
  mipmaps AND adaptive foreground/background layers, so every system surface
  (launcher, settings "About" dialog, themed icons) shows the new icon.

## [1.2.1] - 2026-11-03
### Fixed
- Crash-on-launch (release build): disabled R8 minification and added full keep-rules for androidx.startup / WorkManager / Room; stripped unused WorkManager components and InitializationProvider via manifest tools:node="remove".

### Changed
- App icon replaced with downloaded artwork: full mipmap PNG set (square + round), adaptive foreground/background layers for all densities, monochrome layer for themed icons.

# Changelog

## 1.2.0 (build 20) — preview: CI, Fluent UI / Material Expressive, импортёр Accupedo

- Выпущен как **v1.2.0-b20-preview**; versionCode 20, versionName 1.2.0.
- Добавлен GitHub Actions workflow `.github/workflows/android-ci.yml`: временно активирован
  на все пуши/PR (`env.ACTIVATE_ALL: "true"`); содержит unit-тесты, assembleDebug/assembleRelease
  и загрузку APK артефактами. Зелёный прогон подтверждён (run #8, коммит `d3dbbba`).
- `gradlew` теперь бутстрапит отсутствующий `gradle-wrapper.jar` через curl/wget на Linux/macOS
  (PowerShell остаётся запасным вариантом для Windows). Wrapper JAR закоммичен в репозиторий.
- Внедрён переключаемый стиль интерфейса (Настройки → «Стиль интерфейса»):
  - **Material** (по умолчанию) — Material Expressive: динамические цвета Monet (Android 12+),
    выразительные формы (до 36dp) и усиленная типографика;
  - **Fluent** — Fluent UI / WinUI-палитра (акценты Windows 11 #005FB8/#60CDFF), умеренные
    скругления 4dp, нейтральные поверхности;
  - **System** — стандартная схема Material 3 без динамических цветов.
- Выбор стиля сохраняется в `AppSettings.uiThemeStyle`, включён в JSON-экспорт/импорт настроек.
- Тема прокидывается через `CompositionLocalProvider` (`LocalUiThemeStyle`, `LocalIsFluentStyle`).
- Импортёр Accupedo CSV: корректный RFC-4180 парсер (кавычки, экранирование `""`), унифицированная
  семантика расстояния (`integer + fraction/100` согласно docs/IMPORT_ACCUPEDO.md), поддержка
  legacy-формата без кавычек; добавлены регрессионные тесты обоих форматов.
- Обновлён тулчейн: AGP 9.3.1 → 9.5.0, Kotlin/Compose plugin 2.4.20 → 2.4.21,
  Compose BOM 2026.08.00 → 2026.09.00, Glance 1.2.0; targetSdk 36 (Android 15+), compileSdk 37
  (платформенный пакет `platforms;android-37.0`).

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
