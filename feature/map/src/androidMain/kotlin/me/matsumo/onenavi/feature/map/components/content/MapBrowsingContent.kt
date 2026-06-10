package me.matsumo.onenavi.feature.map.components.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_search_on_phone
import me.matsumo.onenavi.core.ui.theme.LocalSupportsPlatformDialogWindow
import me.matsumo.onenavi.feature.map.components.topappbar.MapTopAppBar
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapBrowsingContent(
    cameraState: MapCameraState,
    uiState: MapUiState,
    showSettingAction: Boolean,
    showPhoneDestinationSearchAction: Boolean,
    destinationSearchRequestId: Long?,
    onDestinationSearchRequestConsumed: (Long) -> Unit,
    onUiEvent: (MapUiEvent) -> Unit,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        val supportsPlatformDialogWindow = LocalSupportsPlatformDialogWindow.current
        val topAppBarModifier = if (supportsPlatformDialogWindow) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.fillMaxSize()
        }

        if (showPhoneDestinationSearchAction) {
            MapBrowsingPhoneDestinationSearchTopAppBar(
                modifier = topAppBarModifier.statusBarsPadding(),
                onClicked = { onUiEvent(MapUiEvent.OnPhoneDestinationSearchClicked) },
                onTopAppBarHeightChanged = { height ->
                    onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(height))
                },
            )
        } else {
            MapTopAppBar(
                modifier = topAppBarModifier.statusBarsPadding(),
                cameraState = cameraState,
                query = uiState.query,
                suggestions = uiState.suggestions,
                histories = uiState.histories,
                selectedResult = uiState.selectedResult,
                showSettingAction = showSettingAction,
                destinationSearchRequestId = destinationSearchRequestId,
                onDestinationSearchRequestConsumed = onDestinationSearchRequestConsumed,
                onUiEvent = onUiEvent,
                onSettingClicked = onSettingClicked,
                onTopAppBarHeightChanged = { height ->
                    onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(height))
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapBrowsingPhoneDestinationSearchTopAppBar(
    onClicked: () -> Unit,
    onTopAppBarHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(top = 4.dp)
                .heightIn(min = 56.dp)
                .onGloballyPositioned {
                    onTopAppBarHeightChanged(it.size.height)
                },
            onClick = onClicked,
            shape = SearchBarDefaults.inputFieldShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.home_search_on_phone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
