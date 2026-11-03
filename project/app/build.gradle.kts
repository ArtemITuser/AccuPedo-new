import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.stepper.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stepper.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 19
        versionName = "1.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { applicationIdSuffix = ".debug" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}


// Явно фиксируем JVM-цель Kotlin 17, чтобы Kotlin и Java-компиляторы использовали
// один и тот же байткод и Gradle не выдавал ошибку несовместимых target'ов.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // CenterAlignedTopAppBar и ряд Material3-композитов помечены как экспериментальные.
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Стабильный Glance 1.2.0: виджет строится из Compose-подобных компонентов.
    implementation("androidx.glance:glance:1.2.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")

    implementation("androidx.health.connect:connect-client:1.1.0")
    // Текущая стабильная ветка Play Services Location 21.x используется только
    // при явно включённой GPS-прогулке.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
