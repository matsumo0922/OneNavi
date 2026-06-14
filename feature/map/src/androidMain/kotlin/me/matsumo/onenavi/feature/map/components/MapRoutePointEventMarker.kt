package me.matsumo.onenavi.feature.map.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import me.matsumo.onenavi.core.model.RoutePointEventKind

/**
 * ルート上の地点イベント marker を描画する。
 *
 * @param googleMap marker 描画先の GoogleMap
 * @param latitude marker の緯度
 * @param longitude marker の経度
 * @param kind 地点イベントの種別
 * @param zIndex marker の zIndex
 */
@Composable
internal fun MapRoutePointEventMarker(
    googleMap: GoogleMap,
    latitude: Double,
    longitude: Double,
    kind: RoutePointEventKind,
    zIndex: Float,
) {
    val icon = rememberRoutePointEventMarkerIcon(kind)

    DisposableEffect(googleMap, latitude, longitude, kind, zIndex, icon) {
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .anchor(0.5f, 0.5f)
                .icon(icon)
                .zIndex(zIndex),
        )
        onDispose { marker?.remove() }
    }
}

@Composable
private fun rememberRoutePointEventMarkerIcon(kind: RoutePointEventKind): BitmapDescriptor {
    val density = LocalDensity.current

    return remember(density, kind) {
        createRoutePointEventMarkerIcon(kind, density)
    }
}

private fun createRoutePointEventMarkerIcon(kind: RoutePointEventKind, density: Density): BitmapDescriptor {
    val bitmapSize = with(density) { RoutePointEventMarkerBitmapSize.roundToPx() }
    val shadowRadius = with(density) { RoutePointEventMarkerShadowRadius.toPx() }
    val shadowOffsetY = with(density) { RoutePointEventMarkerShadowOffsetY.toPx() }
    val borderWidth = with(density) { RoutePointEventMarkerBorderWidth.toPx() }
    val center = bitmapSize / 2f
    val outerRadius = with(density) { RoutePointEventMarkerOuterRadius.toPx() }
    val bitmap = createBitmap(
        width = bitmapSize,
        height = bitmapSize,
    )
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(
            shadowRadius,
            0f,
            shadowOffsetY,
            RoutePointEventMarkerShadowColor,
        )
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RoutePointEventMarkerBorderColor
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }

    bitmap.applyCanvas {
        drawCircle(center, center, outerRadius, fillPaint)
        fillPaint.clearShadowLayer()
        drawCircle(center, center, outerRadius, fillPaint)
        drawCircle(center, center, outerRadius - borderWidth / 2f, borderPaint)
        drawRoutePointEventGlyph(
            kind = kind,
            center = center,
            density = density,
        )
    }

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun Canvas.drawRoutePointEventGlyph(
    kind: RoutePointEventKind,
    center: Float,
    density: Density,
) {
    when (kind) {
        RoutePointEventKind.TRAFFIC_LIGHT -> drawTrafficLightGlyph(center, density)
        RoutePointEventKind.STOP_LINE -> drawStopLineGlyph(center, density)
        RoutePointEventKind.RAILWAY_CROSSING -> drawRailwayCrossingGlyph(center, density)
    }
}

private fun Canvas.drawTrafficLightGlyph(center: Float, density: Density) {
    val bodyWidth = with(density) { TrafficLightBodyWidth.toPx() }
    val bodyHeight = with(density) { TrafficLightBodyHeight.toPx() }
    val bodyRadius = with(density) { TrafficLightBodyCornerRadius.toPx() }
    val lampRadius = with(density) { TrafficLightLampRadius.toPx() }
    val lampSpacing = with(density) { TrafficLightLampSpacing.toPx() }
    val bodyRect = RectF(
        center - bodyWidth / 2f,
        center - bodyHeight / 2f,
        center + bodyWidth / 2f,
        center + bodyHeight / 2f,
    )
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TrafficLightBodyColor
        style = Paint.Style.FILL
    }
    val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    drawRoundRect(bodyRect, bodyRadius, bodyRadius, bodyPaint)

    lampPaint.color = TrafficLightRedColor
    drawCircle(center, center - lampSpacing, lampRadius, lampPaint)
    lampPaint.color = TrafficLightYellowColor
    drawCircle(center, center, lampRadius, lampPaint)
    lampPaint.color = TrafficLightGreenColor
    drawCircle(center, center + lampSpacing, lampRadius, lampPaint)
}

