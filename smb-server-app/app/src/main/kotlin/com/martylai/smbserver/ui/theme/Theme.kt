package com.martylai.smbserver.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary          = androidx.compose.ui.graphics.Color(0xFF0063A5),
    onPrimary        = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD1E4FF),
    secondary        = androidx.compose.ui.graphics.Color(0xFF545F71),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD8E3F8),
    surface          = androidx.compose.ui.graphics.Color(0xFFF8F9FF),
    background       = androidx.compose.ui.graphics.Color(0xFFF8F9FF)
)

private val DarkColorScheme = darkColorScheme(
    primary          = androidx.compose.ui.graphics.Color(0xFF9ECAFF),
    onPrimary        = androidx.compose.ui.graphics.Color(0xFF003258),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF00497D),
    secondary        = androidx.compose.ui.graphics.Color(0xFFBCC7DC),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF3C4758),
    surface          = androidx.compose.ui.graphics.Color(0xFF111318),
    background       = androidx.compose.ui.graphics.Color(0xFF111318)
)

@Composable
fun SmbServerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content     = content
    )
}
