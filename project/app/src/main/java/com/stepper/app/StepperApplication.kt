package com.stepper.app

import android.app.Application
import com.stepper.app.diagnostics.AppErrorCenter

/** Точка инициализации приложения. Здесь устанавливаем глобальную защиту от необработанных исключений. */
class StepperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppErrorCenter.installGlobalExceptionHandler(this)
    }
}
