plugins {
    // AGP 9.5.0 — стабильная ветка с поддержкой compileSdk/targetSdk 37 (Android 17).
    id("com.android.application") version "9.5.0" apply false
    // Kotlin 2.4.21 — последний стабильный релиз, совместимый с Compose Compiler plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
