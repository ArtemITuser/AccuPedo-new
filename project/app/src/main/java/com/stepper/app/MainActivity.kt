package com.stepper.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.os.PowerManager
import android.app.ActivityManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.heightIn
import androidx.core.content.ContextCompat
import com.stepper.app.backup.BackupManager
import com.stepper.app.data.AppSettings
import com.stepper.app.data.DailySummary
import com.stepper.app.data.ChartPoint
import com.stepper.app.data.PeriodSummary
import com.stepper.app.data.StepperDb
import com.stepper.app.data.SensorCapabilities
import com.stepper.app.health.HealthConnectManager
import com.stepper.app.importer.AccupedoCsvImporter
import com.stepper.app.importer.AccupedoSqliteImporter
import com.stepper.app.service.StepTrackingService
import com.stepper.app.ui.theme.StepperTheme
import com.stepper.app.data.StrideProfileStore
import com.stepper.app.data.StepMath
import com.stepper.app.data.StepperLiveState
import com.stepper.app.diagnostics.AppErrorCenter
import com.stepper.app.location.LocationDb
import com.stepper.app.location.TrackExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Используем стабильный API AndroidX Core; на Android 15+ edge-to-edge включён системой,
        // а на Android 13–14 этот вызов обеспечивает одинаковое поведение.
        WindowCompat.enableEdgeToEdge(window)
        val restoreMessage = runCatching {
            BackupManager(this).applyPendingRestore()
        }.fold(
            onSuccess = { restored ->
                if (restored) {
                    AppErrorCenter.info(this, "BACKUP_RESTORE_APPLIED", "Резервная копия успешно применена при запуске")
                    "Резервная копия успешно восстановлена"
                } else null
            },
            onFailure = {
                AppErrorCenter.critical(this, "BACKUP_RESTORE_FAILED", "Не удалось применить ожидаемое восстановление при запуске", it)
                "Восстановление не применено: ${it.message ?: "неизвестная ошибка"}"
            }
        )
        val appSettings = AppSettings(this)
        var themeStyle by remember { mutableStateOf(appSettings.uiThemeStyle) }
        setContent {
            StepperTheme(style = themeStyle) {
                StepperRoot(
                    activity = this,
                    startupMessage = restoreMessage,
                    onThemeStyleChange = { style ->
                        appSettings.uiThemeStyle = style
                        themeStyle = AppSettings.normalizeThemeStyle(style)
                    },
                )
            }
        }
    }
}

private data class PendingImport(
    val summary: com.stepper.app.data.ImportSummary,
    val temporaryDbPath: String? = null
)

