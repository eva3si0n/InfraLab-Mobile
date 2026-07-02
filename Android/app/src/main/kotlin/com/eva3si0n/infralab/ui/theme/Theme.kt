package com.eva3si0n.infralab.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback dark scheme (Android < 12, where dynamic color isn't available).
private val FallbackDark = darkColorScheme(
    primary = Color(0xFF82B4FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468B),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFBBC7DB),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E2E8),
    surface = Color(0xFF181C20),
    onSurface = Color(0xFFE0E2E8),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    error = Color(0xFFFFB4AB),
)

@Composable
fun InfraLabTheme(content: @Composable () -> Unit) {
    // Material You: derive the palette from the system wallpaper on Android 12+,
    // always the dark variant; older devices fall back to the fixed dark scheme.
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        FallbackDark
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
