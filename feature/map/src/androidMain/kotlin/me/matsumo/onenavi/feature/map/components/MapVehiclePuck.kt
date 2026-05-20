package me.matsumo.onenavi.feature.map.components

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.isActive
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.ic_vehicle_puck
import me.matsumo.onenavi.feature.map.components.callout.rememberMapComposeBitmapDescriptor
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.VehicleLocationState
import me.matsumo.onenavi.feature.map.state.VehiclePose
import me.matsumo.onenavi.feature.map.state.VehiclePoseEstimator
import org.jetbrains.compose.resources.painterResource

/**
 * 自車アイコンと追従カメラを frame ごとの推定 pose で更新する。
 *
 * @param googleMap 描画先の GoogleMap
 * @param cameraState カメラ追従状態
 * @param vehicleLocationState 最新の自車位置 tick
 * @param routeGeometry 案内中 route の geometry。案内中以外は空でよい
 * @param zIndex 自車アイコンの zIndex
 */
@Composable
internal fun MapVehiclePoseEffect(
    googleMap: GoogleMap,
    cameraState: MapCameraState,
    vehicleLocationState: VehicleLocationState,
    routeGeometry: ImmutableList<RoutePoint>,
    zIndex: Float,
) {
    val icon = rememberVehiclePuckIcon()
    val estimator = remember(googleMap) { VehiclePoseEstimator() }
    val markerState = remember(googleMap, icon) {
        mutableStateOf<Marker?>(null)
    }
    val position = LatLng(
        vehicleLocationState.location.latitude,
        vehicleLocationState.location.longitude,
    )

    DisposableEffect(googleMap, icon) {
        markerState.value = googleMap.addMarker(
            MarkerOptions()
                .position(position)
                .anchor(0.5f, 0.5f)
                .flat(true)
                .icon(icon)
                .rotation(vehicleLocationState.bearingDegrees ?: 0f)
                .zIndex(zIndex),
        )

        onDispose {
            markerState.value?.remove()
            markerState.value = null
        }
    }

    SideEffect {
        val marker = markerState.value ?: return@SideEffect
        marker.zIndex = zIndex
    }

    LaunchedEffect(vehicleLocationState, routeGeometry) {
        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        estimator.updateSample(
            sample = vehicleLocationState,
            routeGeometry = routeGeometry,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
        estimator.estimate(nowElapsedRealtimeNanos)?.let { pose ->
            markerState.value?.updatePose(pose)
            cameraState.updateVehiclePose(pose)
        }
    }

    LaunchedEffect(markerState.value, estimator, cameraState) {
        while (isActive) {
            withFrameNanos { }
            val marker = markerState.value ?: continue
            val pose = estimator.estimate(SystemClock.elapsedRealtimeNanos()) ?: continue

            marker.updatePose(pose)
            cameraState.updateVehiclePose(pose)
        }
    }
}

/**
 * Compose resources の自車アイコンを GoogleMap marker 用の bitmap descriptor に変換する。
 *
 * @return 自車アイコン marker に設定する bitmap descriptor
 */
@Composable
private fun rememberVehiclePuckIcon(): BitmapDescriptor {
    return rememberMapComposeBitmapDescriptor("vehicle-puck") {
        Image(
            modifier = Modifier.size(VEHICLE_PUCK_SIZE),
            painter = painterResource(Res.drawable.ic_vehicle_puck),
            contentDescription = null,
        )
    }
}

/**
 * marker に表示 pose を反映する。
 *
 * @param pose frame 時点の表示 pose
 */
private fun Marker.updatePose(pose: VehiclePose) {
    position = LatLng(
        pose.location.latitude,
        pose.location.longitude,
    )
    pose.bearingDegrees?.let { bearingDegrees ->
        rotation = bearingDegrees
    }
}

/** 自車アイコンの表示サイズ。 */
private val VEHICLE_PUCK_SIZE = 64.dp
