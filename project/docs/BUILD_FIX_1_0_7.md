# Build Fix — migration to 1.0.8 build 17

Build 16 exposed a number of real source/dependency compatibility errors during the first Android Studio compilation. Build 17 is the corrected baseline.

## Toolchain

- Android Gradle Plugin: 9.3.1
- Gradle: 9.5.0
- Kotlin / Compose Compiler plugin: 2.4.10
- JDK: 17
- compileSdk: 37
- targetSdk: 37
- minSdk: 33
- Compose BOM: 2026.08.00
- Lifecycle: 2.11.0
- Health Connect: 1.1.0
- Glance: 1.1.1

## Source fixes

- `AppSettings` now keeps an application context for backup/restore operations.
- `AggregateRequest` uses the Health Connect 1.1.0 `request` package.
- `BootReceiver` uses `Intent.ACTION_TIME_CHANGED` for the `TIME_SET` broadcast.
- Material 3 imports are complete; current experimental APIs are explicitly opted into where necessary.
- Health Connect permission launcher types are explicit.
- Glance uses Compose `DpSize`/`dp`/`sp`; `defaultWeight()` is resolved through `RowScope` instead of an invalid top-level import.
- `StepperDb` avoids the Kotlin generic inference ambiguity around `maxOfOrNull`.

## Build

After opening the project, run Gradle Sync and then:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

The preparation environment does not contain Android SDK/Build Tools, so no false claim of a locally executed APK build is made.
