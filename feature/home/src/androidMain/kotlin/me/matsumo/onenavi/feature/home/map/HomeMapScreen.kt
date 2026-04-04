package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.matsumo.onenavi.core.model.NavigationState
import org.koin.compose.viewmodel.koinViewModel

@Suppress("ViewModelForwarding")
@Composable
internal actual fun HomeMapScreen(
    onNavigatingChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val viewModel: HomeMapViewModel = koinViewModel()
    val navigationState by viewModel.navigationState.collectAsStateWithLifecycle()

    LaunchedEffect(navigationState) {
        val isNavigating = navigationState is NavigationState.ActiveGuidance ||
            navigationState is NavigationState.Arrival
        onNavigatingChanged(isNavigating)
    }

    HomeMapScreenContent(
        viewModel = viewModel,
        modifier = modifier,
    )
}
