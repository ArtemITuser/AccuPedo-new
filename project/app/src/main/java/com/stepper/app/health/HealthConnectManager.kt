package com.stepper.app.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import com.stepper.app.data.ImportedDay
import com.stepper.app.data.StepperDb
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Полноценный контур Health Connect: чтение внешних данных и безопасная запись
 * канонической истории приложения обратно в Health Connect.
 *
 * Собственная SQLite остаётся источником истины. Для записей, принадлежащих
 * «Шагомеру», используется clientRecordId вида stepper-day-YYYY-MM-DD, поэтому
 * повторная синхронизация не создаёт дубликаты: Health Connect разрешает записи
 * по clientRecordId/clientRecordVersion.
 */
class HealthConnectManager(private val context: Context) {
    private val client: HealthConnectClient? = runCatching {
        HealthConnectClient.getOrCreate(context)
    }.getOrNull()

    fun isAvailable(): Boolean = sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE && client != null

    fun unavailableReason(): String = when (sdkStatus(context)) {
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect требует обновления системного провайдера."
        HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect недоступен на этом устройстве."
        else -> "Health Connect недоступен."
    }

    /** Полный набор разрешений, необходимых для двусторонней синхронизации шагов. */
    fun permissions(): Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class)
    )

    /** Только чтение, если пользователь не включил экспорт. */
    fun readPermissions(): Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    suspend fun currentGrantedPermissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions().orEmpty()

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun readSteps(start: Instant, end: Instant): Long {
        val safeClient = client ?: error(unavailableReason())
        val response = safeClient.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return (response[StepsRecord.COUNT_TOTAL] ?: 0L).coerceAtLeast(0L)
    }

    /**
     * Импортирует внешний агрегат Health Connect за последние 30 дней.
     * Локальные сенсорные данные и уже импортированная история имеют приоритет.
     */
    suspend fun importLast30Days(db: StepperDb, goal: Long): HealthSyncResult {
        val safeClient = client ?: error(unavailableReason())
        val granted = currentGrantedPermissions()
        require(granted.containsAll(readPermissions())) { "Нет разрешения на чтение шагов Health Connect." }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = today.minusDays(29)
        var skipped = 0
        var nonZero = 0
        val days = ArrayList<ImportedDay>()
        var date = startDate
        while (!date.isAfter(today)) {
            val start = date.atStartOfDay(zone).toInstant()
            val end = if (date == today) Instant.now() else date.plusDays(1).atStartOfDay(zone).toInstant()
            val count = safeClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )[StepsRecord.COUNT_TOTAL] ?: 0L

            if (count > 0L) {
                nonZero++
                if (db.hasAuthoritativeLocalData(date)) {
                    skipped++
                } else {
                    days += ImportedDay(
                        date = date,
                        steps = count,
                        distanceMeters = 0.0,
                        caloriesKcal = 0.0,
                        activeSeconds = 0L,
                        source = "health_connect",
                        sourceKey = "health-connect-$date"
                    )
                }
            }
            date = date.plusDays(1)
        }

        if (days.isNotEmpty()) {
            val batchId = "health-${System.currentTimeMillis()}"
            db.addImportedDays(days, batchId, goal)
            db.recordImportBatch(
                batchId,
                com.stepper.app.data.ImportSummary(
                    source = "Health Connect",
                    rowsRead = 30,
                    rowsImported = days.size,
                    warnings = emptyList(),
                    days = days
                )
            )
        }
        return HealthSyncResult(nonZeroDays = nonZero, importedDays = days.size, skippedAuthoritativeDays = skipped)
    }

    /**
     * Экспортирует канонические дневные шаги приложения в Health Connect.
     * Каждая дата имеет стабильный clientRecordId, поэтому повторный экспорт
     * обновляет ту же запись вместо создания дубля.
     */
    suspend fun exportDays(db: StepperDb, startDate: LocalDate, endDateInclusive: LocalDate): HealthExportResult {
        val safeClient = client ?: error(unavailableReason())
        val granted = currentGrantedPermissions()
        require(granted.contains(HealthPermission.getWritePermission(StepsRecord::class))) {
            "Нет разрешения на запись шагов в Health Connect."
        }

        val zone = ZoneId.systemDefault()
        val records = ArrayList<StepsRecord>()
        val exportStates = ArrayList<StepperDb.HealthExportState>()
        var daysPrepared = 0
        var daysSkipped = 0

        var date = startDate
        while (!date.isAfter(endDateInclusive)) {
            val day = db.day(date)
            val count = day?.steps ?: 0L
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = if (date == LocalDate.now(zone)) Instant.now() else date.plusDays(1).atStartOfDay(zone).toInstant()

            if (count <= 0L || !dayEnd.isAfter(dayStart) || !db.hasExportableLocalData(date)) {
                // Не экспортируем в Health Connect данные, которые сами были
                // импортированы из Health Connect: иначе следующий агрегат
                // может содержать собственную копию этих же шагов.
                daysSkipped++
            } else {
                val version = db.healthExportVersion(date, count)
                val clientId = "stepper-day-$date"
                exportStates += StepperDb.HealthExportState(date, count, version)
                records += buildStepRecords(
                    start = dayStart,
                    end = dayEnd,
                    count = count,
                    zone = zone,
                    clientRecordId = clientId,
                    clientRecordVersion = version
                )
                daysPrepared++
            }
            date = date.plusDays(1)
        }

        if (records.isEmpty()) {
            return HealthExportResult(daysPrepared = daysPrepared, recordsWritten = 0, daysSkipped = daysSkipped)
        }

        safeClient.insertRecords(records)
        db.commitHealthExportState(exportStates)
        return HealthExportResult(daysPrepared = daysPrepared, recordsWritten = records.size, daysSkipped = daysSkipped)
    }

    /** Удаляет только собственные записи приложения из заданного диапазона. */
    suspend fun deleteOwnExportedDays(startDate: LocalDate, endDateInclusive: LocalDate): Int {
        val safeClient = client ?: error(unavailableReason())
        val granted = currentGrantedPermissions()
        require(granted.contains(HealthPermission.getWritePermission(StepsRecord::class))) {
            "Нет разрешения на запись шагов в Health Connect."
        }
        val zone = ZoneId.systemDefault()
        val start = startDate.atStartOfDay(zone).toInstant()
        val end = endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        // Временной фильтр автоматически ограничивает удаление записями,
        // принадлежащими вызывающему приложению, поэтому чужие данные не тронуты.
        safeClient.deleteRecords(
            recordType = StepsRecord::class,
            timeRangeFilter = TimeRangeFilter.between(start, end)
        )
        return Duration.between(start, end).toDays().toInt()
    }

    private fun buildStepRecords(
        start: Instant,
        end: Instant,
        count: Long,
        zone: ZoneId,
        clientRecordId: String,
        clientRecordVersion: Long
    ): List<StepsRecord> {
        require(count in 1L..1_000_000L) { "Суточное число шагов вне допустимого диапазона Health Connect." }
        require(end.isAfter(start)) { "Интервал записи Health Connect должен быть положительным." }
        return listOf(
            StepsRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                count = count,
                metadata = Metadata.unknownRecordingMethod(
                    clientRecordId = clientRecordId,
                    clientRecordVersion = clientRecordVersion,
                    device = null
                )
            )
        )
    }

    companion object {
        fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    }
}

data class HealthSyncResult(
    val nonZeroDays: Int,
    val importedDays: Int,
    val skippedAuthoritativeDays: Int
)

data class HealthExportResult(
    val daysPrepared: Int,
    val recordsWritten: Int,
    val daysSkipped: Int
)
