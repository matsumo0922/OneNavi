package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mapbox.annotation.MapboxExperimental
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.viewannotation.annotationAnchor
import com.mapbox.maps.viewannotation.geometry
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import me.matsumo.onenavi.core.ui.theme.center

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(MapboxExperimental::class)
@Composable
internal fun HomeMapNumberedPin(
    point: Point,
    number: Int,
    modifier: Modifier = Modifier,
) {
    ViewAnnotation(
        modifier = modifier,
        options = viewAnnotationOptions {
            geometry(point)
            annotationAnchor {
                anchor(com.mapbox.maps.ViewAnnotationAnchor.BOTTOM)
            }
            allowOverlap(true)
            allowOverlapWithPuck(true)
        },
    ) {
        HomeMapNumberedPinContent(
            number = number,
        )
    }
}

@Composable
internal fun HomeMapNumberedPinContent(
    number: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Red,
        border = BorderStroke(2.dp, Color.White),
        shadowElevation = 4.dp,
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium.center(),
                color = Color.White,
            )
        }
    }
}
