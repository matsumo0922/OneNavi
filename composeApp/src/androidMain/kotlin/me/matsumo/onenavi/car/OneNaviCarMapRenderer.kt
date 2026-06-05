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
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import me.matsumo.onenavi.core.ui.theme.OneNaviTheme
import me.matsumo.onenavi.feature.map.car.CarMapControlsOverlay
import kotlin.math.ln

/**
 * Android Auto の地図サーフェスを描画するレンダラ。
 *
 * ホストから受け取った [android.view.Surface] を [VirtualDisplay] の出力にし、
 * その仮想ディスプレイ上の [Presentation] へ Google Maps の [MapView] と、
 * その上に重ねる Compose 製の地図コントロールを載せて描画する。
 * Google Maps SDK は任意の Surface へ直接描画できないため、View 階層を持つ
 * [Presentation] を仲介させる構成を取っている。
 *
 * 車載画面のタッチは View 階層へ届かず、ホストから [onClick] / [onScroll] /
 * [onScale] として通知される。コントロールのタップは [onClick] を合成 [MotionEvent]
 * に変換して Compose へ転送することで成立させる。
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
            onSettingClicked = { /* TODO: 車載の設定導線は別途対応する */ },
        )
        presentation.show()
        mapPresentation = presentation
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        releaseSurface()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        applyVisibleArea()
    }

    override fun onClick(x: Float, y: Float) {
        mapPresentation?.dispatchTap(x, y)
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
        applyVisibleArea()
    }

    private fun applyVisibleArea() {
        val area = visibleArea ?: return
        if (area.isEmpty) return

        val leftInset = area.left.coerceAtLeast(0)
        val topInset = area.top.coerceAtLeast(0)
        val rightInset = (surfaceWidth - area.right).coerceAtLeast(0)
        val bottomInset = (surfaceHeight - area.bottom).coerceAtLeast(0)

        googleMap?.setPadding(leftInset, topInset, rightInset, bottomInset)
        mapPresentation?.updateControlsInsets(leftInset, topInset, rightInset, bottomInset)
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
 * 仮想ディスプレイ上に Google Maps の [MapView] と Compose の地図コントロールを表示する [Presentation]。
 *
 * [MapView] のライフサイクルは本 [Presentation] のライフサイクルに同期させる。
 * Compose の動作に必要な owner 群は [CarPresentationOwner] から供給する。
 */
private class MapPresentation(
    outerContext: Context,
    targetDisplay: Display,
    private val onMapReady: (GoogleMap) -> Unit,
    private val onSettingClicked: () -> Unit,
) : Presentation(outerContext, targetDisplay) {

    private val composeOwner = CarPresentationOwner()
    private val googleMapState = mutableStateOf<GoogleMap?>(null)

    private var mapView: MapView? = null
    private var composeView: ComposeView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeOwner.onCreate()

        val container = FrameLayout(context)
        container.setViewTreeLifecycleOwner(composeOwner)
        container.setViewTreeViewModelStoreOwner(composeOwner)
        container.setViewTreeSavedStateRegistryOwner(composeOwner)

        val createdMapView = MapView(context, mapOptions())
        mapView = createdMapView
        container.addView(createdMapView, matchParentLayoutParams())

        val createdComposeView = createComposeView()
        composeView = createdComposeView
        container.addView(createdComposeView, matchParentLayoutParams())

        setContentView(container)

        createdMapView.onCreate(null)
        createdMapView.getMapAsync { readyMap ->
            googleMapState.value = readyMap
            onMapReady(readyMap)
        }
    }

    override fun onStart() {
        super.onStart()
        composeOwner.onResume()
        mapView?.onStart()
        mapView?.onResume()
    }

    override fun onStop() {
        super.onStop()
        mapView?.onPause()
        mapView?.onStop()
        composeOwner.onPause()
    }

    /** ホストから通知されたタップ座標を Compose へ転送する。 */
    fun dispatchTap(x: Float, y: Float) {
        val targetView = composeView ?: return
        val eventTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(eventTime, eventTime + TAP_DURATION_MS, MotionEvent.ACTION_UP, x, y, 0)
        targetView.dispatchTouchEvent(downEvent)
        targetView.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
    }

    /** ホストの可視領域に合わせてコントロールの内側余白を更新する。 */
    fun updateControlsInsets(left: Int, top: Int, right: Int, bottom: Int) {
        composeView?.setPadding(left, top, right, bottom)
    }

    /** [MapView] を破棄して [Presentation] を閉じる。 */
    fun release() {
        mapView?.onDestroy()
        mapView = null
        composeView = null
        composeOwner.onDestroy()
        dismiss()
    }

    private fun createComposeView(): ComposeView {
        val view = ComposeView(context)
        view.setContent {
            val map = googleMapState.value
            OneNaviTheme(drawBackground = false) {
                if (map != null) {
                    CarMapControlsOverlay(
                        googleMap = map,
                        isNavigating = false,
                        onSettingClicked = onSettingClicked,
                    )
                }
            }
        }
        return view
    }

    private fun matchParentLayoutParams(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun mapOptions(): GoogleMapOptions = GoogleMapOptions()
        .mapType(GoogleMap.MAP_TYPE_NORMAL)
        .camera(defaultCameraPosition())
        .compassEnabled(false)
        .mapToolbarEnabled(false)
        .zoomControlsEnabled(false)
        .rotateGesturesEnabled(true)
        .tiltGesturesEnabled(true)

    companion object {
        /** 合成タップの押下から離上までの時間（ミリ秒）。 */
        private const val TAP_DURATION_MS = 16L
    }
}

/**
 * [Presentation] 上で Compose を動かすための owner。
 *
 * Compose は [LifecycleOwner] / [ViewModelStoreOwner] / [SavedStateRegistryOwner] を要求するが、
 * [Presentation] はこれらを提供しないため、本クラスが代替として供給する。
 */
private class CarPresentationOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
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
