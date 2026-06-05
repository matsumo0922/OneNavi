package me.matsumo.onenavi.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Display
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import kotlin.math.ln

/**
 * Android Auto の地図サーフェスを描画するレンダラ。
 *
 * ホストから受け取った [android.view.Surface] を [VirtualDisplay] の出力にし、
 * その仮想ディスプレイ上の [Presentation] へ Google Maps の [MapView] を載せて描画する。
 * Google Maps SDK は任意の Surface へ直接描画できないため、View 階層を持つ
 * [Presentation] を仲介させる構成を取っている。
 *
 * [AppManager.setSurfaceCallback] に登録して使用する。
 */
class OneNaviCarMapRenderer(
    private val carContext: CarContext,
) : SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var mapPresentation: MapPresentation? = null
    private var googleMap: GoogleMap? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var visibleArea: Rect? = null

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        releaseSurface()

        surfaceWidth = surfaceContainer.width.coerceAtLeast(1)
        surfaceHeight = surfaceContainer.height.coerceAtLeast(1)
        val densityDpi = surfaceContainer.dpi.coerceAtLeast(1)

        val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val createdDisplay = displayManager.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            surfaceWidth,
            surfaceHeight,
            densityDpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        )
        virtualDisplay = createdDisplay

        val presentation = MapPresentation(
            outerContext = carContext,
            targetDisplay = createdDisplay.display,
            onMapReady = ::onMapReady,
        )
        presentation.show()
        mapPresentation = presentation
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        releaseSurface()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        applyMapPadding()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val map = googleMap ?: return
        map.moveCamera(CameraUpdateFactory.scrollBy(distanceX, distanceY))
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val map = googleMap ?: return
        val zoomDelta = scaleFactor.toZoomDelta()
        val focusPoint = Point(focusX.toInt(), focusY.toInt())
        map.animateCamera(CameraUpdateFactory.zoomBy(zoomDelta, focusPoint))
    }

    /** ホスト接続終了時などに呼び出し、確保中のリソースを解放する。 */
    fun release() {
        releaseSurface()
    }

    @SuppressLint("MissingPermission")
    private fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.isBuildingsEnabled = true
        map.isTrafficEnabled = true
        map.uiSettings.apply {
            isCompassEnabled = false
            isMapToolbarEnabled = false
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
        }

        if (hasLocationPermission()) {
            map.isMyLocationEnabled = true
        }

        map.moveCamera(CameraUpdateFactory.newCameraPosition(defaultCameraPosition()))
        applyMapPadding()
    }

    private fun applyMapPadding() {
        val map = googleMap ?: return
        val area = visibleArea ?: return
        if (area.isEmpty) return

        val leftPadding = area.left.coerceAtLeast(0)
        val topPadding = area.top.coerceAtLeast(0)
        val rightPadding = (surfaceWidth - area.right).coerceAtLeast(0)
        val bottomPadding = (surfaceHeight - area.bottom).coerceAtLeast(0)
        map.setPadding(leftPadding, topPadding, rightPadding, bottomPadding)
    }

    private fun hasLocationPermission(): Boolean {
        val result = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
        return result == PackageManager.PERMISSION_GRANTED
    }

    private fun releaseSurface() {
        googleMap = null
        mapPresentation?.release()
        mapPresentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    companion object {
        /** 仮想ディスプレイの識別名。 */
        private const val VIRTUAL_DISPLAY_NAME = "OneNaviCarMap"
    }
}

/**
 * 仮想ディスプレイ上に Google Maps の [MapView] を表示する [Presentation]。
 *
 * [MapView] のライフサイクルは本 [Presentation] のライフサイクルに同期させる。
 */
private class MapPresentation(
    outerContext: Context,
    targetDisplay: Display,
    private val onMapReady: (GoogleMap) -> Unit,
) : Presentation(outerContext, targetDisplay) {

    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val createdMapView = MapView(context, mapOptions())
        mapView = createdMapView
        setContentView(createdMapView)
        createdMapView.onCreate(null)
        createdMapView.getMapAsync { readyMap -> onMapReady(readyMap) }
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
        mapView?.onResume()
    }

    override fun onStop() {
        super.onStop()
        mapView?.onPause()
        mapView?.onStop()
    }

    /** [MapView] を破棄して [Presentation] を閉じる。 */
    fun release() {
        mapView?.onDestroy()
        mapView = null
        dismiss()
    }

    private fun mapOptions(): GoogleMapOptions = GoogleMapOptions()
        .mapType(GoogleMap.MAP_TYPE_NORMAL)
        .camera(defaultCameraPosition())
        .compassEnabled(false)
        .mapToolbarEnabled(false)
        .zoomControlsEnabled(false)
        .rotateGesturesEnabled(true)
        .tiltGesturesEnabled(true)
}

/** 地図初期表示の緯度（東京駅付近）。 */
private const val DEFAULT_LATITUDE = 35.681236

/** 地図初期表示の経度（東京駅付近）。 */
private const val DEFAULT_LONGITUDE = 139.767125

/** 地図初期表示のズームレベル。 */
private const val DEFAULT_ZOOM = 14f

/**
 * 地図の初期カメラ位置を生成する。
 *
 * @return 初期表示に用いる [CameraPosition]
 */
private fun defaultCameraPosition(): CameraPosition = CameraPosition.Builder()
    .target(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
    .zoom(DEFAULT_ZOOM)
    .build()

/**
 * ピンチ操作の拡大率を Google Maps のズーム変化量へ変換する。
 *
 * @return 加算するズームレベルの差分
 */
private fun Float.toZoomDelta(): Float {
    if (this <= 0f) return 0f
    return (ln(this.toDouble()) / ln(2.0)).toFloat()
}
