# Changelog

## Unreleased — CI + UI-темы (Fluent / Material Expressive)

- Добавлен GitHub Actions workflow `.github/workflows/android-ci.yml`: временно активирован
  на все пуши/PR (`env.ACTIVATE_ALL: "true"`); содержит unit-тесты, assembleDebug/assembleRelease
  и загрузку APK артефактами.
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
