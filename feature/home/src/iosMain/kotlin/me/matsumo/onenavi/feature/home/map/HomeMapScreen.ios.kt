package me.matsumo.onenavi.feature.home.map

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_not_available_ios
import org.jetbrains.compose.resources.stringResource

@Suppress("UnusedParameter")
@Composable
internal actual fun HomeMapScreen(
    onNavigatingChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.home_map_not_available_ios),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
