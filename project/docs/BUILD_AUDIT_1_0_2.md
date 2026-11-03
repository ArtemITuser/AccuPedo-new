# Build audit — Шагомер 1.0.2 build 11

## Target

- Android 13+ / API 33+
- compileSdk 37
- targetSdk 37
- primary ABI: arm64-v8a (standard Android artifact can also contain other ABIs unless restricted at build time)

## Toolchain

- AGP 9.3.1
- Gradle 9.5.0
- Kotlin 2.4.10
- JDK 17
- Jetpack Compose / Material 3

Kotlin 2.4.10 is in the Kotlin 2.4 line, whose fully supported AGP range ends at 9.1.0; The project deliberately pins AGP 9.3.1 to the published fully-supported Kotlin/AGP matrix.

## Source audit

- Android manifest contains required activity-recognition, health FGS, boot, notification and optional location permissions.
- Health Connect contains both READ_STEPS and WRITE_STEPS.
- Step counter state is persisted in SQLite per boot session.
- Sensor sample, delta, aggregates and last sensor value are committed in one transaction.
- Health Connect export does not export days that were themselves imported from Health Connect; this prevents feedback loops.
- Health Connect export versions are committed only after insertRecords succeeds.
- GPS foreground mode uses HEALTH only for normal operation and HEALTH | LOCATION for explicit GPS tracking on Android 14+.
- Duplicate “Длина шага” settings card removed.
- Main UI remains non-empty because the current day is created before the first sensor event.

## Static verification performed in the build environment

- Gradle settings and module layout inspected.
- Android manifest inspected.
- Kotlin source tree inspected.
- Version consistency checked: versionName 1.0.2 / versionCode 11.
- No stale 1.0.1/build-10 values remain in Gradle configuration.
- No duplicate length-step settings card remains.

## Physical build limitation

The current execution environment does not contain Android SDK/Build Tools or a full Gradle installation, therefore an APK/Gradle test task could not be physically executed here. The source package is prepared for Android Studio with SDK 36 and JDK 17.
