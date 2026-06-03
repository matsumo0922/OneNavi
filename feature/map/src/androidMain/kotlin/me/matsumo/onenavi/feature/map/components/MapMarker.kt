package me.matsumo.onenavi.feature.map.components

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.ic_origin_dot
import me.matsumo.onenavi.feature.map.components.callout.rememberMapComposeBitmapDescriptor
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun MapMarker(
    googleMap: GoogleMap,
    latitude: Double,
    longitude: Double,
    title: String? = null,
    zIndex: Float = DEFAULT_MARKER_Z_INDEX,
) {
    DisposableEffect(googleMap, latitude, longitude, title, zIndex) {
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title(title)
                .zIndex(zIndex),
        )
        onDispose { marker?.remove() }
    }
}

@Composable
internal fun MapNumberedMarker(
    googleMap: GoogleMap,
    latitude: Double,
    longitude: Double,
    number: Int,
    title: String? = null,
    zIndex: Float = DEFAULT_MARKER_Z_INDEX,
    backgroundColor: Int = NumberedMarkerBackgroundColor,
    borderColor: Int = NumberedMarkerBorderColor,
    textColor: Int = NumberedMarkerTextColor,
) {
    val icon = rememberNumberedMarkerIcon(
        number = number,
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        textColor = textColor,
    )

    DisposableEffect(googleMap, latitude, longitude, number, title, zIndex, icon) {
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title(title)
                .anchor(0.5f, 0.5f)
                .icon(icon)
                .zIndex(zIndex),
        )
        onDispose { marker?.remove() }
    }
}

@Composable
internal fun MapWaypointNumberedMarker(
    googleMap: GoogleMap,
    latitude: Double,
    longitude: Double,
    number: Int,
    title: String? = null,
    zIndex: Float = DEFAULT_MARKER_Z_INDEX,
) {
    MapNumberedMarker(
        googleMap = googleMap,
        latitude = latitude,
        longitude = longitude,
        number = number,
        title = title,
        zIndex = zIndex,
        backgroundColor = Color.WHITE,
        borderColor = Color.BLACK,
        textColor = Color.BLACK,
    )
}

@Composable
internal fun MapOriginMarker(
    googleMap: GoogleMap,
    latitude: Double,
    longitude: Double,
    zIndex: Float = DEFAULT_MARKER_Z_INDEX,
) {
    val icon = rememberOriginMarkerIcon()

    DisposableEffect(googleMap, latitude, longitude, zIndex, icon) {
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

/**
 * 出発地 dot の vector drawable を GoogleMap marker 用の bitmap descriptor に変換する。
 *
 * @return 出発地 marker に設定する bitmap descriptor
 */
@Composable
private fun rememberOriginMarkerIcon(): BitmapDescriptor {
    return rememberMapComposeBitmapDescriptor("origin-dot") {
        Image(
            modifier = Modifier.size(OriginMarkerSize),
            painter = painterResource(Res.drawable.ic_origin_dot),
            contentDescription = null,
        )
    }
}

@Composable
private fun rememberNumberedMarkerIcon(
    number: Int,
    backgroundColor: Int,
    borderColor: Int,
    textColor: Int,
): BitmapDescriptor {
    val density = LocalDensity.current
    val text = remember(number) { number.toString() }

    return remember(density, text, backgroundColor, borderColor, textColor) {
        createNumberedMarkerIcon(
            text = text,
            density = density,
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            textColor = textColor,
        )
    }
}

private fun createNumberedMarkerIcon(
    text: String,
    density: Density,
    backgroundColor: Int,
    borderColor: Int,
    textColor: Int,
): BitmapDescriptor {
    val bitmapSize = with(density) { NumberedMarkerBitmapSize.roundToPx() }
    val circleRadius = with(density) { NumberedMarkerCircleRadius.toPx() }
    val borderWidth = with(density) { NumberedMarkerBorderWidth.toPx() }
    val shadowRadius = with(density) { NumberedMarkerShadowRadius.toPx() }
    val shadowOffsetY = with(density) { NumberedMarkerShadowOffsetY.toPx() }
    val textSize = with(density) {
        if (text.length >= 3) {
            NumberedMarkerSmallTextSize.toPx()
        } else {
            NumberedMarkerTextSize.toPx()
        }
    }

    val center = bitmapSize / 2f
    val bitmap = createBitmap(
        width = bitmapSize,
        height = bitmapSize,
    )

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColor
        style = Paint.Style.FILL
        setShadowLayer(
            shadowRadius,
            0f,
            shadowOffsetY,
            NumberedMarkerShadowColor,
        )
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        this.textSize = textSize
    }

    bitmap.applyCanvas {
        drawCircle(center, center, circleRadius, fillPaint)
        fillPaint.clearShadowLayer()
        drawCircle(center, center, circleRadius, fillPaint)
        drawCircle(center, center, circleRadius - borderWidth / 2f, borderPaint)

        val baseline = center - (textPaint.ascent() + textPaint.descent()) / 2f
        drawText(text, center, baseline, textPaint)
    }

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private const val DEFAULT_MARKER_Z_INDEX = 10_000f

/** 出発地 dot marker の表示サイズ（vector の余白を含む全体）。 */
private val OriginMarkerSize = 36.dp

private val NumberedMarkerBitmapSize = 44.dp
private val NumberedMarkerCircleRadius = 16.dp
private val NumberedMarkerBorderWidth = 2.dp
private val NumberedMarkerShadowRadius = 4.dp
private val NumberedMarkerShadowOffsetY = 2.dp
private val NumberedMarkerTextSize = 15.sp
private val NumberedMarkerSmallTextSize = 12.sp
private val NumberedMarkerBackgroundColor = Color.rgb(211, 47, 47)

/** 通常 numbered marker の枠線色。 */
private val NumberedMarkerBorderColor = Color.WHITE

/** 通常 numbered marker の文字色。 */
private val NumberedMarkerTextColor = Color.WHITE

private val NumberedMarkerShadowColor = Color.argb(96, 0, 0, 0)
