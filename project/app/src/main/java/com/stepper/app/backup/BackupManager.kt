package com.stepper.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.stepper.app.BuildConfig
import com.stepper.app.data.AppSettings
import com.stepper.app.diagnostics.AppErrorCenter
import com.stepper.app.location.LocationDb
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Версионируемый контейнер резервной копии .stepperbackup.
 *
 * Менеджер намеренно не держит открытый StepperDb: это позволяет безопасно
 * подготовить и применить восстановление до создания первого соединения с БД
 * при запуске приложения.
 */
class BackupManager(private val context: Context) {
    fun export(output: OutputStream): Long {
        val database = context.getDatabasePath(DATABASE_NAME)
        require(database.exists()) { "Локальная база ещё не создана." }

        val mainSnapshot = File.createTempFile("stepper-snapshot-", ".db", context.cacheDir)
        val locationDatabase = context.getDatabasePath(LocationDb.NAME)
        val locationSnapshot = if (locationDatabase.exists()) File.createTempFile("stepper-location-snapshot-", ".db", context.cacheDir) else null
        try {
            snapshotDatabase(database, mainSnapshot)
            locationSnapshot?.let { snapshotDatabase(locationDatabase, it) }

            val settingsJson = AppSettings(context).toJson().toString(2)
            val settingsBytes = settingsJson.toByteArray(Charsets.UTF_8)
            val manifest = JSONObject().apply {
                put("formatVersion", FORMAT_VERSION)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("createdAtUtc", Instant.now().toString())
                put("databaseFile", DATABASE_NAME)
                put("databaseSize", mainSnapshot.length())
                put("databaseSha256", sha256(mainSnapshot))
                put("settingsFile", "settings.json")
                put("settingsSize", settingsBytes.size)
                put("settingsSha256", sha256Bytes(settingsBytes))
                put("locationDatabaseFile", LocationDb.NAME)
                put("locationDatabaseIncluded", locationSnapshot != null)
                if (locationSnapshot != null) {
                    put("locationDatabaseSize", locationSnapshot.length())
                    put("locationDatabaseSha256", sha256(locationSnapshot))
                }
            }.toString(2)
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DATABASE_NAME))
                mainSnapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (locationSnapshot != null) {
                    zip.putNextEntry(ZipEntry(LocationDb.NAME))
                    locationSnapshot.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(settingsBytes)
                zip.closeEntry()
            }
            return mainSnapshot.length() + (locationSnapshot?.length() ?: 0L)
        } finally {
            mainSnapshot.delete()
            locationSnapshot?.delete()
        }
    }

    private fun snapshotDatabase(source: File, target: File) {
        val db = SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL("PRAGMA busy_timeout=15000")
            target.delete()
            val escaped = target.absolutePath.replace("'", "''")
            db.execSQL("VACUUM INTO '$escaped'")
        } finally {
            db.close()
        }
        require(integrityOk(target)) { "Не удалось создать целостный снимок БД." }
        syncFile(target)
    }

    /** Ставит проверенную копию в очередь; текущая БД не меняется. */
    fun stageRestore(input: InputStream) {
        val staging = File.createTempFile("restore-", ".stepperbackup", context.cacheDir)
        try {
            FileOutputStream(staging).use { out ->
                input.copyBoundedTo(out, MAX_TOTAL_BYTES)
                out.fd.sync()
            }
            validateBackup(staging)
            val pending = File(context.filesDir, PENDING_FILE)
            val pendingTemp = File(context.filesDir, "$PENDING_FILE.tmp")
            staging.copyTo(pendingTemp, overwrite = true)
            atomicReplace(pendingTemp, pending)
        } finally {
            staging.delete()
        }
    }

    /**
     * Применяется самым первым действием приложения, до создания StepperDb.
     * При ошибке pending-файл сохраняется для повторной попытки, а рабочая база
     * остаётся нетронутой.
     */
    fun applyPendingRestore(): Boolean {
        val pending = File(context.filesDir, PENDING_FILE)
        if (!pending.exists()) return false

        val extracted = File.createTempFile("restore-db-", ".sqlite", context.cacheDir)
        val settingsFile = File.createTempFile("restore-settings-", ".json", context.cacheDir)
        val validated = File.createTempFile("restore-validated-", ".sqlite", context.cacheDir)
        val target = context.getDatabasePath(DATABASE_NAME)
        val rollback = File(context.filesDir, "$DATABASE_NAME.before-restore")
        val locationTarget = context.getDatabasePath(LocationDb.NAME)
        val locationRollback = File(context.filesDir, "${LocationDb.NAME}.before-restore")
        val hadTarget = target.exists()
        val hadLocationTarget = locationTarget.exists()

        try {
            validateBackup(pending)
            extractDatabase(pending.inputStream(), extracted)
            extractSettings(pending.inputStream(), settingsFile)
            require(integrityOk(extracted)) { "Восстанавливаемая база не прошла integrity_check." }

            target.parentFile?.mkdirs()
            closeAndSyncCandidate(target)

            // Страховочные копии обеих БД остаются до успешной замены.
            if (target.exists()) target.copyTo(rollback, overwrite = true)
            if (locationTarget.exists()) locationTarget.copyTo(locationRollback, overwrite = true)

            extracted.copyTo(validated, overwrite = true)
            syncFile(validated)

            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            atomicReplace(validated, target)
            val locationCandidate = File(context.cacheDir, LOCATION_RESTORE_FILE)
            if (locationCandidate.exists()) {
                File(locationTarget.path + "-wal").delete()
                File(locationTarget.path + "-shm").delete()
                atomicReplace(locationCandidate, locationTarget)
                require(integrityOk(locationTarget)) { "После восстановления GPS-база не прошла integrity_check." }
            }

            // Повторно проверяем уже установленный файл.
            require(integrityOk(target)) { "После восстановления база не прошла integrity_check." }
            AppSettings(context).restoreFromJson(JSONObject(settingsFile.readText(Charsets.UTF_8)))

            pending.delete()
            rollback.delete()
            locationRollback.delete()
            return true
        } catch (t: Throwable) {
            AppErrorCenter.critical(context, "BACKUP_RESTORE_ROLLBACK", "Ошибка восстановления резервной копии; выполняется откат", t)
            // Если новый файл успел попасть на место, откатываемся к предыдущему.
            if (rollback.exists()) {
                runCatching {
                    File(target.path + "-wal").delete()
                    File(target.path + "-shm").delete()
                    rollback.copyTo(target, overwrite = true)
                    syncFile(target)
                }
                if (locationRollback.exists()) runCatching {
                    File(locationTarget.path + "-wal").delete()
                    File(locationTarget.path + "-shm").delete()
                    locationRollback.copyTo(locationTarget, overwrite = true)
                    syncFile(locationTarget)
                }
            }
            if (!hadTarget) {
                runCatching {
                    File(target.path + "-wal").delete()
                    File(target.path + "-shm").delete()
                    target.delete()
                }
            }
            if (!hadLocationTarget) {
                runCatching {
                    File(locationTarget.path + "-wal").delete()
                    File(locationTarget.path + "-shm").delete()
                    locationTarget.delete()
                }
            }
            throw t
        } finally {
            extracted.delete()
            settingsFile.delete()
            validated.delete()
            File(context.cacheDir, LOCATION_RESTORE_FILE).delete()
        }
    }

    private fun validateBackup(file: File) {
        var expectedSha: String? = null
        var expectedSize: Long? = null
        var expectedLocationSha: String? = null
        var expectedLocationSize: Long? = null
        var expectedSettingsSha: String? = null
        var expectedSettingsSize: Long? = null
        val extracted = File.createTempFile("validate-stepper-", ".db", context.cacheDir)
        File(context.cacheDir, LOCATION_RESTORE_FILE).delete()
        var totalBytes = 0L
        var manifestFound = false
        var databaseFound = false
        var settingsFound = false
        var locationFound = false
        try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    validateEntryName(entry)
                    if (entry.isDirectory) continue
                    when (entry.name) {
                        "manifest.json" -> {
                            require(!manifestFound) { "В резервной копии несколько manifest.json." }
                            manifestFound = true
                            val bytes = zip.readBounded(MAX_MANIFEST_BYTES)
                            val json = JSONObject(bytes.toString(Charsets.UTF_8))
                            val formatVersion = json.optInt("formatVersion", -1)
                            require(formatVersion == 1 || formatVersion == FORMAT_VERSION) {
                                "Неподдерживаемая версия резервной копии."
                            }
                            require(json.optString("databaseFile") == DATABASE_NAME) { "Неверное имя базы в manifest." }
                            expectedSha = json.optString("databaseSha256").takeIf { it.isNotBlank() }
                            expectedSize = json.optLong("databaseSize", -1L).takeIf { it >= 0L }
                            expectedSettingsSha = json.optString("settingsSha256").takeIf { it.isNotBlank() }
                            expectedSettingsSize = json.optLong("settingsSize", -1L).takeIf { it >= 0L }
                            expectedLocationSha = json.optString("locationDatabaseSha256").takeIf { it.isNotBlank() }
                            expectedLocationSize = json.optLong("locationDatabaseSize", -1L).takeIf { it >= 0L }
                            val included = json.opt("locationDatabaseIncluded")
                            if (included != null) {
                                require(included is Boolean) { "Некорректный флаг включения GPS-базы." }
                                if (!included) {
                                    require(expectedLocationSha == null && expectedLocationSize == null) {
                                        "Manifest содержит контрольные данные GPS-базы, хотя база объявлена отсутствующей."
                                    }
                                }
                            }
                        }
                        "settings.json" -> {
                            require(!settingsFound) { "В резервной копии несколько settings.json." }
                            settingsFound = true
                            val bytes = zip.readBounded(MAX_SETTINGS_BYTES)
                            JSONObject(bytes.toString(Charsets.UTF_8))
                            expectedSettingsSize?.let { require(bytes.size.toLong() == it) { "Размер settings.json не совпадает с manifest." } }
                            expectedSettingsSha?.let { require(sha256Bytes(bytes).equals(it, true)) { "SHA-256 settings.json не совпадает с manifest." } }
                        }
                        DATABASE_NAME -> {
                            require(!databaseFound) { "В резервной копии несколько файлов БД." }
                            databaseFound = true
                            extracted.outputStream().use { output ->
                                totalBytes += zip.copyBoundedTo(output, MAX_DATABASE_BYTES)
                            }
                        }
                        LocationDb.NAME -> {
                            require(!locationFound) { "В резервной копии несколько файлов GPS-базы." }
                            locationFound = true
                            val locationExtracted = File.createTempFile("location-restore-", ".db", context.cacheDir)
                            locationExtracted.outputStream().use { output ->
                                totalBytes += zip.copyBoundedTo(output, MAX_LOCATION_DATABASE_BYTES)
                            }
                            require(integrityOk(locationExtracted)) { "GPS-база в резервной копии повреждена." }
                            locationExtracted.renameTo(File(context.cacheDir, LOCATION_RESTORE_FILE))
                        }
                    }
                    zip.closeEntry()
                    require(totalBytes <= MAX_TOTAL_BYTES) { "Резервная копия превышает допустимый размер." }
                }
            }
            require(manifestFound) { "В резервной копии отсутствует manifest.json." }
            require(databaseFound) { "В резервной копии отсутствует $DATABASE_NAME." }
            // Старые v1-копии не содержали settings.json и остаются совместимыми.
            require(integrityOk(extracted)) { "Резервная копия повреждена." }
            expectedSize?.let { require(extracted.length() == it) { "Размер базы не совпадает с manifest." } }
            expectedSha?.let { require(sha256(extracted).equals(it, true)) { "SHA-256 не совпадает с manifest." } }
            val restoredLocation = File(context.cacheDir, LOCATION_RESTORE_FILE)
            if (restoredLocation.exists()) {
                require(integrityOk(restoredLocation)) { "GPS-база резервной копии повреждена." }
                expectedLocationSize?.let { require(restoredLocation.length() == it) { "Размер GPS-базы не совпадает с manifest." } }
                expectedLocationSha?.let { require(sha256(restoredLocation).equals(it, true)) { "SHA-256 GPS-базы не совпадает с manifest." } }
            }
        } finally {
            extracted.delete()
        }
    }

    private fun extractDatabase(input: InputStream, output: File) {
        var found = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                validateEntryName(entry)
                if (!entry.isDirectory && entry.name == DATABASE_NAME) {
                    output.outputStream().use { zip.copyBoundedTo(it, MAX_DATABASE_BYTES) }
                    found = true
                }
                zip.closeEntry()
            }
        }
        require(found) { "В резервной копии отсутствует $DATABASE_NAME." }
        syncFile(output)
    }

    private fun extractSettings(input: InputStream, output: File) {
        var found = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                validateEntryName(entry)
                if (!entry.isDirectory && entry.name == "settings.json") {
                    output.outputStream().use { zip.copyBoundedTo(it, MAX_SETTINGS_BYTES) }
                    JSONObject(output.readText(Charsets.UTF_8))
                    found = true
                }
                zip.closeEntry()
            }
        }
        // v1 backup does not contain settings; current preferences are preserved.
        if (!found) output.writeText(AppSettings(context).toJson().toString(), Charsets.UTF_8)
    }

    private fun validateEntryName(entry: ZipEntry) {
        require(entry.name == "manifest.json" || entry.name == DATABASE_NAME || entry.name == LocationDb.NAME || entry.name == "settings.json") {
            "Неизвестная запись архива: ${entry.name}"
        }
        require(!entry.name.contains("..")) { "Недопустимое имя записи архива." }
    }

    private fun checkpointWal(database: File) {
        val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { if (it.moveToFirst()) it.getString(0) }
            db.execSQL("PRAGMA synchronous=FULL")
        } finally {
            db.close()
        }
    }

    private fun closeAndSyncCandidate(database: File) {
        if (database.exists()) syncFile(database)
    }

    private fun integrityOk(file: File): Boolean {
        val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            database.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0).equals("ok", true) }
        } finally {
            database.close()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun atomicReplace(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun hasPendingRestore(context: Context): Boolean = File(context.applicationContext.filesDir, PENDING_FILE).exists()

        const val DATABASE_NAME = "stepper.db"
        const val PENDING_FILE = "restore.pending"
        const val FORMAT_VERSION = 2
        const val MAX_DATABASE_BYTES = 128L * 1024L * 1024L
        const val MAX_LOCATION_DATABASE_BYTES = 256L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 400L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        const val MAX_SETTINGS_BYTES = 64L * 1024L
        const val LOCATION_RESTORE_FILE = "restore-location.db"

        fun InputStream.readBounded(maxBytes: Long): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            copyBoundedTo(output, maxBytes)
            return output.toByteArray()
        }

        fun InputStream.copyBoundedTo(output: OutputStream, maxBytes: Long): Long =
            copyBoundedTo(this, output, maxBytes)

        fun copyBoundedTo(input: InputStream, output: OutputStream, maxBytes: Long): Long {
            require(maxBytes >= 0L) { "Ограничение размера должно быть неотрицательным." }
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                total += count
                require(total <= maxBytes) { "Входной файл превышает допустимый размер." }
                output.write(buffer, 0, count)
            }
            return total
        }
    }
}
