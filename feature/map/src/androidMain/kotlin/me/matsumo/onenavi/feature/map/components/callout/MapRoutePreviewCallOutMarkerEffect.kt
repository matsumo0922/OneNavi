package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_minute
import org.jetbrains.compose.resources.stringResource

/**
 * ルートプレビュー用の仮 CallOut marker effect。
 *
 * 中身 UI は [content] で差し替える。
 */
@Composable
internal fun MapRoutePreviewCallOutMarkerEffect(
    googleMap: GoogleMap?,
    routePreviewState: RoutePreviewState.Ready?,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    horizontalViewportPadding: Dp,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (
        routeIndex: Int,
        route: RouteDetail,
        tailSide: MapCallOutTailSide,
        isSelected: Boolean,
    ) -> Unit = { _, route, tailSide, isSelected ->
        MapRoutePreviewPlaceholderCallOut(
            tailSide = tailSide,
            routeDetail = route,
            isSelected = isSelected,
        )
    },
) {
    if (googleMap == null || routePreviewState == null) return

    val density = LocalDensity.current
    val topPadding = with(density) { topAppBarHeightPx.toDp() } + 12.dp

    val items = remember(routePreviewState.routes, routePreviewState.selectedIndex) {
        routePreviewState.routes.mapIndexedNotNull { index, route ->
            route.toCallOutRequest(index, routePreviewState.selectedIndex)?.let { request ->
                RoutePreviewCallOutItem(
                    routeIndex = index,
                    route = route,
                    request = request,
                )
            }
        }
    }

    val requests = remember(items) {
        items.map { it.request }.toImmutableList()
    }

    MapCallOutMarkerEffect(
        modifier = modifier,
        googleMap = googleMap,
        requests = requests,
        viewportPadding = PaddingValues(
            start = horizontalViewportPadding + 12.dp,
            top = topPadding,
            end = horizontalViewportPadding + 12.dp,
            bottom = bottomSheetPeekHeight + 12.dp,
        ),
        onCallOutClick = { index, _ ->
            items.getOrNull(index)?.let { item ->
                onRouteSelected(item.routeIndex)
            }
        },
    ) { index, _, tailSide ->
        val item = items.getOrNull(index)

        if (item != null) {
            content(
                item.routeIndex,
                item.route,
                tailSide,
                item.routeIndex == routePreviewState.selectedIndex,
            )
        }
    }
}

@Composable
private fun MapRoutePreviewPlaceholderCallOut(
    tailSide: MapCallOutTailSide,
    routeDetail: RouteDetail,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val duration = formatDuration(
        totalSeconds = routeDetail.durationSeconds,
        dayLabel = stringResource(Res.string.common_unit_day),
        hourLabel = stringResource(Res.string.common_unit_hour),
        minuteLabel = stringResource(Res.string.common_unit_minute),
    )

    MapSelectedCallOutContentFrame(
        modifier = modifier,
        tailSide = tailSide,
        isSelected = isSelected,
    ) { contentColor ->
        Column {
            Text(
                text = duration,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )

            routeDetail.tollFee?.let {
                Text(
                    text = formatYen(it),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                )
            }
        }
    }
}

private fun RouteDetail.toCallOutRequest(
    routeIndex: Int,
    selectedRouteIndex: Int,
): MapCallOutRequest? {
    if (geometry.size < MIN_ROUTE_CALLOUT_POLYLINE_POINTS) return null

    val isSelected = routeIndex == selectedRouteIndex

    return MapCallOutRequest(
        id = "route-$routeIndex-$id",
        target = MapCallOutTarget.PolylineMovable(geometry.toImmutableList()),
        priority = routeIndex,
        zIndexPriority = if (isSelected) SELECTED_ROUTE_CALLOUT_Z_INDEX_PRIORITY else routeIndex,
        contentKey = if (isSelected) SELECTED_CONTENT_KEY else UNSELECTED_CONTENT_KEY,
    )
}

private const val MIN_ROUTE_CALLOUT_POLYLINE_POINTS = 2
private const val SELECTED_ROUTE_CALLOUT_Z_INDEX_PRIORITY = 100
private const val SELECTED_CONTENT_KEY = "selected"
private const val UNSELECTED_CONTENT_KEY = "unselected"

/**
 * ルート index と CallOut request の対応。
 */
private class RoutePreviewCallOutItem(
    val routeIndex: Int,
    val route: RouteDetail,
    val request: MapCallOutRequest,
)
