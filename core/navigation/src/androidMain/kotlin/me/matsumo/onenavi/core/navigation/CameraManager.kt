package me.matsumo.onenavi.core.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.RoutePoint

enum class CameraState {
    IDLE,
    FOLLOWING,
    OVERVIEW,
}

data class MapPadding(
    val top: Int = 0,
    val left: Int = 0,
    val bottom: Int = 0,
    val right: Int = 0,
)

class CameraManager(
    private val context: Context,
) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var lastBearing: Float = 0f
    private var followingPadding = MapPadding()
    private var overviewPadding = MapPadding()

    private val _cameraState = MutableStateFlow(CameraState.IDLE)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _isFollowing3D = MutableStateFlow(true)
    val isFollowing3D: StateFlow<Boolean> = _isFollowing3D.asStateFlow()

    private val _currentLocation = MutableStateFlow<RoutePoint?>(null)
    val currentLocation: StateFlow<RoutePoint?> = _currentLocation.asStateFlow()

    private val _currentBearing = MutableStateFlow(0f)
    val currentBearing: StateFlow<Float> = _currentBearing.asStateFlow()

    private val _mapPadding = MutableStateFlow(MapPadding())
    val mapPadding: StateFlow<MapPadding> = _mapPadding.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocationUpdated)
        }
    }

    fun register() {
        startLocationUpdates()
    }

    fun unregister() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    fun onRouteChanged(route: Any?) {
        if (route == null) {
            _cameraState.value = CameraState.IDLE
            _mapPadding.value = MapPadding()
        }
    }

    fun requestCameraFollowing(pitch3D: Boolean = _isFollowing3D.value) {
        _isFollowing3D.value = pitch3D
        _cameraState.value = CameraState.FOLLOWING
        updateCurrentPadding()
    }

    fun requestCameraOverview() {
        _cameraState.value = CameraState.OVERVIEW
        updateCurrentPadding()
    }

    fun requestCameraIdle() {
        _cameraState.value = CameraState.IDLE
        updateCurrentPadding()
    }

    fun toggleCompass() {
        requestCameraFollowing(pitch3D = !_isFollowing3D.value)
    }

    fun applyNavigationPadding(
        followingPadding: MapPadding,
        overviewPadding: MapPadding,
    ) {
        this.followingPadding = followingPadding
        this.overviewPadding = overviewPadding
        updateCurrentPadding()
    }

    fun clearNavigationPadding() {
        followingPadding = MapPadding()
        overviewPadding = MapPadding()
        _mapPadding.value = MapPadding()
    }

    private fun updateCurrentPadding() {
        _mapPadding.value = when (_cameraState.value) {
            CameraState.FOLLOWING -> followingPadding
            CameraState.OVERVIEW -> overviewPadding
            CameraState.IDLE -> MapPadding()
        }
    }

    private fun onLocationUpdated(location: Location) {
        lastBearing = location.bearing
        _currentBearing.value = lastBearing
        _currentLocation.value = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .build()

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                onLocationUpdated(location)
            }
        }
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 500L
    }
}
