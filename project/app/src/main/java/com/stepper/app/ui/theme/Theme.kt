package com.stepper.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkFallback = darkColorScheme()
private val LightFallback = lightColorScheme()

@Composable
fun StepperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 && !dark -> dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = colors, content = content)
}
