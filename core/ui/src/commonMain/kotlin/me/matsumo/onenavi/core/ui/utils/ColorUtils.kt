package me.matsumo.onenavi.core.ui.utils

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
expect fun rememberColorScheme(
    useDynamicColor: Boolean,
    seedColor: Color,
    isDark: Boolean,
): ColorScheme

expect val isSupportDynamicColor: Boolean
