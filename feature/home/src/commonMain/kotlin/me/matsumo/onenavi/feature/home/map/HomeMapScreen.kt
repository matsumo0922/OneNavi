package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal expect fun HomeMapScreen(
    onNavigatingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
)
