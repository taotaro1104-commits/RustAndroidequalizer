package dev.rusteq.ui

import android.app.Activity
import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

internal object AudioColors {
    val Background = Color(0xFFF3F6FC)
    val Ink = Color(0xFF202B47)
    val Muted = Color(0xFF5B6780)
    val Blue = Color(0xFF4056CE)
    val BlueTint = Color(0xFFE9EDFF)
    val Bass = Color(0xFFA74B17)
    val BassTint = Color(0xFFFFF0E4)
    val Green = Color(0xFF246D58)
    val GreenTint = Color(0xFFE3F3EC)
    val Line = Color(0xFFD7DEED)
}

private val HeadingFont = FontFamily(Typeface.create("sans-serif-rounded", Typeface.NORMAL))
private val AudioTypography = Typography(
    headlineLarge = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun AudioTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = AudioColors.Background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.White.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AudioColors.Blue, onPrimary = Color.White,
            primaryContainer = AudioColors.BlueTint, onPrimaryContainer = AudioColors.Blue,
            secondary = AudioColors.Green, onSecondary = Color.White,
            secondaryContainer = AudioColors.GreenTint, onSecondaryContainer = AudioColors.Green,
            tertiary = AudioColors.Bass, onTertiary = Color.White,
            background = AudioColors.Background, onBackground = AudioColors.Ink,
            surface = Color.White, onSurface = AudioColors.Ink,
            surfaceVariant = AudioColors.Background, onSurfaceVariant = AudioColors.Muted,
            surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
            surfaceContainerHighest = AudioColors.Background,
            surfaceContainerLow = AudioColors.Background, surfaceContainerLowest = Color.White,
            outline = AudioColors.Muted, outlineVariant = AudioColors.Line,
        ),
        typography = AudioTypography,
        content = content,
    )
}