@Composable
private fun StepperRoot(
    activity: ComponentActivity,
    startupMessage: String? = null,
    onThemeStyleChange: (String) -> Unit = {},
) {
    val db = remember { StepperDb(activity) }
    val settings = remember { AppSettings(activity) }
    val health = remember { HealthConnectManager(activity) }
    val backup = remember { BackupManager(activity) }
    val locationDb = remember { LocationDb(activity) }
    val strideStore = remember { StrideProfileStore(activity) }
    val todayAtLaunch = remember { LocalDate.now() }
    LaunchedEffect(Unit) {
        runCatching {
            db.ensureDay(todayAtLaunch, settings.dailyGoal, java.time.ZoneId.systemDefault().id)
        }.onFailure {
            AppErrorCenter.critical(activity, "DATABASE_INIT_FAILED", "Не удалось создать запись текущего дня", it)
        }
        runCatching {
            locationDb.recoverUnfinishedSessions()
        }.onFailure {
            AppErrorCenter.error(activity, "GPS_SESSION_RECOVERY_FAILED", "Не удалось восстановить незавершённые GPS-сессии", it)
        }
        if (settings.trackingEnabled && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            runCatching { StepTrackingService.start(activity) }
                .onFailure { AppErrorCenter.critical(activity, "TRACKING_START_FAILED", "Не удалось запустить фоновый подсчёт", it) }
        }
    }
    DisposableEffect(Unit) { onDispose { db.close(); locationDb.close() } }

    var selected by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var tracking by remember { mutableStateOf(settings.trackingEnabled && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) }
    var goal by remember { mutableLongStateOf(settings.dailyGoal) }
    var message by remember { mutableStateOf(startupMessage) }
    var criticalIssues by remember { mutableStateOf(AppErrorCenter.getCriticalIssues(activity)) }
    val criticalRevision by AppErrorCenter.criticalRevision.collectAsState()
    var showGoalDialog by remember { mutableStateOf(false) }
    var showStepLengthDialog by remember { mutableStateOf(false) }
    var selectedDay by remember { mutableStateOf<DailySummary?>(null) }
    var selectedRoute by remember { mutableStateOf<LocationDb.TrackSession?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var selectedUnresolved by remember { mutableStateOf<com.stepper.app.data.UnresolvedStepEvent?>(null) }
    var locationRecording by remember { mutableStateOf(false) }
    var locationActivity by remember { mutableStateOf("walking") }
    val liveSnapshot by StepperLiveState.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    // Основные показатели «Сегодня» приходят событийно через StepperLiveState.
    // База не опрашивается каждые несколько секунд. Один раз в минуту обновляем
    // только почасовую/вторичную статистику, пока экран действительно видим.
    LaunchedEffect(activity) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                refreshKey++
            }
        }
    }
    LaunchedEffect(refreshKey) {
        val permissionGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        tracking = settings.trackingEnabled && permissionGranted
        goal = settings.dailyGoal
    }
    LaunchedEffect(criticalRevision) {
        criticalIssues = AppErrorCenter.getCriticalIssues(activity)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (allowed) {
            runCatching {
                StepTrackingService.startLocation(activity, locationActivity)
                locationRecording = true
                message = "GPS-запись прогулки включена"
            }.onFailure {
                AppErrorCenter.error(activity, "GPS_START_FAILED", "Не удалось включить GPS", it)
                message = it.message ?: "Не удалось включить GPS"
            }
        } else {
            message = "Разрешение на геопозицию не выдано"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            settings.trackingEnabled = true
            tracking = true
            runCatching { StepTrackingService.start(activity) }
                .onFailure { AppErrorCenter.error(activity, "TRACKING_START_FAILED", "Не удалось запустить подсчёт", it) }
            message = "Подсчёт шагов включён"
        } else {
            message = "Разрешение на физическую активность не выдано"
        }
    }

    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { input -> AccupedoCsvImporter.import(input) }
                    ?: error("Не удалось открыть файл")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { pendingImport = PendingImport(it) }
                    .onFailure {
                        AppErrorCenter.error(activity, "ACCUPEDO_CSV_IMPORT_FAILED", "Ошибка импорта CSV", it)
                        message = it.message ?: "Ошибка импорта CSV"
                    }
            }
        }
    }

    val importDbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val tmp = File(activity.cacheDir, "accupedo-import-${System.currentTimeMillis()}.db")
                try {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output ->
                                com.stepper.app.backup.BackupManager.copyBoundedTo(input, output, 128L * 1024L * 1024L)
                            }
                        }
                        ?: error("Не удалось открыть файл")
                    val summary = AccupedoSqliteImporter.inspectAndImport(tmp.absolutePath)
                    PendingImport(summary, tmp.absolutePath)
                } catch (t: Throwable) {
                    tmp.delete()
                    throw t
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { pendingImport = it }
                    .onFailure {
                        AppErrorCenter.error(activity, "ACCUPEDO_DB_IMPORT_FAILED", "Ошибка импорта базы Accupedo", it)
                        message = it.message ?: "Ошибка базы Accupedo"
                    }
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri)?.use { backup.export(it) } ?: error("Не удалось открыть файл для записи")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { message = "Резервная копия создана" }
                    .onFailure {
                        AppErrorCenter.error(activity, "BACKUP_EXPORT_FAILED", "Ошибка создания резервной копии", it)
                        message = it.message ?: "Ошибка резервной копии"
                    }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        StepTrackingService.stop(activity)
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { backup.stageRestore(it) } ?: error("Не удалось открыть резервную копию")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { message = "Копия проверена. Закройте приложение и запустите его заново для восстановления." }
                    .onFailure {
                        AppErrorCenter.critical(activity, "BACKUP_STAGE_FAILED", "Ошибка проверки резервной копии", it)
                        message = it.message ?: "Ошибка восстановления"
                    }
            }
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val csv = db.exportCsv()
                activity.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("Не удалось открыть файл для записи")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { message = "CSV сохранён" }
                    .onFailure {
                        AppErrorCenter.error(activity, "CSV_EXPORT_FAILED", "Ошибка экспорта CSV", it)
                        message = it.message ?: "Ошибка экспорта"
                    }
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val content = db.exportJson()
                activity.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("Не удалось открыть файл для записи")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { message = "JSON сохранён" }
                    .onFailure {
                        AppErrorCenter.error(activity, "JSON_EXPORT_FAILED", "Ошибка экспорта JSON", it)
                        message = it.message ?: "Ошибка экспорта JSON"
                    }
            }
        }
    }

    val exportTsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val content = db.exportTsv()
                activity.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("Не удалось открыть файл для записи")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { message = "TSV сохранён" }
                    .onFailure {
                        AppErrorCenter.error(activity, "TSV_EXPORT_FAILED", "Ошибка экспорта TSV", it)
                        message = it.message ?: "Ошибка экспорта TSV"
                    }
            }
        }
    }

    val exportAccupedoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val content = db.exportAccupedoCsv()
                activity.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("Не удалось открыть файл для записи")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { message = "Accupedo CSV сохранён" }
                    .onFailure {
                        AppErrorCenter.error(activity, "ACCUPEDO_EXPORT_FAILED", "Ошибка экспорта Accupedo CSV", it)
                        message = it.message ?: "Ошибка экспорта Accupedo CSV"
                    }
            }
        }
    }

    val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri)?.use { AppErrorCenter.exportLogs(activity, it) } ?: error("Не удалось открыть файл журнала для записи")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { message = "Диагностический журнал сохранён" }
                    .onFailure {
                        AppErrorCenter.error(activity, "LOG_EXPORT_FAILED", "Не удалось экспортировать журнал диагностики", it)
                        message = it.message ?: "Ошибка экспорта журнала"
                    }
            }
        }
    }

    val startLocation: () -> Unit = {
        if (!tracking) {
            message = "Сначала включите фоновый подсчёт шагов"
        } else if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching { StepTrackingService.startLocation(activity, locationActivity); locationRecording = true }
                .onFailure {
                    AppErrorCenter.error(activity, "GPS_START_FAILED", "Не удалось включить GPS", it)
                    message = it.message ?: "Не удалось включить GPS"
                }
        } else {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val stopLocation: () -> Unit = {
        runCatching { StepTrackingService.stopLocation(activity); locationRecording = false; refreshKey++ }
            .onFailure {
                AppErrorCenter.error(activity, "GPS_STOP_FAILED", "Не удалось остановить GPS", it)
                message = it.message ?: "Не удалось остановить GPS"
            }
    }
    val deleteLocationData: () -> Unit = {
        locationDb.deleteAll()
        message = "История GPS удалена; шаги и обычная статистика сохранены"
    }

    val gpxExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val session = selectedRoute
        if (uri == null || session == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val gpx = TrackExporter.toGpx(session, locationDb.track(session.id))
                activity.contentResolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray(Charsets.UTF_8)) } ?: error("Не удалось открыть файл для записи")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { message = "Маршрут экспортирован в GPX" }
                    .onFailure {
                        AppErrorCenter.error(activity, "GPX_EXPORT_FAILED", "Ошибка экспорта маршрута", it)
                        message = it.message ?: "Ошибка экспорта маршрута"
                    }
            }
        }
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult<Set<String>, Set<String>>(health.permissionContract()) { granted: Set<String> ->
        if (granted.containsAll(health.permissions())) {
            message = "Health Connect подключён"
        } else {
            message = "Разрешение Health Connect предоставлено частично"
        }
    }

    val today = LocalDate.now()
    // Перечитываем текущий день только при реальном событии/ручном изменении.
    // Само событие приходит через StateFlow, поэтому при простое Activity не
    // выполняет запросы к SQLite.
    val current = remember(today, refreshKey, liveSnapshot) {
        db.day(today) ?: DailySummary(today, 0, 0.0, 0.0, 0, false)
    }
    val todayHourly = remember(today, refreshKey) { db.hourly(today) }
    val history = remember(refreshKey) { db.history() }
    val unresolvedSteps = remember(refreshKey) { db.unresolvedStepCount() }
    val locationSessions = remember(refreshKey) { locationDb.allSessions() }
    val unresolvedEvents = remember(refreshKey) { db.unresolvedEvents(50) }
    val capabilities = remember { SensorCapabilities.detect(activity) }
    val hasStepSensor = capabilities.canCountContinuously
    val batteryState = remember(refreshKey) {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        BatteryUiState(
            optimizationActive = !pm.isIgnoringBatteryOptimizations(activity.packageName),
            powerSaveMode = pm.isPowerSaveMode,
            backgroundRestricted = if (android.os.Build.VERSION.SDK_INT >= 28) am.isBackgroundRestricted else false
        )
    }

    if (showGoalDialog) {
        GoalDialog(
            initial = goal.toString(),
            onDismiss = { showGoalDialog = false },
            onSave = { value ->
                settings.dailyGoal = value
                db.updateGoalForAllDays(settings.dailyGoal)
                goal = settings.dailyGoal
                showGoalDialog = false
                refreshKey++
                message = "Цель обновлена во всей истории"
            }
        )
    }
    if (showStepLengthDialog) {
        StepLengthDialog(
            initial = settings.stepLengthMeters,
            onDismiss = { showStepLengthDialog = false },
            onSave = { value ->
                settings.stepLengthMeters = value
                showStepLengthDialog = false
                message = "Длина шага сохранена"
            }
        )
    }

    pendingImport?.let { pending ->
        val summary = pending.summary
        AlertDialog(
            onDismissRequest = {
                pending.temporaryDbPath?.let { File(it).delete() }
                pendingImport = null
            },
            title = { Text("Проверка импорта") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sourceName(summary.source), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Строк прочитано: ${summary.rowsRead}")
                    Text("Дней: ${summary.days.size}")
                    Text("Шагов: ${formatNumber(summary.days.sumOf { it.steps })}")
                    Text("Расстояние: ${formatKm(summary.days.sumOf { it.distanceMeters })}")
                    if (summary.warnings.isNotEmpty()) {
                        Text("Предупреждений: ${summary.warnings.size}", color = MaterialTheme.colorScheme.error)
                        summary.warnings.take(3).forEach { Text("• $it", fontSize = 12.sp) }
                    }
                    Text("Источники не складываются: существующие показания датчика имеют приоритет.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val batchId = java.util.UUID.randomUUID().toString()
                            db.addImportedDays(summary.days, batchId, settings.dailyGoal)
                            db.recordImportBatch(batchId, summary)
                        }.onSuccess {
                            pending.temporaryDbPath?.let { File(it).delete() }
                            withContext(Dispatchers.Main) {
                                pendingImport = null
                                refreshKey++
                                message = "Импортировано ${summary.days.size} дней"
                            }
                        }.onFailure {
                            AppErrorCenter.error(activity, "IMPORT_COMMIT_FAILED", "Ошибка записи импортированных данных в базу", it)
                            withContext(Dispatchers.Main) { message = it.message ?: "Ошибка импорта" }
                        }
                    }
                }) { Text("Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending.temporaryDbPath?.let { File(it).delete() }
                    pendingImport = null
                }) { Text("Отмена") }
            }
        )
    }

    selectedUnresolved?.let { unresolved ->
        var targetText by remember(unresolved.eventKey) { mutableStateOf(unresolved.observedLocalDate.toString()) }
        AlertDialog(
            onDismissRequest = { selectedUnresolved = null },
            title = { Text("Восстановить ${formatNumber(unresolved.steps)} шагов") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Изменение системных часов не позволило достоверно определить календарную дату.")
                    Text("Наблюдаемая дата: ${unresolved.observedLocalDate}")
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it.take(10) },
                        label = { Text("Назначить дню (ГГГГ-ММ-ДД)") },
                        singleLine = true
                    )
                    Text("Будут восстановлены только шаги. Расстояние, калории и активность не выдумываются.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val target = LocalDate.parse(targetText)
                        db.resolveUnresolvedToDate(unresolved.eventKey, target, settings.dailyGoal)
                        selectedUnresolved = null
                        refreshKey++
                        message = "Шаги восстановлены: ${formatNumber(unresolved.steps)}"
                    }.onFailure {
                        AppErrorCenter.error(activity, "UNRESOLVED_STEP_RESOLVE_FAILED", "Не удалось восстановить шаги", it)
                        message = "Не удалось восстановить: ${it.message}"
                    }
                }) { Text("Восстановить") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        db.ignoreUnresolved(unresolved.eventKey)
                        selectedUnresolved = null
                        refreshKey++
                        message = "Событие оставлено вне истории"
                    }) { Text("Пропустить") }
                    TextButton(onClick = { selectedUnresolved = null }) { Text("Отмена") }
                }
            }
        )
    }

    selectedDay?.let { day ->
        val hourly = db.hourly(day.date)
        Dialog(onDismissRequest = { selectedDay = null }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 6.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            day.date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(sourceName(day.source), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard(formatNumber(day.steps), "шагов", Modifier.weight(1f))
                            MetricCard(formatKm(day.distanceMeters), "расстояние", Modifier.weight(1f))
                            MetricCard(formatKcal(day.caloriesKcal), "ккал", Modifier.weight(1f))
                        }
                    }
                    item {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("По часам", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(formatDuration(day.activeSeconds), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(12.dp))
                                HourlyChart(hourly)
                            }
                        }
                    }
                    item {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SummaryLine("Цель", if (day.goalReached) "Выполнена" else "${formatNumber(goal)} шагов")
                                SummaryLine("Активность", formatDuration(day.activeSeconds))
                                SummaryLine("Дата в журнале", day.date.toString())
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedDay = null }
                        ) { Text("Закрыть") }
                        Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Шагомер", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                val tabs = listOf(
                    "Сегодня" to Icons.Default.DirectionsWalk,
                    "История" to Icons.Default.History,
                    "Графики" to Icons.Default.BarChart,
                    "Маршруты" to Icons.Default.Map,
                    "Настройки" to Icons.Default.Settings
                )
                tabs.forEachIndexed { index, (title, icon) ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selected == 0) FloatingActionButton(onClick = { selected = 2 }) {
                Icon(Icons.Default.BarChart, "Графики")
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> TodayScreen(
                    day = current,
                    hourly = todayHourly,
                    goal = settings.dailyGoal,
                    tracking = tracking,
                    hasStepSensor = hasStepSensor,
                    criticalIssues = criticalIssues,
                    onClearCriticalIssues = {
                        AppErrorCenter.clearCriticalIssues(activity)
                        criticalIssues = emptyList()
                    },
                    onEnable = {
                        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                            settings.trackingEnabled = true
                            tracking = true
                            runCatching { StepTrackingService.start(activity) }
                                .onFailure { AppErrorCenter.error(activity, "TRACKING_START_FAILED", "Не удалось запустить подсчёт с главного экрана", it) }
                            message = "Подсчёт шагов включён"
                        } else {
                            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                    },
                    refreshKey = refreshKey
                )
                1 -> HistoryScreen(history, settings.dailyGoal) { selectedDay = it }
                2 -> ChartsScreen(db, today, settings.dailyGoal, refreshKey)
                3 -> RoutesScreen(
                    sessions = locationSessions,
                    onExportGpx = { session -> selectedRoute = session; gpxExportLauncher.launch("stepper-route-${session.startedUtc.toString().replace(":", "-")}.gpx") },
                    onOpenMaps = { session ->
                        val url = TrackExporter.googleMapsUrl(session, locationDb.track(session.id))
                        if (url != null) {
                            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure {
                                    AppErrorCenter.error(activity, "MAPS_OPEN_FAILED", "Не удалось открыть маршрут в Google Maps", it)
                                    message = "Google Maps не найден или не может открыть маршрут"
                                }
                        } else message = "Для маршрута недостаточно GPS-точек"
                    },
                    onRefresh = { refreshKey++ }
                )
                4 -> SettingsScreen(
                    uiThemeStyle = settings.uiThemeStyle,
                    onUiThemeStyle = onThemeStyleChange,
                    tracking = tracking,
                    goal = settings.dailyGoal,
                    stepLength = settings.stepLengthMeters,
                    onTrackingChange = { enabled ->
                        if (enabled) {
                            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                                settings.trackingEnabled = true
                                tracking = true
                                runCatching { StepTrackingService.start(activity) }
                                    .onFailure { AppErrorCenter.error(activity, "TRACKING_START_FAILED", "Не удалось запустить подсчёт из настроек", it) }
                            } else permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        } else {
                            settings.trackingEnabled = false
                            tracking = false
                            StepTrackingService.stop(activity)
                        }
                    },
                    onGoal = { showGoalDialog = true },
                    onStepLength = { showStepLengthDialog = true },
                    onHealth = { if (health.isAvailable()) healthPermissionLauncher.launch(health.permissions()) else message = health.unavailableReason() },
                    onHealthExport = {
                        if (!health.isAvailable()) {
                            message = health.unavailableReason()
                        } else {
                            scope.launch {
                                runCatching {
                                    health.exportDays(db, today.minusDays(29), today)
                                }.onSuccess { result ->
                                    refreshKey++
                                    message = "Health Connect: записано ${result.recordsWritten} записей за ${result.daysPrepared} дней"
                                }.onFailure {
                                    AppErrorCenter.error(activity, "HEALTH_EXPORT_FAILED", "Ошибка экспорта в Health Connect", it)
                                    message = "Health Connect: ${it.message ?: "ошибка экспорта"}"
                                }
                            }
                        }
                    },
                    onHealthSync = {
                        if (!health.isAvailable()) {
                            message = health.unavailableReason()
                        } else {
                            scope.launch {
                                runCatching {
                                    health.importLast30Days(db, settings.dailyGoal)
                                }.onSuccess { result ->
                                    refreshKey++
                                    message = if (result.importedDays > 0) {
                                        "Health Connect: импортировано ${result.importedDays} дней"
                                    } else if (result.nonZeroDays > 0) {
                                        "Health Connect проверен: локальные данные имеют приоритет"
                                    } else {
                                        "Health Connect: данных за последние 30 дней не найдено"
                                    }
                                }.onFailure {
                                    AppErrorCenter.error(activity, "HEALTH_SYNC_FAILED", "Ошибка синхронизации Health Connect", it)
                                    message = "Health Connect: ${it.message ?: "ошибка синхронизации"}"
                                }
                            }
                        }
                    },
                    healthAvailable = health.isAvailable(),
                    healthStatus = if (health.isAvailable()) "Чтение шагов за последние 30 дней" else health.unavailableReason(),
                    onImportCsv = { importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/octet-stream")) },
                    onImportDb = { importDbLauncher.launch(arrayOf("application/x-sqlite3", "application/vnd.sqlite3", "application/octet-stream")) },
                    onBackup = { backupLauncher.launch("stepper-${today}.stepperbackup") },
                    onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onExportCsv = { exportCsvLauncher.launch("stepper-${today}.csv") },
                    onExportJson = { exportJsonLauncher.launch("stepper-${today}.json") },
                    onExportTsv = { exportTsvLauncher.launch("stepper-${today}.tsv") },
                    onExportAccupedo = { exportAccupedoLauncher.launch("stepper-${today}-accupedo.csv") },
                    onExportLog = { exportLogLauncher.launch("stepper-diagnostics-${today}.zip") },
                    onBattery = {
                        runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            .onFailure { AppErrorCenter.error(activity, "BATTERY_SETTINGS_OPEN_FAILED", "Не удалось открыть настройки оптимизации батареи", it) }
                    },
                    onWidget = { message = "Виджет добавляется стандартным средством Android с главного экрана" },
                    locationRecording = locationRecording,
                    locationActivity = locationActivity,
                    onLocationActivity = { locationActivity = it },
                    onStartLocation = startLocation,
                    onStopLocation = stopLocation,
                    onDeleteLocationData = deleteLocationData,
                    automaticStrideCalibration = strideStore.automaticCalibrationEnabled,
                    onAutomaticStrideCalibration = { strideStore.automaticCalibrationEnabled = it },
                    hasStepSensor = hasStepSensor,
                    unresolvedSteps = unresolvedSteps,
                    unresolvedEvents = unresolvedEvents,
                    batteryOptimized = batteryState.optimizationActive,
                    batteryPowerSaveMode = batteryState.powerSaveMode,
                    batteryBackgroundRestricted = batteryState.backgroundRestricted,
                    onUnresolved = { selectedUnresolved = it }
                )
            }

            message?.let { text ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).clickable { message = null },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.inverseSurface
                ) {
                    Text(text, modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    }
}

