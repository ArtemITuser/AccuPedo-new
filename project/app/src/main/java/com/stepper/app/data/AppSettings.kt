package com.stepper.app.data

import android.content.Context
import org.json.JSONObject

/** Централизованные пользовательские настройки. Единицы только метрические. */
class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING, false)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING, value).apply()

    var dailyGoal: Long
        get() = prefs.getLong(KEY_GOAL, 10_000L).coerceIn(100L, 100_000L)
        set(value) = prefs.edit().putLong(KEY_GOAL, value.coerceIn(100L, 100_000L)).apply()

    var stepLengthMeters: Double
        get() = prefs.getFloat(KEY_STEP_LENGTH, 0.76f).toDouble().coerceIn(0.30, 1.50)
        set(value) = prefs.edit().putFloat(KEY_STEP_LENGTH, value.coerceIn(0.30, 1.50).toFloat()).apply()

    var bodyWeightKg: Double
        get() = prefs.getFloat(KEY_WEIGHT, 70f).toDouble().coerceIn(20.0, 300.0)
        set(value) = prefs.edit().putFloat(KEY_WEIGHT, value.coerceIn(20.0, 300.0).toFloat()).apply()


    fun toJson(): JSONObject = JSONObject().apply {
        put("trackingEnabled", trackingEnabled)
        put("dailyGoal", dailyGoal)
        put("stepLengthMeters", stepLengthMeters)
        put("bodyWeightKg", bodyWeightKg)
        put("strideProfiles", StrideProfileStore(appContext).exportJson())
    }

    fun restoreFromJson(json: JSONObject) {
        trackingEnabled = json.optBoolean("trackingEnabled", trackingEnabled)
        dailyGoal = json.optLong("dailyGoal", dailyGoal)
        stepLengthMeters = json.optDouble("stepLengthMeters", stepLengthMeters)
        bodyWeightKg = json.optDouble("bodyWeightKg", bodyWeightKg)
        json.optJSONObject("strideProfiles")?.let { StrideProfileStore(appContext).importJson(it) }
    }

    companion object {
        private const val FILE = "settings"
        private const val KEY_TRACKING = "tracking_enabled"
        private const val KEY_GOAL = "daily_goal"
        private const val KEY_STEP_LENGTH = "step_length_meters"
        private const val KEY_WEIGHT = "body_weight_kg"
    }
}
