package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.koin.compose.viewmodel.koinViewModel

@Suppress("ViewModelForwarding")
@Composable
internal actual fun HomeMapScreen(modifier: Modifier) {
    val viewModel: HomeMapViewModel = koinViewModel()
    HomeMapScreenContent(
        viewModel = viewModel,
        modifier = modifier,
    )
}
