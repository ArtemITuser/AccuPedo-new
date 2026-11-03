# ---------------------------------------------------------------------------
# Stepper ProGuard/R8 rules (v1.2.1)
#
# History: crash-on-launch after R8 minification —
#   RuntimeException: Unable to get provider androidx.startup.InitializationProvider
#   Caused by: Failed to create an instance of androidx.work.impl.WorkDatabase
# Root cause class: aggressive R8 optimization removed/renamed entities required
# reflectively by androidx.startup / WorkManager / Room runtime.
# ---------------------------------------------------------------------------

# Keep the androidx.startup InitializationProvider and every Initializer it
# instantiates via reflection from library AndroidManifest merging.
-keep class androidx.startup.** { *; }
-keep class * implements androidx.startup.Initializer { *; }
-dontwarn androidx.startup.**

# WorkManager: database classes, enums and FTS are created/instantiated reflectively.
-keep class androidx.work.** { *; }
-keep interface androidx.work.** { *; }
-dontwarn androidx.work.**
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Our own app code: keep public API surface used by framework callbacks
# (Application, Activity, Service, BroadcastReceiver, Glance widget receivers).
-keep class com.stepper.app.StepperApplication { *; }
-keep class com.stepper.app.MainActivity { *; }
-keep class com.stepper.app.service.** { *; }
-keep class com.stepper.app.widget.** { *; }

# Enum values()/valueOf() are used by persistence & widget state restore.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Reflection-based members used by Compose/Glance runtime.
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Health Connect client is referenced through service connections only.
-keep class androidx.health.connect.client.** { *; }
-dontwarn androidx.health.connect.client.**
