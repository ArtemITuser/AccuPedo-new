# План проверки 1.1.1

## Offline regression

- CSV Accupedo: 43 строки, 113 973 шага, 86,83 км, 3381 ккал, 16:34.
- `StepMath`: первая и последующие дельты, reset, расстояние, активность, калории, цель.
- GPS math: нулевая длина, экватор, переход через ±180°.
- Backup format: manifest, size limits, SHA-256, duplicate-entry rejection, round-trip SQLite integrity.

## Android unit/instrumented

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lintDebug
gradlew.bat :app:assembleDebug
gradlew.bat :app:connectedDebugAndroidTest
```

## Device acceptance

1. Redmi Note 12 / Android 15: permissions, first launch, sleep, reboot, swipe-away, widgets.
2. POCO M8 Pro 5G / Android 16: same plus edge-to-edge and battery restrictions.
3. Optional Redmi 9 / Realme C25s / Galaxy A51: sensor capability and fallback.
4. GPS route: start/stop, process kill, recovery, GPX export, Google Maps URL.
5. Backup: local file, Google Drive provider, restore with and without GPS database.
