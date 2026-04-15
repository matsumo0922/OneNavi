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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
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

/**
 * Google Maps のカメラ制御と位置情報管理を担当するクラス。
 */
class CameraManager(
    private val context: Context,
) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var googleMap: GoogleMap? = null
    private var lastBearing: Float = 0f
    private var currentPadding = MapPadding()

    private val _cameraState = MutableStateFlow(CameraState.IDLE)

    /** 現在のカメラ状態。 */
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _isFollowing3D = MutableStateFlow(true)

    /** Following 3D モードかどうか。false なら Following 2D（北固定）。 */
    val isFollowing3D: StateFlow<Boolean> = _isFollowing3D.asStateFlow()

    private val _currentLocation = MutableStateFlow<RoutePoint?>(null)

    /** 最新の端末位置。 */
    val currentLocation: StateFlow<RoutePoint?> = _currentLocation.asStateFlow()

    private val _currentBearing = MutableStateFlow(0f)

    /** 最新の進行方向。 */
    val currentBearing: StateFlow<Float> = _currentBearing.asStateFlow()

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
        googleMap = null
    }

    fun attachMap(map: GoogleMap) {
        googleMap = map
        applyMapPadding()
    }

    fun detachMap() {
        googleMap = null
    }

    fun setupCamera(map: GoogleMap) {
        attachMap(map)
    }

    fun teardownCamera() {
        detachMap()
    }

    fun onRouteChanged(route: Any?) {
        if (route == null) {
            _cameraState.value = CameraState.IDLE
        }
    }

    fun requestCameraFollowing(pitch3D: Boolean = _isFollowing3D.value) {
        _isFollowing3D.value = pitch3D
        val location = _currentLocation.value ?: return
        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(FOLLOWING_ZOOM)
            .tilt(if (pitch3D) FOLLOWING_3D_PITCH else FOLLOWING_2D_PITCH)
            .bearing(if (pitch3D) lastBearing else 0f)
            .build()

        googleMap?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        _cameraState.value = CameraState.FOLLOWING
    }

    fun requestCameraOverview() {
        _cameraState.value = CameraState.OVERVIEW
    }

    fun requestCameraIdle() {
        _cameraState.value = CameraState.IDLE
    }

    fun toggleCompass() {
        requestCameraFollowing(pitch3D = !_isFollowing3D.value)
    }

    fun applyNavigationPadding(
        followingPadding: MapPadding,
        overviewPadding: MapPadding,
    ) {
        currentPadding = if (_cameraState.value == CameraState.FOLLOWING) followingPadding else overviewPadding
        applyMapPadding()
    }

    fun clearNavigationPadding() {
        currentPadding = MapPadding()
        applyMapPadding()
    }

    private fun onLocationUpdated(location: Location) {
        lastBearing = location.bearing
        _currentBearing.value = location.bearing
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

    private fun applyMapPadding() {
        val padding = currentPadding
        googleMap?.setPadding(padding.left, padding.top, padding.right, padding.bottom)
    }

    companion object {
        private const val FOLLOWING_ZOOM = 17f
        private const val FOLLOWING_3D_PITCH = 45f
        private const val FOLLOWING_2D_PITCH = 0f
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 500L
    }
}
