package me.matsumo.onenavi.feature.map.car

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import me.matsumo.onenavi.feature.map.components.MapControls
import me.matsumo.onenavi.feature.map.state.MapPanelLayout
import me.matsumo.onenavi.feature.map.state.MapPanelSide
import me.matsumo.onenavi.feature.map.state.MapWidthSizeClass
import me.matsumo.onenavi.feature.map.state.rememberMapCameraState

/**
 * Android Auto の地図上に重ねる地図コントロール overlay。
 *
 * 既存の [MapControls] をそのまま流用し、車載向けに非分割・右側固定で配置する。
 * カメラ操作は渡された [GoogleMap] に紐付けた内部の MapCameraState 経由で行う。
 */
@Composable
fun CarMapControlsOverlay(
    googleMap: GoogleMap,
    isNavigating: Boolean,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraState = rememberMapCameraState()
    val panelLayout = remember {
        MapPanelLayout(
            widthSizeClass = MapWidthSizeClass.COMPACT,
            panelWidth = 0.dp,
            panelSide = MapPanelSide.RIGHT,
        )
    }

    LaunchedEffect(googleMap) {
        cameraState.attachMap(googleMap)
    }

    MapControls(
        modifier = modifier,
        cameraState = cameraState,
        panelLayout = panelLayout,
        topPadding = 0.dp,
        bottomPadding = 0.dp,
        isNavigating = isNavigating,
        onSettingClicked = onSettingClicked,
    )
}
