package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapbox.annotation.MapboxExperimental
import com.mapbox.geojson.Point
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.viewannotation.annotationAnchor
import com.mapbox.maps.viewannotation.geometry
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import me.matsumo.onenavi.core.ui.theme.center

private val SELECTED_BACKGROUND = Color(0xFF4285F4)
private val UNSELECTED_BACKGROUND = Color.White

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(MapboxExperimental::class)
@Composable
internal fun HomeMapRouteBubble(
    point: Point,
    durationText: String,
    tollLabel: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    ViewAnnotation(
        modifier = modifier,
        options = viewAnnotationOptions {
            geometry(point)
            annotationAnchor {
                anchor(ViewAnnotationAnchor.CENTER)
            }
            allowOverlap(true)
            allowOverlapWithPuck(true)
        },
    ) {
        HomeMapRouteBubbleContent(
            durationText = durationText,
            tollLabel = tollLabel,
            isSelected = isSelected,
        )
    }
}

@Composable
private fun HomeMapRouteBubbleContent(
    durationText: String,
    tollLabel: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isSelected) SELECTED_BACKGROUND else UNSELECTED_BACKGROUND
    val contentColor = if (isSelected) Color.White else Color.Black

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 10.dp,
                vertical = 6.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelLarge.center(),
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )

            Text(
                text = tollLabel,
                style = MaterialTheme.typography.labelSmall.center(),
                color = contentColor,
            )
        }
    }
}
