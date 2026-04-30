package me.matsumo.onenavi.feature.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.feature.map.components.MapBrowsingContent
import me.matsumo.onenavi.feature.map.components.MapControls
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.rememberMapCameraState
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun MapScreen(modifier: Modifier) {
    val viewModel = koinViewModel<MapViewModel>()

    var allowSheetHide by remember { mutableStateOf(false) }
    var sheetPeekHeight by remember { mutableStateOf(0.dp) }

    val cameraState = rememberMapCameraState()

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false,
            confirmValueChange = { newValue ->
                if (newValue == SheetValue.Hidden) allowSheetHide else true
            },
        ),
    )

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
        sheetContent = {

        },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            MapItem(
                modifier = Modifier.fillMaxSize(),
                cameraState = cameraState,
            )

            MapControls(
                modifier = Modifier.fillMaxSize(),
                cameraState = cameraState,
            )
        }
    }
}

@Composable
private fun MapScreenContent(
    screenState: MapScreenState,
    modifier: Modifier = Modifier,
) {
    when (screenState) {
        is MapScreenState.Browsing -> {
            MapBrowsingContent(
                modifier = modifier,
            )
        }
        is MapScreenState.PlaceDetails -> TODO()
        is MapScreenState.SearchResultsList -> TODO()
        is MapScreenState.RoutePreview -> TODO()
        is MapScreenState.Navigating -> TODO()
        is MapScreenState.Arrived -> TODO()
    }
}