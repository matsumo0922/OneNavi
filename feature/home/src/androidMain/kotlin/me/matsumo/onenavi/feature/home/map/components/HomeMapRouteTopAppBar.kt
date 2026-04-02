package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState

@Composable
internal fun HomeMapRouteTopAppBar(
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationEventHandler(navigationState) {
        onBackClicked()
    }
}
