package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal expect fun HomeMapScreenContent(
    viewModel: HomeMapViewModel,
    modifier: Modifier = Modifier,
)

@Suppress("ViewModelForwarding")
@Composable
internal fun HomeMapScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeMapViewModel = koinViewModel(),
) {
    HomeMapScreenContent(
        viewModel = viewModel,
        modifier = modifier,
    )
}
