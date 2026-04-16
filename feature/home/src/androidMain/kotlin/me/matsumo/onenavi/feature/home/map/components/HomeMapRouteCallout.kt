package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_route_result_general_road
import me.matsumo.onenavi.core.resource.home_map_route_result_toll_road
import me.matsumo.onenavi.feature.home.map.RouteResult
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeMapRouteCallout(
    position: LatLng,
    routeResult: RouteResult,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val tollRoadLabel = stringResource(Res.string.home_map_route_result_toll_road)
    val generalRoadLabel = stringResource(Res.string.home_map_route_result_general_road)
    val style = RouteCalloutStyle.forRoute(isPrimary)
    val tollFee = routeResult.item.tollFee

    val durationText = formatDuration(
        totalSeconds = routeResult.item.durationSeconds,
        dayLabel = dayLabel,
        hourLabel = hourLabel,
        minuteLabel = minuteLabel,
    )
    val tollText = when {
        tollFee != null -> formatYen(tollFee)
        routeResult.item.hasTolls -> tollRoadLabel
        else -> generalRoadLabel
    }

    MarkerComposable(
        keys = arrayOf<Any>(
            position,
            routeResult.item.durationSeconds,
            tollFee ?: -1,
            routeResult.item.hasTolls,
            isPrimary,
        ),
        state = MarkerState(position = position),
        anchor = androidx.compose.ui.geometry.Offset(0.5f, 1f),
        zIndex = if (isPrimary) 3f else 2f,
        onClick = {
            onClick()
            true
        },
    ) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = style.backgroundColor,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = durationText,
                        color = style.textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tollText,
                        color = style.textColor.copy(alpha = if (isPrimary) 0.92f else 0.78f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Canvas(
                modifier = Modifier.size(width = 18.dp, height = 10.dp),
            ) {
                val path = Path().apply {
                    moveTo(size.width / 2f, size.height)
                    lineTo(0f, 0f)
                    lineTo(size.width, 0f)
                    close()
                }
                drawPath(
                    path = path,
                    color = style.backgroundColor,
                )
            }
        }
    }
}

private data class RouteCalloutStyle(
    val backgroundColor: Color,
    val textColor: Color,
) {
    companion object {
        fun forRoute(isPrimary: Boolean): RouteCalloutStyle {
            return if (isPrimary) {
                RouteCalloutStyle(
                    backgroundColor = Color(0xFF4285F4),
                    textColor = Color.White,
                )
            } else {
                RouteCalloutStyle(
                    backgroundColor = Color.White,
                    textColor = Color(0xFF202124),
                )
            }
        }
    }
}
