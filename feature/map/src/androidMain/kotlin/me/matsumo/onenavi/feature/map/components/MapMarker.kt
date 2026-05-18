package me.matsumo.onenavi.feature.map.components

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
) {
    val icon = rememberNumberedMarkerIcon(number = number)

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
private fun rememberNumberedMarkerIcon(number: Int): BitmapDescriptor {
    val density = LocalDensity.current
    val text = remember(number) { number.toString() }

    return remember(density, text) {
        createNumberedMarkerIcon(
            text = text,
            density = density,
        )
    }
}

private fun createNumberedMarkerIcon(
    text: String,
    density: Density,
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
        color = NumberedMarkerBackgroundColor
        style = Paint.Style.FILL
        setShadowLayer(
            shadowRadius,
            0f,
            shadowOffsetY,
            NumberedMarkerShadowColor,
        )
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
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
private val NumberedMarkerBitmapSize = 44.dp
private val NumberedMarkerCircleRadius = 16.dp
private val NumberedMarkerBorderWidth = 2.dp
private val NumberedMarkerShadowRadius = 4.dp
private val NumberedMarkerShadowOffsetY = 2.dp
private val NumberedMarkerTextSize = 15.sp
private val NumberedMarkerSmallTextSize = 12.sp
private val NumberedMarkerBackgroundColor = Color.rgb(211, 47, 47)
private val NumberedMarkerShadowColor = Color.argb(96, 0, 0, 0)
