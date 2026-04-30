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
import com.google.android.gms.maps.GoogleMap
import me.matsumo.onenavi.feature.map.state.rememberMapCameraState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun MapScreen(modifier: Modifier) {
    var allowSheetHide by remember { mutableStateOf(false) }
    var sheetPeekHeight by remember { mutableStateOf(0.dp) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }

    val cameraState = rememberMapCameraState(googleMap)

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
                googleMap = googleMap,
                onMapChanged = { googleMap = it },
            )
        }
    }
}