private fun Canvas.drawStopLineGlyph(center: Float, density: Density) {
    val triangleWidth = with(density) { StopLineTriangleWidth.toPx() }
    val triangleHeight = with(density) { StopLineTriangleHeight.toPx() }
    val borderWidth = with(density) { StopLineTriangleBorderWidth.toPx() }
    val top = center - triangleHeight / 2f
    val bottom = center + triangleHeight / 2f
    val triangle = Path().apply {
        moveTo(center - triangleWidth / 2f, top)
        lineTo(center + triangleWidth / 2f, top)
        lineTo(center, bottom)
        close()
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = StopLineFillColor
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = borderWidth
        style = Paint.Style.STROKE
    }

    drawPath(triangle, fillPaint)
    drawPath(triangle, borderPaint)
}

private fun Canvas.drawRailwayCrossingGlyph(center: Float, density: Density) {
    val radius = with(density) { RailwayCrossingSymbolRadius.toPx() }
    val crossingStrokeWidth = with(density) { RailwayCrossingStrokeWidth.toPx() }
    val crossHalfLength = radius * RailwayCrossingCrossRatio
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RailwayCrossingFillColor
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeWidth = crossingStrokeWidth
    }

    drawCircle(center, center, radius, fillPaint)
    drawLine(
        center - crossHalfLength,
        center - crossHalfLength,
        center + crossHalfLength,
        center + crossHalfLength,
        strokePaint,
    )
    drawLine(
        center + crossHalfLength,
        center - crossHalfLength,
        center - crossHalfLength,
        center + crossHalfLength,
        strokePaint,
    )
}

/** 地点イベント marker の bitmap 全体サイズ。 */
private val RoutePointEventMarkerBitmapSize = 34.dp

/** 地点イベント marker の外円半径。 */
private val RoutePointEventMarkerOuterRadius = 14.dp

/** 地点イベント marker の枠線幅。 */
private val RoutePointEventMarkerBorderWidth = 1.5.dp

/** 地点イベント marker の影ぼかし半径。 */
private val RoutePointEventMarkerShadowRadius = 3.dp

/** 地点イベント marker の影の下方向 offset。 */
private val RoutePointEventMarkerShadowOffsetY = 1.5.dp

/** 地点イベント marker の枠線色。 */
private val RoutePointEventMarkerBorderColor = Color.rgb(31, 41, 55)

/** 地点イベント marker の影色。 */
private val RoutePointEventMarkerShadowColor = Color.argb(96, 0, 0, 0)

/** 信号機 glyph の本体幅。 */
private val TrafficLightBodyWidth = 9.dp

/** 信号機 glyph の本体高さ。 */
private val TrafficLightBodyHeight = 20.dp

/** 信号機 glyph の角丸半径。 */
private val TrafficLightBodyCornerRadius = 3.dp

/** 信号機 glyph の灯火半径。 */
private val TrafficLightLampRadius = 2.3.dp

/** 信号機 glyph の灯火間隔。 */
private val TrafficLightLampSpacing = 5.2.dp

/** 信号機 glyph の本体色。 */
private const val TrafficLightBodyColor = Color.BLACK

/** 信号機 glyph の赤色。 */
private val TrafficLightRedColor = Color.rgb(239, 68, 68)

/** 信号機 glyph の黄色。 */
private val TrafficLightYellowColor = Color.rgb(250, 204, 21)

/** 信号機 glyph の緑色。 */
private val TrafficLightGreenColor = Color.rgb(34, 197, 94)

/** 一時停止 glyph の逆三角幅。 */
private val StopLineTriangleWidth = 18.dp

/** 一時停止 glyph の逆三角高さ。 */
private val StopLineTriangleHeight = 16.dp

/** 一時停止 glyph の白枠幅。 */
private val StopLineTriangleBorderWidth = 2.dp

/** 一時停止 glyph の塗り色。 */
private val StopLineFillColor = Color.rgb(220, 38, 38)

/** 踏切 glyph の円半径。 */
private val RailwayCrossingSymbolRadius = 10.dp

/** 踏切 glyph の線幅。 */
private val RailwayCrossingStrokeWidth = 2.dp

/** 踏切 glyph の斜線長比率。 */
private const val RailwayCrossingCrossRatio = 0.58f

/** 踏切 glyph の塗り色。 */
private val RailwayCrossingFillColor = Color.rgb(250, 204, 21)
