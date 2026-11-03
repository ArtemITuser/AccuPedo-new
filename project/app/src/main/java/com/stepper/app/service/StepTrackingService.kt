package com.stepper.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.stepper.app.R
import com.stepper.app.data.AppSettings
import com.stepper.app.data.SensorSample
import com.stepper.app.data.StepperDb
import com.stepper.app.data.SensorCapabilities
import com.stepper.app.data.StepperLiveState
import com.stepper.app.data.TimeIntegrity
import com.stepper.app.widget.StepperWidget
import com.stepper.app.location.LocationRecorder
import com.stepper.app.data.StrideProfileStore
import com.stepper.app.diagnostics.AppErrorCenter
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Лёгкий foreground service для непрерывного TYPE_STEP_COUNTER.
 * Сенсор слушается на отдельном HandlerThread, чтобы запись SQLite никогда не
 * выполнялась на главном потоке приложения.
 */
class StepTrackingService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private lateinit var db: StepperDb
    private lateinit var settings: AppSettings
    private lateinit var bootId: String
    private lateinit var timeIntegrity: TimeIntegrity
    private var startedCorrectly = false
    private var locationRecorder: LocationRecorder? = null
    private var locationActivityKey: String = "walking"
    private var locationStarted = false
    private var lastWidgetUpdateElapsed = 0L
    private var lastDetectorEventElapsed = -1L
    private var activeSensorType: Int = Sensor.TYPE_STEP_COUNTER
    private lateinit var strideStore: StrideProfileStore

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        if (com.stepper.app.backup.BackupManager.hasPendingRestore(this)) {
            stopSelf()
            return
        }
        if (!settings.trackingEnabled) {
            stopSelf()
            return
        }

        db = StepperDb(this)
        strideStore = StrideProfileStore(this)
        timeIntegrity = TimeIntegrity(this)
        bootId = BootIdentity.current(this)
        db.registerBoot(bootId, Instant.now())

        createNotificationChannel()
        try {
            startForegroundCompat(includeLocation = false)
        } catch (t: Throwable) {
            AppErrorCenter.critical(this, "FOREGROUND_START_FAILED", "Не удалось запустить фоновую службу шагомера", t)
            stopSelf()
            return
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val capabilities = SensorCapabilities.detect(this)
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (sensor == null) {
            AppErrorCenter.error(this, "NO_STEP_SENSOR", "Подходящий аппаратный датчик шагов не найден")
            showSensorUnavailableNotification(capabilities)
            stopSelf()
            return
        }
        activeSensorType = sensor.type

        val initialDate = java.time.LocalDate.now()
        db.ensureDay(initialDate, settings.dailyGoal, ZoneId.systemDefault().id)
        db.day(initialDate)?.let { StepperLiveState.publish(it, SystemClock.elapsedRealtime()) }

        sensorThread = HandlerThread("StepperSensor").apply { start() }
        sensorHandler = Handler(sensorThread.looper)
        if (!sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                if (sensor.type == Sensor.TYPE_STEP_DETECTOR) DETECTOR_BATCH_LATENCY_US else SENSOR_BATCH_LATENCY_US,
                sensorHandler
            )) {
            AppErrorCenter.critical(this, "SENSOR_REGISTER_FAILED", "Android не зарегистрировал обработчик датчика шагов")
            sensorThread.quitSafely()
            stopSelf()
            return
        }
        startedCorrectly = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            if (event.sensor.type !in setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR) || event.values.isEmpty()) return
            if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                onStepDetectorEvent(event)
                return
            }

            val value = event.values[0].toDouble().toLong().coerceAtLeast(0L)
        val (now, elapsed) = eventTime(event)
        val zone = ZoneId.systemDefault()
        val localDate = now.atZone(zone).toLocalDate()
        val timeResult = timeIntegrity.observe(now, elapsed, bootId)

        if (timeResult.anomaly) {
            db.recordTimeAnomaly(
                timeIntegrity.newId(), bootId, timeResult.elapsedDeltaMs,
                timeResult.wallDeltaMs, timeResult.kind, timeResult.details
            )
        }

        val sample = SensorSample(
            bootId = bootId,
            sensorValue = value,
            sensorTimestampNanos = event.timestamp,
            elapsedRealtimeMillis = elapsed,
            wallClockUtc = now,
            localDate = localDate,
            timezoneId = zone.id,
            timezoneOffsetMinutes = zone.rules.getOffset(now).totalSeconds / 60
        )

        val previousState = db.lastSensorState(bootId)
        val estimatedDelta = previousState?.let { state ->
            if (value >= state.first) value - state.first else 0L
        } ?: 0L
        val elapsedSincePrevious = previousState?.second?.let { previousElapsed ->
            (elapsed - previousElapsed).coerceAtLeast(0L)
        }
        val activityKind = strideStore.activityForCadence(estimatedDelta, elapsedSincePrevious)
        val stepLength = strideStore.resolve(activityKind, now.atZone(zone).toLocalDateTime(), settings.stepLengthMeters)

        val result = db.processSensorReading(
            sample = sample,
            timeAnomaly = timeResult.anomaly,
            anomalyKind = if (timeResult.anomaly) timeResult.kind else null,
            stepLengthMeters = stepLength,
            bodyWeightKg = settings.bodyWeightKg,
            goal = settings.dailyGoal
        )

            if (result.inserted) {
                publishLiveDay(localDate, elapsed)
                if (result.deltaSteps > 0L) updateSurfacesThrottled(elapsed)
            }
        } catch (t: Throwable) {
            AppErrorCenter.critical(this, "SENSOR_PROCESSING_FAILED", "Критическая ошибка обработки показания датчика", t)
        }
    }

    private fun detectorIntervalMillis(currentElapsed: Long): Long {
        val previous = lastDetectorEventElapsed
        lastDetectorEventElapsed = currentElapsed
        return if (previous >= 0L) (currentElapsed - previous).coerceAtLeast(1L) else 20_000L
    }

    private fun eventTime(event: SensorEvent): Pair<Instant, Long> {
        val currentElapsed = SystemClock.elapsedRealtime()
        val sensorElapsed = event.timestamp.takeIf { it > 0L }?.div(1_000_000L)
        if (sensorElapsed == null) return Instant.now() to currentElapsed
        val age = (currentElapsed - sensorElapsed).coerceAtLeast(0L)
        // Не даём некорректному старому timestamp уйти слишком далеко в прошлое.
        if (age > 24L * 60L * 60L * 1000L) return Instant.now() to currentElapsed
        return Instant.now().minusMillis(age) to sensorElapsed
    }

    private fun onStepDetectorEvent(event: SensorEvent) {
        try {
            val (now, elapsed) = eventTime(event)
        val zone = ZoneId.systemDefault()
        val timeResult = timeIntegrity.observe(now, elapsed, bootId)
        if (timeResult.anomaly) {
            db.recordTimeAnomaly(
                timeIntegrity.newId(), bootId, timeResult.elapsedDeltaMs,
                timeResult.wallDeltaMs, timeResult.kind, timeResult.details
            )
        }
        val sample = SensorSample(
            bootId = bootId,
            sensorValue = 1L,
            sensorTimestampNanos = event.timestamp,
            elapsedRealtimeMillis = elapsed,
            wallClockUtc = now,
            localDate = now.atZone(zone).toLocalDate(),
            timezoneId = zone.id,
            timezoneOffsetMinutes = zone.rules.getOffset(now).totalSeconds / 60,
            source = "step_detector"
        )
        val activityKind = strideStore.activityForCadence(1L, detectorIntervalMillis(elapsed))
        val stepLength = strideStore.resolve(activityKind, now.atZone(zone).toLocalDateTime(), settings.stepLengthMeters)
        val result = db.processStepDetectorEvent(
            sample = sample,
            timeAnomaly = timeResult.anomaly,
            stepLengthMeters = stepLength,
            bodyWeightKg = settings.bodyWeightKg,
            goal = settings.dailyGoal
        )
            if (result.inserted) {
                publishLiveDay(sample.localDate, elapsed)
                updateSurfacesThrottled(elapsed)
            }
        } catch (t: Throwable) {
            AppErrorCenter.critical(this, "DETECTOR_PROCESSING_FAILED", "Критическая ошибка обработки события шагового детектора", t)
        }
    }


    private fun publishLiveDay(localDate: java.time.LocalDate, elapsed: Long) {
        runCatching {
            db.day(localDate)?.let { StepperLiveState.publish(it, elapsed) }
        }.onFailure {
            // Ошибка обновления UI-снимка не влияет на каноническую БД и
            // не должна останавливать фоновый подсчёт.
            AppErrorCenter.error(this, "LIVE_STATE_UPDATE_FAILED", "Не удалось обновить состояние интерфейса", it)
        }
    }

    private fun showSensorUnavailableNotification(capabilities: SensorCapabilities) {
        val channel = "stepper_status"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channel, "Состояние шагомера", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val detail = if (capabilities.accelerometer)
            "Есть акселерометр, но Android не предоставил счётчик/детектор шагов."
        else
            "На устройстве не найден подходящий датчик движения."
        manager.notify(1002, NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setContentTitle("Шагомер не может считать шаги")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\nИмпорт из Health Connect или ручная история по-прежнему доступны."))
            .setAutoCancel(true)
            .build())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        if (::sensorManager.isInitialized && startedCorrectly) sensorManager.unregisterListener(this)
        if (::sensorThread.isInitialized) sensorThread.quitSafely()
        if (locationStarted) {
            runCatching { stopLocationRecording() }
                .onFailure { AppErrorCenter.error(this, "LOCATION_DESTROY_STOP_FAILED", "Ошибка завершения GPS-записи", it) }
        }
        locationRecorder?.close()
        if (::db.isInitialized) db.close()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (com.stepper.app.backup.BackupManager.hasPendingRestore(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!AppSettings(this).trackingEnabled) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START_LOCATION -> startLocationRecording(intent.getStringExtra(EXTRA_ACTIVITY) ?: "walking")
            ACTION_STOP_LOCATION -> stopLocationRecording()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Свайп приложения из списка недавних не должен превращаться в отключение
        // подсчёта: при включённом режиме пользователь явно просил продолжать фон.
        // START_STICKY остаётся основным механизмом восстановления после убийства
        // процесса системой; здесь только дополнительно возвращаем сервис после
        // удаления задачи.
        if (AppSettings(this).trackingEnabled && !com.stepper.app.backup.BackupManager.hasPendingRestore(this)) {
            runCatching { StepTrackingService.start(applicationContext) }
                .onFailure { AppErrorCenter.error(this, "TASK_REMOVED_RESTART_FAILED", "Не удалось восстановить службу после удаления задачи", it) }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startLocationRecording(activityKey: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) return
        }
        if (!locationStarted) {
            try {
                startForegroundCompat(includeLocation = true)
                locationRecorder = LocationRecorder(this)
                locationActivityKey = activityKey
                locationRecorder?.start(activityKey)
                locationStarted = true
            } catch (t: Throwable) {
                AppErrorCenter.error(this, "LOCATION_START_FAILED", "Не удалось запустить запись GPS-маршрута", t)
                locationRecorder?.close()
                locationRecorder = null
                locationStarted = false
            }
        }
    }

    private fun stopLocationRecording() {
        if (!locationStarted) return
        val recorder = locationRecorder ?: return
        val provisionalEnd = Instant.now()
        val provisionalStart = recorder.currentStartedUtc()
        val stepCount = if (provisionalStart != null) db.stepsBetween(provisionalStart, provisionalEnd) else 0L
        val completed = runCatching { recorder.stop(stepCount) }.getOrNull()
        completed?.let { session ->
            val kind = when (session.activity) {
                "running" -> StrideProfileStore.ActivityKind.RUNNING
                "brisk" -> StrideProfileStore.ActivityKind.BRISK_WALKING
                else -> StrideProfileStore.ActivityKind.WALKING
            }
            val learned = strideStore.learn(
                kind = kind,
                startedAt = session.startedUtc.atZone(ZoneId.systemDefault()).toLocalDateTime(),
                steps = session.stepCount,
                distanceMeters = session.distanceMeters,
                durationSeconds = session.durationSeconds,
                averageAccuracyMeters = session.averageAccuracyMeters
            )
            if (learned != null) {
                android.util.Log.i(TAG, "Автокоррекция длины шага: $learned м")
            }
        }
        locationRecorder?.close()
        locationRecorder = null
        locationStarted = false
    }

    private fun updateSurfacesThrottled(elapsed: Long) {
        if (elapsed - lastWidgetUpdateElapsed < 60_000L) return
        lastWidgetUpdateElapsed = elapsed

        // Виджет обновляется пакетно, а не после каждого шага.
        StepperWidget.requestUpdate(this)

        // Foreground-уведомление также обновляется раз в минуту. Это даёт
        // пользователю актуальное значение, но не создаёт системное событие
        // на каждый шаг.
        val today = java.time.LocalDate.now()
        val day = db.day(today)
        val stepsText = formatStepsForNotification(day?.steps ?: 0L)
        val distanceText = formatKmForNotification(day?.distanceMeters ?: 0.0)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$stepsText шагов · $distanceText")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun formatStepsForNotification(steps: Long): String =
        String.format(java.util.Locale("ru"), "%,d", steps).replace(',', ' ')

    private fun formatKmForNotification(meters: Double): String =
        String.format(java.util.Locale("ru"), "%.1f км", meters / 1000.0)

    private fun startForegroundCompat(includeLocation: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.tracking_notification))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            if (includeLocation) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_tracking),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "step_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "StepperService"
        const val ACTION_START_LOCATION = "com.stepper.app.action.START_LOCATION"
        const val ACTION_STOP_LOCATION = "com.stepper.app.action.STOP_LOCATION"
        const val EXTRA_ACTIVITY = "activity"
        // Разрешаем аппаратному датчику накапливать события до 60 секунд:
        // это уменьшает число пробуждений без потери счётчика, потому что
        // TYPE_STEP_COUNTER является накопительным источником.
        private const val SENSOR_BATCH_LATENCY_US = 60_000_000
        // Fallback-события приходят чаще, поэтому для них ограничиваем задержку
        // пакетирования до 15 секунд. Основной TYPE_STEP_COUNTER остаётся более
        // экономичным и может накапливаться до 60 секунд.
        private const val DETECTOR_BATCH_LATENCY_US = 15_000_000

        fun start(context: android.content.Context) {
            val settings = AppSettings(context)
            if (!settings.trackingEnabled) return
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, StepTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun startLocation(context: android.content.Context, activity: String) {
            val intent = Intent(context, StepTrackingService::class.java).apply {
                action = ACTION_START_LOCATION
                putExtra(EXTRA_ACTIVITY, activity)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stopLocation(context: android.content.Context) {
            val intent = Intent(context, StepTrackingService::class.java).apply { action = ACTION_STOP_LOCATION }
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, StepTrackingService::class.java))
        }
    }
}

object BootIdentity {
    /** BOOT_COUNT различает загрузки устройства и не зависит от перезапуска процесса. */
    fun current(context: android.content.Context): String {
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(-1)
        if (bootCount >= 0) return "boot-$bootCount"

        // Надёжный запасной путь: храним монотонную отметку текущей загрузки.
        // После перезагрузки elapsedRealtime снова мал и создаётся новый UUID.
        val prefs = context.getSharedPreferences("runtime", android.content.Context.MODE_PRIVATE)
        val nowElapsed = SystemClock.elapsedRealtime()
        val storedElapsed = prefs.getLong("boot_elapsed_ms", -1L)
        val existing = prefs.getString("boot_id", null)
        if (existing != null && storedElapsed >= 0L && nowElapsed >= storedElapsed) return existing

        val id = "fallback-${UUID.randomUUID()}"
        prefs.edit().putLong("boot_elapsed_ms", nowElapsed).putString("boot_id", id).apply()
        return id
    }
}