@Composable
private fun CriticalIssuesCard(
    issues: List<AppErrorCenter.CriticalIssue>,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Требуется внимание", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("${issues.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            issues.take(3).forEach { issue ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(issue.message, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                    Text("${issue.code} · ${issue.timeUtc}", color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f), fontSize = 11.sp)
                }
            }
            if (issues.size > 3) {
                Text("Показаны последние 3 ошибки. Полная информация сохранена в журнале.", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
            }
            Text("Подробности сохранены в диагностическом журнале. Очистка этого блока не удаляет журнал.", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClear) {
                    Text("Скрыть предупреждение")
                }
            }
        }
    }
}

@Composable
private fun TodayScreen(
    day: DailySummary,
    hourly: List<Long>,
    goal: Long,
    tracking: Boolean,
    hasStepSensor: Boolean,
    criticalIssues: List<AppErrorCenter.CriticalIssue>,
    onClearCriticalIssues: () -> Unit,
    onEnable: () -> Unit,
    refreshKey: Int
) {
    val progress = (day.steps.toDouble() / goal.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
    val animatedProgress by animateFloatAsState(
        targetValue = progress.toFloat(),
        animationSpec = tween(durationMillis = 550),
        label = "goalProgress"
    )
    val left = (goal - day.steps).coerceAtLeast(0L)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        if (criticalIssues.isNotEmpty()) {
            item {
                CriticalIssuesCard(criticalIssues, onClearCriticalIssues)
            }
        }
        if (!tracking) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Подсчёт пока выключен", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (hasStepSensor) "Аппаратный счётчик шагов найден. Включите фоновый режим один раз — приложение продолжит работать после закрытия экрана."
                            else "Телефон не сообщает аппаратный счётчик шагов. Можно использовать Health Connect или импорт истории.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (hasStepSensor) {
                            Button(onClick = onEnable) { Text("Включить подсчёт") }
                        }
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Сегодня", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    val ringTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    val ringProgressColor = MaterialTheme.colorScheme.primary
                    Box(Modifier.size(245.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = 18.dp.toPx()
                            drawArc(ringTrackColor, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                            drawArc(ringProgressColor, -90f, (animatedProgress * 360.0).toFloat(), false, style = Stroke(stroke, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(formatNumber(day.steps), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                            Text("шагов", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("${(animatedProgress * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("Цель · ${formatNumber(goal)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(if (left == 0L) "Цель выполнена" else "Осталось ${formatNumber(left)} шагов", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(formatKm(day.distanceMeters), "расстояние", Modifier.weight(1f))
                MetricCard(formatKcal(day.caloriesKcal), "ккал", Modifier.weight(1f))
                MetricCard(formatDuration(day.activeSeconds), "активность", Modifier.weight(1f))
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Сегодня по часам", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        AssistChip(onClick = {}, label = { Text(if (day.goalReached) "Цель выполнена" else "В процессе") })
                    }
                    Spacer(Modifier.height(10.dp))
                    HourlyChart(hourly)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp)) {
                    Text("Источник данных", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(sourceName(day.source), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Надёжность", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (tracking && hasStepSensor) "Фоновый подсчёт активен. Данные сохраняются локально и не требуют постоянного GPS."
                        else "Сенсорный фон не активен.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Text("Текущий день обновляется автоматически при поступлении показаний сенсора.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryScreen(history: List<DailySummary>, goal: Long, onDayClick: (DailySummary) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("История", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${history.size} сохранённых дней", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        if (history.isEmpty()) item { Text("История пока пуста.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(history, key = { it.date.toString() }) { day ->
            Surface(
                modifier = Modifier.clickable { onDayClick(day) },
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(
                            if (day.goalReached) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                        ), contentAlignment = Alignment.Center
                    ) {
                        Text("${(day.steps.toDouble() / goal.coerceAtLeast(1L)).times(100).toInt().coerceAtMost(999)}%", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(day.date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${formatNumber(day.steps)} шагов · ${formatKm(day.distanceMeters)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${formatKcal(day.caloriesKcal)} · ${formatDuration(day.activeSeconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun ChartsScreen(db: StepperDb, today: LocalDate, goal: Long, refreshKey: Int) {
    var mode by remember { mutableStateOf("Шаги") }
    var period by remember { mutableStateOf("День") }
    val start = when (period) {
        "День" -> today
        "Неделя" -> today.with(DayOfWeek.MONDAY)
        "Месяц" -> today.withDayOfMonth(1)
        else -> today.withDayOfYear(1)
    }
    val end = when (period) {
        "День" -> today.plusDays(1)
        "Неделя" -> start.plusDays(7)
        "Месяц" -> start.plusMonths(1)
        else -> start.plusYears(1)
    }
    val summary = db.periodSummary(start, end, goal)
    val daily = db.dailyBetween(start, end, 5000).sortedBy { it.date }
    val points = when (period) {
        "День" -> db.hourly(today).mapIndexed { index, value -> ChartPoint("$index", value.toDouble()) }
        "Неделя" -> (0 until 7).map { offset ->
            val d = start.plusDays(offset.toLong())
            val row = daily.firstOrNull { it.date == d }
            ChartPoint(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale("ru")), metricValue(row, mode))
        }
        "Месяц" -> (1..YearMonth.from(today).lengthOfMonth()).map { dayNumber ->
            val d = start.withDayOfMonth(dayNumber)
            ChartPoint(dayNumber.toString(), metricValue(daily.firstOrNull { it.date == d }, mode))
        }
        else -> (1..12).map { month ->
            val ym = YearMonth.of(today.year, month)
            val value = daily.filter { YearMonth.from(it.date) == ym }.sumOf { metricValue(it, mode) }
            ChartPoint(ym.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale("ru")), value)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Графики", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("День", "Неделя", "Месяц", "Год").forEach {
                    FilterChip(selected = period == it, onClick = { period = it }, label = { Text(it) })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Шаги", "Расстояние", "Калории", "Активность").forEach {
                    FilterChip(selected = mode == it, onClick = { mode = it }, label = { Text(it) })
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(18.dp)) {
                    Text(periodTitle(period, today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(summaryLine(summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    StepChart(points, target = if (mode == "Шаги") goal.toDouble() else null)
                    Spacer(Modifier.height(10.dp))
                    Text("Максимум: ${formatMetric(summary.maxDaySteps, mode)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp)) {
                    Text("Сводка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    SummaryLine("Всего", formatMetric(summary.totalSteps, "Шаги"))
                    SummaryLine("Среднее за день", formatNumber(summary.averagePerCalendarDay.toLong()))
                    SummaryLine("Среднее активного дня", formatNumber(summary.averagePerActiveDay.toLong()))
                    SummaryLine("Дней с целью", "${summary.goalDays} / ${summary.calendarDays}")
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepChart(points: List<ChartPoint>, target: Double?) {
    val maxValue = max(1.0, maxOf(points.maxOfOrNull { it.value } ?: 1.0, target ?: 0.0))
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val targetColor = MaterialTheme.colorScheme.tertiary
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(250.dp)) {
        val count = points.size.coerceAtLeast(1)
        val slot = size.width / count.toFloat()
        val barWidth = (slot * 0.62f).coerceAtLeast(4f)
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (target != null) {
            val y = size.height * (1f - (target / maxValue).toFloat())
            drawLine(targetColor, Offset(0f, y), Offset(size.width, y), 2f, cap = StrokeCap.Round)
        }
        points.forEachIndexed { index, point ->
            val h = size.height * (point.value / maxValue).toFloat()
            val x = slot * index + (slot - barWidth) / 2f
            drawRoundRect(barColor, Offset(x, size.height - h), Size(barWidth, h), androidx.compose.ui.geometry.CornerRadius(10f, 10f))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.firstOrNull()?.label.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(points.getOrNull(points.size / 2)?.label.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(points.lastOrNull()?.label.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun HourlyChart(values: List<Long>) {
    val maxValue = maxOf(1L, values.maxOrNull() ?: 1L)
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val slot = size.width / 24f
        val barWidth = (slot * 0.56f).coerceAtLeast(3f)
        values.forEachIndexed { hour, value ->
            val height = size.height * (value.toFloat() / maxValue.toFloat())
            val x = slot * hour + (slot - barWidth) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("00", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text("06", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text("12", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text("18", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text("23", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun RoutesScreen(
    sessions: List<LocationDb.TrackSession>,
    onExportGpx: (LocationDb.TrackSession) -> Unit,
    onOpenMaps: (LocationDb.TrackSession) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Маршруты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("GPS используется только по явному запросу", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Sync, "Обновить") }
            }
        }
        if (sessions.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Маршрутов пока нет", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Обычный подсчёт шагов работает без GPS. Включите GPS-прогулку в настройках, только когда хотите сохранить маршрут.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            sessions.forEach { session ->
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(when (session.activity) {
                                "running" -> "Бег"
                                "brisk" -> "Быстрая ходьба"
                                else -> "Ходьба"
                            }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(session.startedUtc.atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ru"))), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(formatKm(session.distanceMeters))
                                Text("${formatNumber(session.stepCount)} шагов")
                                Text("${session.points} точек")
                            }
                            Text("Средняя точность GPS: ${if (session.averageAccuracyMeters > 0) "%.0f м".format(Locale("ru"), session.averageAccuracyMeters) else "нет данных"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextButton("Google Maps", Modifier.weight(1f)) { onOpenMaps(session) }
                                OutlinedTextButton("GPX", Modifier.weight(1f)) { onExportGpx(session) }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun OutlinedTextButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
}

private data class BatteryUiState(
    val optimizationActive: Boolean,
    val powerSaveMode: Boolean,
    val backgroundRestricted: Boolean
)

@Composable
private fun SettingsScreen(
    uiThemeStyle: String,
    onUiThemeStyle: (String) -> Unit,
    tracking: Boolean,
    goal: Long,
    stepLength: Double,
    onTrackingChange: (Boolean) -> Unit,
    onGoal: () -> Unit,
    onStepLength: () -> Unit,
    onHealth: () -> Unit,
    onHealthSync: () -> Unit,
    onHealthExport: () -> Unit,
    healthAvailable: Boolean,
    healthStatus: String,
    onImportCsv: () -> Unit,
    onImportDb: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onExportTsv: () -> Unit,
    onExportAccupedo: () -> Unit,
    onExportLog: () -> Unit,
    onBattery: () -> Unit,
    onWidget: () -> Unit,
    locationRecording: Boolean,
    locationActivity: String,
    onLocationActivity: (String) -> Unit,
    onStartLocation: () -> Unit,
    onStopLocation: () -> Unit,
    onDeleteLocationData: () -> Unit,
    automaticStrideCalibration: Boolean,
    onAutomaticStrideCalibration: (Boolean) -> Unit,
    hasStepSensor: Boolean,
    unresolvedSteps: Long,
    unresolvedEvents: List<com.stepper.app.data.UnresolvedStepEvent>,
    batteryOptimized: Boolean,
    batteryPowerSaveMode: Boolean,
    batteryBackgroundRestricted: Boolean,
    onUnresolved: (com.stepper.app.data.UnresolvedStepEvent) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Стиль интерфейса", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Material Expressive — динамические цвета Android и выразительные формы. Fluent (WinUI) — палитра и скругления в стиле Windows 11.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiThemeStyle == "material",
                            onClick = { onUiThemeStyle("material") },
                            label = { Text("Material") }
                        )
                        FilterChip(
                            selected = uiThemeStyle == "fluent",
                            onClick = { onUiThemeStyle("fluent") },
                            label = { Text("Fluent") }
                        )
                        FilterChip(
                            selected = uiThemeStyle == "system",
                            onClick = { onUiThemeStyle("system") },
                            label = { Text("System") }
                        )
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Диагностика", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Ошибки сохраняются в ограниченный журнал (не более 50 МБ). Критические ошибки показываются на главном экране.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Text(
                        "Текущий объём: ${formatFileSize(AppErrorCenter.logSizeBytes(LocalContext.current))}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = onExportLog, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Экспортировать журнал")
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Фоновый подсчёт", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (tracking) "Аппаратный счётчик работает" else "Выключен", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = tracking, onCheckedChange = onTrackingChange)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Состояние устройства", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hasStepSensor) "Аппаратный счётчик шагов найден" else "Аппаратный счётчик шагов не найден",
                        color = if (hasStepSensor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        when {
                            batteryBackgroundRestricted -> "Система ограничивает фоновую работу приложения. Это важнее обычной оптимизации батареи и может нарушить непрерывный подсчёт."
                            batteryPowerSaveMode -> "Сейчас включён режим энергосбережения системы. Он может влиять на сроки доставки фоновых обновлений."
                            batteryOptimized -> "Обычная оптимизация батареи включена. Для шагомера это предпочтительный режим: приложение не требует постоянного WakeLock и не просит без необходимости исключение из оптимизации."
                            else -> "Ограничение системной оптимизации батареи для приложения отключено. Это допустимо, но обычно не требуется."
                        },
                        color = when {
                            batteryBackgroundRestricted || batteryPowerSaveMode -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.sp
                    )
                    OutlinedButton(onClick = onBattery, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (batteryBackgroundRestricted) "Проверить фоновые ограничения" else "Открыть настройки батареи")
                    }
                    if (unresolvedSteps > 0) {
                        Text(
                            "Не распределено из-за изменения системных часов: ${formatNumber(unresolvedSteps)} шагов. Данные сохранены и не потеряны.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (unresolvedEvents.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Требуется восстановление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${formatNumber(unresolvedSteps)} шагов сохранены отдельно после изменения системных часов. Дата не была придумана автоматически.", color = MaterialTheme.colorScheme.onErrorContainer)
                        unresolvedEvents.take(5).forEach { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { onUnresolved(item) },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(formatNumber(item.steps) + " шагов", fontWeight = FontWeight.SemiBold)
                                        Text("Наблюдалось ${item.observedLocalDate}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                    Text("Восстановить", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { SettingCard("Цель шагов", "${formatNumber(goal)} шагов", Icons.Default.CalendarMonth, onGoal) }
        item { SettingCard("Длина шага", formatDistancePrecise(stepLength), Icons.Default.DirectionsWalk, onStepLength) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Автокоррекция длины шага", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Обучается только по качественным GPS-прогулкам", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = automaticStrideCalibration, onCheckedChange = onAutomaticStrideCalibration)
                    }
                    Text("Профили разделяются по ходьбе/быстрой ходьбе/бегу и части дня. Одно GPS-наблюдение меняет профиль плавно, поэтому ошибочный маршрут не ломает дистанцию.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        item { SettingCard("Health Connect", if (healthAvailable) healthStatus else "Недоступен · $healthStatus", Icons.Default.HealthAndSafety, onHealth) }
        if (healthAvailable) {
            item { SettingCard("Обновить из Health Connect", "Без сложения с собственными показаниями", Icons.Default.Sync, onHealthSync) }
            item { SettingCard("Экспортировать в Health Connect", "Собственная история записывается без дублей", Icons.Default.CloudUpload, onHealthExport) }
        }
        item { SettingCard("Импорт Accupedo CSV", "Дневная история из CSV", Icons.Default.FileUpload, onImportCsv) }
        item { SettingCard("Импорт Accupedo.db", "Оригинальная SQLite-база Accupedo", Icons.Default.FileUpload, onImportDb) }
        item { SettingCard("Резервная копия", "Проверяемый .stepperbackup", Icons.Default.Backup, onBackup) }
        item { SettingCard("Восстановить копию", "Без замены рабочей базы до проверки", Icons.Default.CloudDownload, onRestore) }
        item { SettingCard("Экспорт CSV", "Полная история из canonical-данных", Icons.Default.FileDownload, onExportCsv) }
        item { SettingCard("Экспорт JSON", "Структурированные данные для обработки", Icons.Default.FileDownload, onExportJson) }
        item { SettingCard("Экспорт TSV", "Табличный экспорт с метрическими значениями", Icons.Default.FileDownload, onExportTsv) }
        item { SettingCard("Экспорт Accupedo CSV", "Формат, совместимый с импортом Accupedo", Icons.Default.FileDownload, onExportAccupedo) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("GPS-прогулка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (locationRecording) "Запись маршрута включена" else "По умолчанию GPS не используется",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.Map, contentDescription = null)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("walking" to "Ходьба", "brisk" to "Быстро", "running" to "Бег").forEach { (key, title) ->
                            FilterChip(
                                selected = locationActivity == key,
                                onClick = { onLocationActivity(key) },
                                label = { Text(title) }
                            )
                        }
                    }
                    Button(onClick = if (locationRecording) onStopLocation else onStartLocation, modifier = Modifier.fillMaxWidth()) {
                        Text(if (locationRecording) "Остановить GPS-запись" else "Начать GPS-запись")
                    }
                    TextButton(onClick = onDeleteLocationData, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DeleteSweep, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить всю историю GPS")
                    }
                    Text("Точки маршрута хранятся в отдельной stepper_location.db. Для записи сначала должен быть включён основной подсчёт шагов. Удаление GPS не удаляет шаги, цели или обычную статистику.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        item { SettingCard("Виджет", "Адаптируется от маленького 2×1 до крупного формата", Icons.Default.Widgets, onWidget) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp)) {
                    Text("Архитектура", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Собственная база остаётся канонической. Health Connect и Accupedo считаются внешними источниками и не складываются между собой.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun SettingCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GoalDialog(initial: String, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Цель шагов") },
        text = {
            OutlinedTextField(value, { value = it.filter(Char::isDigit) }, label = { Text("шагов") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { value.toLongOrNull()?.let(onSave) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun StepLengthDialog(initial: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var value by remember { mutableStateOf("%.2f".format(Locale.US, initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Длина шага") },
        text = {
            OutlinedTextField(value, { value = it.replace(',', '.').filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("метры") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { value.toDoubleOrNull()?.let(onSave) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun metricValue(day: DailySummary?, mode: String): Double = when (mode) {
    "Расстояние" -> day?.distanceMeters ?: 0.0
    "Калории" -> day?.caloriesKcal ?: 0.0
    "Активность" -> day?.activeSeconds?.div(60.0) ?: 0.0
    else -> day?.steps?.toDouble() ?: 0.0
}

private fun periodTitle(period: String, today: LocalDate): String = when (period) {
    "День" -> today.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")))
    "Неделя" -> "Неделя ${today.with(DayOfWeek.MONDAY).format(DateTimeFormatter.ofPattern("d MMM", Locale("ru")))}"
    "Месяц" -> today.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru")))
    else -> today.year.toString()
}

private fun summaryLine(summary: PeriodSummary): String =
    "${formatNumber(summary.totalSteps)} шагов · ${formatKm(summary.totalDistanceMeters)} · ${summary.goalDays} дней с целью"

private fun formatMetric(value: Long, mode: String): String = when (mode) {
    "Расстояние" -> formatKm(value.toDouble())
    "Калории" -> "${value} ккал"
    "Активность" -> "${formatDuration(value * 60)}"
    else -> "${formatNumber(value)} шагов"
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1.0) "${(bytes / 1024.0).formatOneDecimal()} КБ" else "${mb.formatOneDecimal()} МБ"
}

private fun Double.formatOneDecimal(): String = String.format(Locale("ru"), "%.1f", this)

private fun formatNumber(value: Long): String = String.format(Locale("ru"), "%,d", value).replace(',', ' ')
private fun formatKm(meters: Double): String = String.format(Locale("ru"), "%.1f км", meters / 1000.0)
private fun formatDistancePrecise(meters: Double): String = String.format(Locale("ru"), "%.2f м", meters)
private fun formatKcal(value: Double): String = String.format(Locale("ru"), "%.0f", value)
private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "%d:%02d ч".format(h, m) else "%d мин".format(m)
}
private fun sourceName(source: String): String = when {
    source == "sensor" -> "Встроенный датчик шагов"
    source == "accupedo.csv" -> "Импорт Accupedo CSV"
    source == "accupedo.db" -> "Импорт Accupedo.db"
    source.startsWith("health") -> "Health Connect"
    source == "canonical" -> "Канонические данные"
    source == "sensor+recovered" -> "Датчик + восстановленные шаги"
    source == "recovered_time_anomaly" -> "Восстановленные шаги"
    else -> source
}
