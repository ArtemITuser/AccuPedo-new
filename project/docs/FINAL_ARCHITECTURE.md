# Финальная архитектура «Шагомер» 1.1.1

## Канонический контур

`TYPE_STEP_COUNTER` → `StepTrackingService` → SQLite-транзакция → `sensor_samples` → `step_events` → `hourly_summary` → `daily_summary`.

`TYPE_STEP_DETECTOR` используется только как аппаратный fallback и записывает отдельное атомарное событие.

## Время

Для каждого sensor sample хранятся UTC wall-clock, `SensorEvent.timestamp`, `elapsedRealtime`, `bootId`, локальная дата и часовой пояс. Изменение часов не смешивается с обычным ходом времени. Сброс счётчика после перезагрузки определяется новой `bootId`.

## Хранилище

`stepper.db` — единственный канонический источник пользовательской истории шагов. `stepper_location.db` — отдельно хранимые GPS-треки. Обе базы работают с WAL и `synchronous=FULL`.

## Импорт/экспорт

Accupedo CSV и SQLite приводятся к `ImportedDay` и проходят идемпотентную запись в `imported_records`. Экспорты: CSV, JSON, TSV, Accupedo CSV, GPX и versioned `.stepperbackup`. Источники не складываются без контроля приоритета.

## Backup

Архив содержит manifest, SHA-256 и консистентные SQLite snapshots. Восстановление проходит через staging, integrity check и rollback. Архив ограничен по размеру. `settings.json` также проверяется по размеру и SHA-256.

## Фоновый режим

Activity отвечает только за UI. Подсчёт выполняет foreground service типа `health`; sensor listener находится на отдельном HandlerThread. В обычном режиме GPS выключен. Виджет и уведомление ограничены обновлением не чаще одного раза в минуту.

## GPS

GPS запускается исключительно явно пользователем. Callback-и Fused Location обслуживаются на отдельном `HandlerThread`. Незавершённые сессии после аварийной остановки восстанавливаются по сохранённым точкам.
