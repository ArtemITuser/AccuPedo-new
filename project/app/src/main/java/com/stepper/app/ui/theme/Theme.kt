package com.stepper.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stepper.app.data.AppSettings

/** Доступный в composable-коде текущий стиль темы: "material" | "fluent" | "system". */
val LocalUiThemeStyle = staticCompositionLocalOf { "material" }

/** Маркер стиля форм-фактора для компонентов (скругления Fluent vs Material Expressive). */
val LocalIsFluentStyle = compositionLocalOf { false }

// ---------------------------------------------------------------------------
// Fluent UI / WinUI палитра (эталонные акценты Windows 11).
// ---------------------------------------------------------------------------
private val FluentAccentLight = Color(0xFF005FB8) // accent for light backgrounds
private val FluentAccentDark = Color(0xFF60CDFF)  // accent on dark surfaces
private val FluentBgLight = Color(0xFFF3F3F3)
private val FluentSurfaceLight = Color(0xFFFFFFFF)
private val FluentStrokeLight = Color(0xFFE5E5E5)
private val FluentTextPrimaryLight = Color(0xFF1B1B1B)
private val FluentTextSecondaryLight = Color(0xFF5F5F5F)
private val FluentBgDark = Color(0xFF202020)
private val FluentSurfaceDark = Color(0xFF2C2C2C)
private val FluentStrokeDark = Color(0xFF393939)
private val FluentTextPrimaryDark = Color(0xFFFAFAFA)
private val FluentTextSecondaryDark = Color(0xFFCFCFCF)

private fun fluentLightScheme() = lightColorScheme(
    primary = FluentAccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE4F7),
    onPrimaryContainer = Color(0xFF003366),
    secondary = Color(0xFF4A5A6A),
    onSecondary = Color.White,
    background = FluentBgLight,
    onBackground = FluentTextPrimaryLight,
    surface = FluentSurfaceLight,
    onSurface = FluentTextPrimaryLight,
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = FluentTextSecondaryLight,
    outline = FluentStrokeLight,
    outlineVariant = Color(0xFFD8D8D8),
    error = Color(0xFFC42B1C),
    onError = Color.White,
    surfaceContainerLowest = Color(0xFFFFFBFE),
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = FluentBgLight,
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE5E5E5),
)

private fun fluentDarkScheme() = darkColorScheme(
    primary = FluentAccentDark,
    onPrimary = Color(0xFF062F4F),
    primaryContainer = Color(0xFF1B3A57),
    onPrimaryContainer = Color(0xFFCDE7FF),
    secondary = Color(0xFFA8B5C2),
    onSecondary = Color(0xFF1D242B),
    background = FluentBgDark,
    onBackground = FluentTextPrimaryDark,
    surface = FluentSurfaceDark,
    onSurface = FluentTextPrimaryDark,
    surfaceVariant = Color(0xFF323232),
    onSurfaceVariant = FluentTextSecondaryDark,
    outline = FluentStrokeDark,
    outlineVariant = Color(0xFF444444),
    error = Color(0xFFFF99A4),
    onError = Color(0xFF5B1410),
    surfaceContainerLowest = Color(0xFF191919),
    surfaceContainerLow = Color(0xFF232323),
    surfaceContainer = FluentBgDark,
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
)

// ---------------------------------------------------------------------------
// Material Expressive: выразительная палитра на базе динамических цветов
// Android 12+ (Monet); fallback — эталонная фиолетовая пара M3.
// ---------------------------------------------------------------------------
private val ExpressiveFallbackLight = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
)

private val ExpressiveFallbackDark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
)

// Fluent WinUI: умеренные скругления 4dp (эталон WinUI Card/Control).
private val FluentShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
)

// Material Expressive: крупные «экспрессивные» формы.
private val ExpressiveShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

private val ExpressiveTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun StepperTheme(
    style: String = AppSettings.normalizeThemeStyle(null),
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colors = when (style) {
        "fluent" -> if (dark) fluentDarkScheme() else fluentLightScheme()
        "system" -> if (dark) darkColorScheme() else lightColorScheme()
        else -> when { // "material" — Material Expressive поверх динамической палитры
            dynamicAvailable && dark -> dynamicDarkColorScheme(context)
            dynamicAvailable && !dark -> dynamicLightColorScheme(context)
            dark -> ExpressiveFallbackDark
            else -> ExpressiveFallbackLight
        }
    }

    val shapes = if (style == "fluent") FluentShapes else ExpressiveShapes
    val typography = if (style == "fluent") Typography() else ExpressiveTypography

    CompositionLocalProvider(
        LocalUiThemeStyle provides style,
        LocalIsFluentStyle provides (style == "fluent"),
    ) {
        MaterialTheme(
            colorScheme = colors,
            shapes = shapes,
            typography = typography,
            content = content,
        )
    }
}
