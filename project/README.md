# Шагомер — Android 13+

**Версия:** 1.1.1
**Build:** 19
**minSdk:** 33
**compileSdk:** 37
**targetSdk:** 36
**ABI приоритет:** arm64-v8a

## Стек

- Kotlin 2.4.20
- Android Gradle Plugin 9.3.1
- Gradle Wrapper 9.7.0
- JDK 17
- Jetpack Compose 1.12 / BOM 2026.08.00
- Material 3 1.4.x через BOM
- Glance 1.2.0
- Health Connect 1.1.0
- Android SDK Platform 37

Kotlin 2.4.20 имеет полную совместимость с AGP до 9.3.1 и Gradle до 9.7.0; AGP 9.3 поддерживает API 37 и требует JDK 17. Compose 1.12/BOM 2026.08.00 требует compileSdk 37.

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
