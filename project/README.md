# Шагомер — Android 13+

**Версия:** 1.2.0 (build 20, preview)
**Предыдущий релиз:** 1.1.1-b19-preview (SHA-256 архива: `d64964ba75a160cbe66026029889908213dcf3757619cf9af1cadec16c22b055`)
**minSdk:** 33 (Android 13)
**compileSdk:** 37 (Android 17)
**targetSdk:** 36 (Android 15+)
**ABI приоритет:** arm64-v8a

## Стек

- Kotlin / Compose Compiler plugin 2.4.21
- Android Gradle Plugin 9.5.0
- Gradle Wrapper 9.7.0
- JDK 17
- Jetpack Compose BOM 2026.09.00
- Material 3 + Material Expressive (динамические цвета Monet, выразительные формы)
- Fluent UI / WinUI тема (акценты Windows 11, скругления 4dp) — переключаемый стиль интерфейса
- Glance 1.2.0 (виджет шагомера)
- Health Connect 1.1.0
- Android SDK Platform 37 (пакет публикуется как `platforms;android-37.0`)

AGP 9.5 требует JDK 17 и поддерживает compileSdk 37; Compose BOM 2026.09.00 соответствует Kotlin 2.4.21. Целевая версия Android 15+ (API 36) обеспечивает соответствие политикам Google Play 2026.

## CI / GitHub Actions

Workflow `.github/workflows/android-ci.yml` временно активирован на все пуши/PR (`env.ACTIVATE_ALL: "true"`) и выполняет:
- `:app:testDebugUnitTest` (unit-тесты, включая регрессионные тесты импортёра Accupedo CSV);
- `:app:assembleDebug` и `:app:assembleRelease`;
- загрузку APK как артефактов (`stepper-debug-apk`, `stepper-release-apk`).

Зелёный прогон подтверждён на ветке `feature/fluent-ui-ci` (коммит `d3dbbba`, run #8). Для отключения повременной активации установите `ACTIVATE_ALL: "false"`.

## Что нового в 1.2.0-preview

- **Fluent UI + Material Expressive**: переключаемый стиль интерфейса в Настройках («Стиль интерфейса»: Material / Fluent / System), выбор сохраняется в `AppSettings.uiThemeStyle` и включён в экспорт/импорт настроек.
- **Импортёр Accupedo CSV**: корректный RFC-4180 парсер с поддержкой кавычек и экранирования `""`; унифицированная семантика расстояния (`integer + fraction/100`); поддержка legacy-формата без кавычек; добавлены регрессионные тесты.
- **Обновление тулчейна**: AGP 9.3.1 → 9.5.0, Kotlin 2.4.20 → 2.4.21, Compose BOM 2026.08.00 → 2026.09.00; targetSdk 36 (Android 15+).
- **CI**: GitHub Actions workflow с unit-тестами и сборкой debug/release APK; bootstrap отсутствующего `gradle-wrapper.jar` на Linux/macOS.

## Сборка

Рекомендуется Android Studio Quail 4 (2026.1.4) или новее с установленным Android SDK. Для работы из Visual Studio 2026 достаточно JDK/Android SDK и терминала; сам проект является Kotlin/Gradle-проектом, не .NET/MAUI.

### SDK на Windows

Установить:

```text
JDK 17
Android SDK Platform 37
Android SDK Build-Tools 36.0.0
Android SDK Platform-Tools
Android SDK Command-line Tools
```

Android Studio может установить SDK через SDK Manager. Gradle 9.7.0 отдельно ставить не требуется: Gradle Wrapper скачает его из `services.gradle.org`.

Если `gradle-wrapper.jar` отсутствует в исходном архиве, один раз выполните:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bootstrap-gradle-wrapper.ps1
```

Скрипт должен проверять SHA-256 загружаемого файла перед установкой.

### Проверка

```powershell
.\tools\doctor-windows.ps1
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

## Архитектура

```text
TYPE_STEP_COUNTER
       ↓
StepTrackingService (FGS health)
       ↓
transaction
       ↓
stepper.db (SQLite WAL)
       ↓
sensor samples → events → hourly → daily
```

`stepper.db` — канонический источник истории. Accupedo CSV/SQLite и Health Connect нормализуются в `imported_records` и не складываются с собственными данными вслепую. GPS хранится отдельно в `stepper_location.db`.

Сенсорные данные записываются атомарно: `sensor sample → delta/event → aggregates → last sensor value → COMMIT`. Сброс счётчика после reboot различается по `bootId`. Для батчированных сенсорных кадров время события восстанавливается по `SensorEvent.timestamp` относительно `elapsedRealtime`.

## Accupedo

Поддерживаются CSV и исходная SQLite-база `Accupedo.db`. Импорт DB проверяет `PRAGMA integrity_check` и наличие обязательных колонок `diaries`; для дня используется последняя кумулятивная запись, а не сумма всех строк. Повторный импорт идентифицируется `source + source_key`.

## Экспорт и резервное копирование

Основная БД резервируется через согласованный SQLite snapshot; архив `.stepperbackup` содержит manifest, SHA-256, `stepper.db`, настройки и, при наличии, `stepper_location.db`. Восстановление сначала проверяет архив в staging, затем заменяет файлы и имеет rollback. Файловый диалог Android позволяет сохранять/читать копии локально и через Google Drive.

Экспорт истории: CSV. Экспорт GPS: GPX. Health Connect поддерживает чтение и запись StepsRecord без слепого сложения.

## Фон и батарея

Основной подсчёт не зависит от Activity. Используется foreground service типа `health`, аппаратный `TYPE_STEP_COUNTER`, отдельный `HandlerThread`, без GPS и без секундного таймера. Виджет и уведомление обновляются не чаще раза в минуту. Производительские ограничения Xiaomi/Realme/Samsung всё равно требуют проверки на физическом устройстве.

## Диагностика

`AppErrorCenter` разделяет INFO/WARNING/ERROR/CRITICAL. Критические проблемы выводятся в основном UI и сохраняются независимо от основной SQLite. Журнал ротируется: до 8 файлов по 6 MiB, то есть менее 50 MiB.

## Ограничения

Android не гарантирует наличие `TYPE_STEP_COUNTER` на каждом устройстве. При его отсутствии используется аппаратный `TYPE_STEP_DETECTOR`; если нет и его, приложение явно сообщает об отсутствии автоматического аппаратного источника. Первый запуск в середине дня не может восстановить шаги, сделанные до регистрации датчика, без внешнего источника (Health Connect/импорт).

## Что реально проверено перед релизом

В исходном окружении без Android SDK выполнены чистые регрессионные тесты математического слоя, CSV Accupedo, статический аудит исходников, проверка структуры ресурсов и тесты формата резервной копии. Полная Gradle-сборка и instrumented tests требуют установленного на Windows Android SDK.

Для стабильной сборки используйте JDK 17, Android SDK Platform 37 и Build Tools 36.0.0. Android Studio предпочтительна для Kotlin/Compose; Visual Studio 2026 можно использовать как редактор и для запуска `gradlew.bat` из терминала.
