package com.stepper.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.LocalSize
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stepper.app.R
import com.stepper.app.data.AppSettings
import com.stepper.app.data.StepperDb
import java.time.LocalDate
import java.util.Locale

/**
 * Один адаптивный виджет вместо нескольких почти одинаковых.
 * 2×1 показывает шаги + расстояние, 2×2 добавляет цель и процент,
 * крупные размеры — ещё и активность/калории.
 */
class StepperWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 56.dp),
            DpSize(110.dp, 110.dp),
            DpSize(250.dp, 110.dp),
            DpSize(250.dp, 180.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = StepperDb(context)
        val day = try {
            db.day(LocalDate.now())
        } finally {
            db.close()
        }
        val settings = AppSettings(context)
        val steps = day?.steps ?: 0L
        val distanceKm = day?.distanceMeters?.div(1000.0) ?: 0.0
        val calories = day?.caloriesKcal ?: 0.0
        val activeMinutes = (day?.activeSeconds ?: 0L) / 60L
        val progress = (steps.toDouble() / settings.dailyGoal).coerceIn(0.0, 1.0)
        val size = LocalSize.current

        provideContent {
            val large = size.width >= 200.dp
            val tall = size.height >= 95.dp
            val background = R.color.widget_background
            Box(GlanceModifier.fillMaxSize().background(background).padding(if (large) 16.dp else 12.dp)) {
                when {
                    !tall -> Column(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Text("${formatNumber(steps)} шагов", style = TextStyle(fontSize = 18.sp))
                        Text(formatDistance(distanceKm), style = TextStyle(fontSize = 12.sp))
                    }
                    large && tall -> Column {
                        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("Шагомер", style = TextStyle(fontSize = 15.sp))
                                Spacer(GlanceModifier.height(3.dp))
                                Text("${formatNumber(steps)}", style = TextStyle(fontSize = 27.sp))
                                Text("шагов", style = TextStyle(fontSize = 12.sp))
                            }
                            Column(horizontalAlignment = Alignment.Horizontal.End) {
                                Text(formatDistance(distanceKm), style = TextStyle(fontSize = 14.sp))
                                Text("${(progress * 100).toInt()}%", style = TextStyle(fontSize = 12.sp))
                            }
                        }
                        if (size.height >= 160.dp) {
                            Spacer(GlanceModifier.height(10.dp))
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                Text("${formatNumber(settings.dailyGoal)} цель", style = TextStyle(fontSize = 12.sp))
                                Spacer(GlanceModifier.width(12.dp))
                                Text("$activeMinutes мин", style = TextStyle(fontSize = 12.sp))
                                Spacer(GlanceModifier.width(12.dp))
                                Text("${calories.toInt()} ккал", style = TextStyle(fontSize = 12.sp))
                            }
                        }
                    }
                    else -> Column {
                        Text("Шагомер", style = TextStyle(fontSize = 15.sp))
                        Spacer(GlanceModifier.height(4.dp))
                        Text("${formatNumber(steps)} шагов", style = TextStyle(fontSize = 21.sp))
                        Spacer(GlanceModifier.height(3.dp))
                        Text("${formatDistance(distanceKm)} · ${(progress * 100).toInt()}%", style = TextStyle(fontSize = 13.sp))
                    }
                }
            }
        }
    }

    private fun formatNumber(value: Long): String = String.format(Locale("ru"), "%,d", value).replace(',', ' ')
    private fun formatDistance(value: Double): String = String.format(Locale("ru"), "%.1f км", value)

    companion object {
        /** Пакетно обновляет экземпляры; не вызывается чаще одного раза в минуту сервисом. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, StepperWidgetReceiver::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(component)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}

class StepperWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepperWidget()
}
