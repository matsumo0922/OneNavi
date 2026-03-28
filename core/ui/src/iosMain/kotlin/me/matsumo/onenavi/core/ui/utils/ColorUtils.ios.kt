package me.matsumo.onenavi.core.ui.utils

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.rememberDynamicColorScheme

@Composable
actual fun rememberColorScheme(
    useDynamicColor: Boolean,
    seedColor: Color,
    isDark: Boolean,
): ColorScheme = rememberDynamicColorScheme(
    seedColor = seedColor,
    isDark = isDark,
)

actual val isSupportDynamicColor: Boolean = false
