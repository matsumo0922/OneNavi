package me.matsumo.onenavi.core.navigation

import android.location.Location
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 地図カメラおよび自車位置に関する状態を保持する。
 * 位置の取得は Google Navigation SDK の RoadSnappedLocationProvider に委譲し、
 * map-matched された位置と bearing をそのまま StateFlow として公開する。
 */
enum class CameraState {
    IDLE,
    FOLLOWING,
    OVERVIEW,
}

/**
 * 地図カメラに適用する padding。画面状態ごとに異なる値を適用する。
 */
data class MapPadding(
    val top: Int = 0,
    val left: Int = 0,
    val bottom: Int = 0,
    val right: Int = 0,
)

class CameraManager(
    private val navigationSdkManager: NavigationSdkManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var attachedProvider: RoadSnappedLocationProvider? = null

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

    private val locationListener = RoadSnappedLocationProvider.LocationListener { location ->
        onLocationUpdated(location)
    }

    fun register() {
        collectJob?.cancel()
        collectJob = scope.launch {
            navigationSdkManager.roadSnappedLocationProvider.collectLatest { provider ->
                detachProvider()
                attachedProvider = provider
                provider?.addLocationListener(locationListener)
            }
        }
    }

    fun unregister() {
        collectJob?.cancel()
        collectJob = null
        detachProvider()
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
        if (location.hasBearing()) {
            lastBearing = location.bearing
            _currentBearing.value = lastBearing
        }
        _currentLocation.value = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
        )
    }

    private fun detachProvider() {
        attachedProvider?.removeLocationListener(locationListener)
        attachedProvider = null
    }
}
