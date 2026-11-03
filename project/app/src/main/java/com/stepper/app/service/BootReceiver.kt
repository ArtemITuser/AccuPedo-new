package com.stepper.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stepper.app.backup.BackupManager
import com.stepper.app.data.AppSettings
import com.stepper.app.diagnostics.AppErrorCenter

/**
 * Возвращает фоновый подсчёт после загрузки устройства или обновления APK.
 *
 * TIME_SET/TIMEZONE_CHANGED намеренно не используются для запуска FGS: анализ
 * времени выполняется при следующем сенсорном событии через TimeIntegrity.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val appContext = context.applicationContext
                if (BackupManager.hasPendingRestore(appContext)) return
                val settings = AppSettings(appContext)
                if (!settings.trackingEnabled) return
                runCatching { StepTrackingService.start(appContext) }
                    .onFailure {
                        AppErrorCenter.error(
                            appContext,
                            "BOOT_RESTART_FAILED",
                            "Не удалось запустить фоновый подсчёт после системного события",
                            it
                        )
                    }
            }
        }
    }
}
