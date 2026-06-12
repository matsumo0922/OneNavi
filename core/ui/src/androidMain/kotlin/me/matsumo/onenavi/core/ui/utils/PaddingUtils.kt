package me.matsumo.onenavi.core.ui.utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current

    return PaddingValues(
        top = calculateTopPadding() + other.calculateTopPadding(),
        start = calculateStartPadding(layoutDirection) + other.calculateStartPadding(layoutDirection),
        end = calculateEndPadding(layoutDirection) + other.calculateEndPadding(layoutDirection),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
    )
}

@Stable
fun Modifier.navigationBarsBottomPaddingOrDefault(
    defaultBottom: Dp = 8.dp,
): Modifier = composed(
    inspectorInfo = debugInspectorInfo {
        name = "navigationBarsBottomPaddingOrDefault"
        properties["defaultBottom"] = defaultBottom
    },
) {
    val bottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    padding(
        bottom = if (bottom == 0.dp) {
            defaultBottom
        } else {
            bottom
        },
    )
}
