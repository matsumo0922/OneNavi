package me.matsumo.onenavi.feature.map.components

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.animation.doOnEnd
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
    val animatorState = remember(googleMap, icon) {
        mutableStateOf<ValueAnimator?>(null)
    }
    val position = LatLng(
        vehicleLocationState.location.latitude,
        vehicleLocationState.location.longitude,
    )
    val bearingDegrees = vehicleLocationState.bearingDegrees

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
            animatorState.value?.cancel()
            animatorState.value = null
            markerState.value?.remove()
            markerState.value = null
        }
    }

    SideEffect {
        val marker = markerState.value ?: return@SideEffect
        marker.zIndex = zIndex
    }

    DisposableEffect(markerState.value, position, bearingDegrees) {
        val marker = markerState.value
        if (marker == null) {
            onDispose {}
        } else {
            val startPosition = marker.position
            val startRotation = marker.rotation
            val targetRotation = bearingDegrees ?: startRotation

            animatorState.value?.cancel()

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = VEHICLE_PUCK_ANIMATION_DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val fraction = animation.animatedValue as Float
                    marker.position = LatLng(
                        lerp(startPosition.latitude, position.latitude, fraction),
                        lerp(startPosition.longitude, position.longitude, fraction),
                    )
                    marker.rotation = lerpAngle(startRotation, targetRotation, fraction)
                }
                doOnEnd {
                    if (animatorState.value == this) {
                        animatorState.value = null
                    }
                }
            }

            animatorState.value = animator
            animator.start()

            onDispose {
                if (animatorState.value == animator) {
                    animator.cancel()
                }
            }
        }
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

private fun lerp(from: Double, to: Double, fraction: Float): Double =
    from + (to - from) * fraction

private fun lerpAngle(from: Float, to: Float, fraction: Float): Float {
    val diff = ((to - from + 540f) % 360f) - 180f
    return (from + diff * fraction + 360f) % 360f
}

private val VEHICLE_PUCK_SIZE = 64.dp
private const val VEHICLE_PUCK_ANIMATION_DURATION_MS = 800L
