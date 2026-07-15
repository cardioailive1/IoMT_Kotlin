package com.cardioai.iomt.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Same brand navy palette used in the web dashboard.
val CardioNavyBg = Color(0xFF00142A)
val CardioNavyCard = Color(0xFF00244B)
val CardioNavyBorder = Color(0xFF1F64B2)
val CardioBlue = Color(0xFF2E9BFF)
val CardioGreen = Color(0xFF3DDC97)
val CardioRed = Color(0xFFFF5C5C)
val CardioAmber = Color(0xFFD97706)

private val CardioAIColorScheme = darkColorScheme(
    primary = CardioBlue,
    secondary = CardioGreen,
    error = CardioRed,
    background = CardioNavyBg,
    surface = CardioNavyCard,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun CardioAITheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        activity?.window?.let { window ->
            window.statusBarColor = CardioNavyBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = CardioAIColorScheme,
        content = content,
    )
}
