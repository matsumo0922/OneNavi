package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import kotlin.math.hypot
import kotlin.math.pow

/**
 * GoogleMap が実際に地図コンテンツを描画している実効 density の測定結果。
 *
 * VirtualDisplay の density (例: 1.0) と乖離している場合、Maps が別ディスプレイの
 * density を拾っている疑いを定量化するために使う。
 *
 * @param zoom 測定時のカメラズーム
 * @param sampleLngDeltaDeg 測定に使った経度差 (度)
 * @param measuredPx 既知2点間の実測 screen px 距離 (測定方法A)
 * @param expectedPxAtDensityOne density=1.0 と仮定した時の期待 px 距離 (測定方法B の基準)
 * @param effectiveDensity 実測 px / 期待 px = Maps の実効 density
 */
internal data class MapRenderDensityMeasurement(
    val zoom: Float,
    val sampleLngDeltaDeg: Double,
    val measuredPx: Double,
    val expectedPxAtDensityOne: Double,
    val effectiveDensity: Double,
)

/**
 * GoogleMap の描画実効 density を debug overlay へ橋渡しするためのプロセス内ホルダー。
 *
 * `feature/map` で計測し、`composeApp` の VD overlay から読み取る。debug 診断専用。
 */
object MapRenderDensityDiagnostics {

    /** overlay 表示用の最新ラベル。未計測時は null。 */
    var label: String? by mutableStateOf(null)
        private set

    fun update(newLabel: String) {
        label = newLabel
    }

    fun clear() {
        label = null
    }
}

/** 経度方向のサンプル差。tilt / bearing=0 の時に水平距離として安定する小さな値。 */
private const val SAMPLE_LNG_DELTA_DEG = 0.0005

/** Web Mercator のズーム0 ワールド幅 (density 1.0 基準, point)。 */
private const val WORLD_TILE_SIZE_PX = 256.0

/** 経度1周。 */
private const val FULL_LONGITUDE_DEG = 360.0

/**
 * 既知2点を screen 座標へ射影し、実測 px 距離と density=1.0 期待 px 距離を比較して
 * Maps の実効 density を算出する。
 *
 * @return 計測結果。projection / camera が未準備で 0 除算になる場合は null
 */
internal fun GoogleMap.measureRenderDensity(): MapRenderDensityMeasurement? {
    val projection = projection
    val cameraPosition = cameraPosition
    val zoom = cameraPosition.zoom
    val center = cameraPosition.target

    val nearPoint = projection.toScreenLocation(center)
    val farPoint = projection.toScreenLocation(
        LatLng(center.latitude, center.longitude + SAMPLE_LNG_DELTA_DEG),
    )

    val measuredPx = hypot(
        (farPoint.x - nearPoint.x).toDouble(),
        (farPoint.y - nearPoint.y).toDouble(),
    )

    val worldPx = WORLD_TILE_SIZE_PX * 2.0.pow(zoom.toDouble())
    val expectedPxAtDensityOne = (SAMPLE_LNG_DELTA_DEG / FULL_LONGITUDE_DEG) * worldPx

    if (expectedPxAtDensityOne <= 0.0 || measuredPx <= 0.0) {
        return null
    }

    return MapRenderDensityMeasurement(
        zoom = zoom,
        sampleLngDeltaDeg = SAMPLE_LNG_DELTA_DEG,
        measuredPx = measuredPx,
        expectedPxAtDensityOne = expectedPxAtDensityOne,
        effectiveDensity = measuredPx / expectedPxAtDensityOne,
    )
}

/** overlay / logcat 表示用にラベル文字列へ整形する。 */
internal fun MapRenderDensityMeasurement.toDiagnosticLabel(composeDensity: Float): String {
    val measuredLabel = "%.1f".format(measuredPx)
    val expectedLabel = "%.1f".format(expectedPxAtDensityOne)
    val effectiveLabel = "%.2f".format(effectiveDensity)
    val ratioLabel = "%.2f".format(effectiveDensity / composeDensity)

    return "mapDensity eff=$effectiveLabel compose=$composeDensity ratio=$ratioLabel " +
        "(zoom=$zoom measured=${measuredLabel}px expected@1.0=${expectedLabel}px)"
}
