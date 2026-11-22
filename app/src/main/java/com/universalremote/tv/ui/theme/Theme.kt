package com.universalremote.tv.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.universalremote.tv.storage.DeviceStore

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DC2FF),
    onPrimary = Color(0xFF07203F),
    primaryContainer = Color(0xFF1C2E49),
    onPrimaryContainer = Color(0xFFD7E4FF),
    secondary = Color(0xFF63DAC4),
    onSecondary = Color(0xFF04201B),
    secondaryContainer = Color(0xFF12332D),
    onSecondaryContainer = Color(0xFFB4EFE3),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF2E1D00),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE7EBF2),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFE7EBF2),
    surfaceVariant = Color(0xFF222A34),
    onSurfaceVariant = Color(0xFFB6C0CC),
    outline = Color(0xFF3C4654),
    outlineVariant = Color(0xFF262F3A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3A0906),
    errorContainer = Color(0xFF5C1512),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE7EBF2),
    inverseOnSurface = Color(0xFF1B2027),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF255E9C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF00695C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9FF2E1),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color.White,
    background = Color(0xFFF8FAFE),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE2E7F0),
    onSurfaceVariant = Color(0xFF424A54),
    outline = Color(0xFF727A85),
    outlineVariant = Color(0xFFC4CBD6),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Allows any composable to feel taps only when the user enabled haptics. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

@Composable
fun AppTheme(
    themeMode: String,
    dynamicColor: Boolean,
    hapticsEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        DeviceStore.THEME_DARK -> true
        DeviceStore.THEME_LIGHT -> false
        else -> systemDark
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(LocalHapticsEnabled provides hapticsEnabled) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
