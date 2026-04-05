package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState
import org.koin.compose.viewmodel.koinViewModel

@Suppress("ViewModelForwarding")
@Composable
internal actual fun HomeMapScreen(
    onNavigatingChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val viewModel: HomeMapViewModel = koinViewModel()
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()

    LaunchedEffect(screenState) {
        val isNavigating = screenState is HomeMapScreenState.Navigating
        val isArrived = screenState is HomeMapScreenState.Arrived

        onNavigatingChanged(isNavigating || isArrived)
    }

    HomeMapScreenContent(
        viewModel = viewModel,
        modifier = modifier,
    )
}
