package me.matsumo.onenavi.feature.map.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.ic_vehicle_puck
import me.matsumo.onenavi.feature.map.components.callout.rememberMapComposeBitmapDescriptor
import me.matsumo.onenavi.feature.map.state.VehicleLocationState
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun MapVehiclePuck(
    googleMap: GoogleMap,
    vehicleLocationState: VehicleLocationState,
    zIndex: Float,
) {
    val icon = rememberVehiclePuckIcon()
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
                .zIndex(zIndex),
        )

        onDispose {
            markerState.value?.remove()
            markerState.value = null
        }
    }

    SideEffect {
        val marker = markerState.value ?: return@SideEffect
        marker.position = position
        vehicleLocationState.bearingDegrees?.let { bearingDegrees ->
            marker.rotation = bearingDegrees
        }
        marker.zIndex = zIndex
    }
}

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

private val VEHICLE_PUCK_SIZE = 64.dp
