# Build environment

## Stable toolchain

- Android Gradle Plugin 9.3.1
- Gradle 9.7.0
- Kotlin 2.4.20 + Compose Compiler plugin
- JDK 17
- compileSdk 37
- targetSdk 36
- minSdk 33
- Compose BOM 2026.08.00
- Glance 1.2.0
- Health Connect 1.1.0

## Windows

Нужны Android SDK Platform 37, Build Tools 36.0.0, Platform-Tools и Command-line Tools. Gradle отдельно не устанавливается: Wrapper использует 9.7.0. Если в архиве ещё нет `gradle-wrapper.jar`, запустите `tools/bootstrap-gradle-wrapper.ps1`, затем `gradlew.bat`.

## Visual Studio 2026

Native Kotlin/Compose не использует MSBuild. Visual Studio 2026 можно применять как редактор и терминал; для синхронизации Kotlin/Compose рекомендуется Android Studio.

## Что скачивается автоматически

Gradle Wrapper получает Gradle 9.7.0. Android Studio/Gradle получают Maven-зависимости проекта во время синхронизации и сборки. Android SDK Platform/Build Tools не являются Gradle-зависимостями и должны быть установлены через SDK Manager.

## Важно о данной проверке

Среда подготовки проекта не содержит Android SDK и не имеет доступа к Android Maven repository, поэтому `assembleDebug` здесь не выполняется. Передача проекта считается полной на уровне исходников, конфигурации и offline-регрессий; окончательная проверка APK выполняется на Windows.
