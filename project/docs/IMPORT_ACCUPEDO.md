# Импорт Accupedo

## CSV

Ожидается 8 полей:

```text
year,month,day,steps,distance_integer,distance_fraction,calories,time
```

Для присланного файла `distance = integer + fraction / 100`, например `3,56` → `3,56 км`.

Контрольный результат присланного файла:

```text
113973 steps
86.83 km
3381 kcal
16:34 active time
43 records
```

## SQLite

Перед чтением выполняется:

```sql
PRAGMA integrity_check;
PRAGMA table_info(diaries);
```

Обязательные поля:

```text
_id
year
month
day
hour
minute
steps
distance
calories
steptime
```

`diaries.steps` рассматривается как накопительный показатель: за календарный день выбирается последняя запись по `(hour, minute, _id)`.

Поэтому строки одной даты не складываются между собой.

## Идемпотентность

Каждая импортированная запись получает `source_key`, а база использует составной первичный ключ `(source, source_key)`. Повторный импорт того же файла не должен дублировать данные.

## Повреждённая база

Файл, не прошедший `integrity_check`, отклоняется без изменения рабочей базы приложения.
