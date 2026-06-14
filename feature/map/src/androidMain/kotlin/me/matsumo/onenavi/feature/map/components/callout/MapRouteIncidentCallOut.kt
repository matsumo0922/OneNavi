package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteIncidentMarker
import me.matsumo.onenavi.core.model.RouteIncidentMarkerCategory

/**
 * ルートインシデント（事故 / 規制）marker effect。
 *
 * [routeIncidents] の各インシデントを [MapCallOutMarkerEffect] 経由で CallOut として表示する。
 * [cameraZoom] が [INCIDENT_CALLOUT_MIN_ZOOM] 未満の場合は非表示。
 * 現在地以降のインシデントのみを対象に、最大 [INCIDENT_CALLOUT_MAX_COUNT] 件表示する。
 *
 * @param googleMap CallOut marker 描画先の GoogleMap
 * @param routeIncidents 描画対象のインシデント一覧
 * @param routeProgressMeters ルート上の現在地累積距離。null の場合はルート先頭から表示する
 * @param cameraZoom 現在の GoogleMap zoom
 * @param modifier callout overlay 用 modifier
 */
@Composable
internal fun MapRouteIncidentCallOutMarkerEffect(
    googleMap: GoogleMap?,
    routeIncidents: ImmutableList<RouteIncidentMarker>,
    routeProgressMeters: Double?,
    cameraZoom: Float,
    modifier: Modifier = Modifier,
) {
    if (googleMap == null) return
    if (cameraZoom < INCIDENT_CALLOUT_MIN_ZOOM) return

    val visibleIncidents = visibleRouteIncidents(
        incidents = routeIncidents,
        routeProgressMeters = routeProgressMeters,
    )
    if (visibleIncidents.isEmpty()) return

    val requests = remember(visibleIncidents) {
        visibleIncidents.mapIndexed { index, incident ->
            incident.toCallOutRequest(index = index)
        }.toImmutableList()
    }

    MapCallOutMarkerEffect(
        modifier = modifier,
        googleMap = googleMap,
        requests = requests,
        viewportPadding = INCIDENT_CALLOUT_VIEWPORT_PADDING,
        onCallOutClick = { _, _ -> },
    ) { index, _, tailSide ->
        val incident = visibleIncidents.getOrNull(index)

        if (incident != null) {
            MapRouteIncidentCallOut(
                incident = incident,
                tailSide = tailSide,
            )
        }
    }
}

@Composable
private fun MapRouteIncidentCallOut(
    incident: RouteIncidentMarker,
    tailSide: MapCallOutTailSide,
    modifier: Modifier = Modifier,
) {
    MapCallOut(
        modifier = modifier,
        tailSide = tailSide,
        backgroundColor = Color.White,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(INCIDENT_CALLOUT_ICON_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IncidentCategoryBadge(category = incident.category)

            Text(
                text = incident.displayText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun IncidentCategoryBadge(
    category: RouteIncidentMarkerCategory,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = incidentBadgeBackgroundColor(category)
    val iconTint = incidentBadgeIconTint(category)
    val icon = incidentBadgeIcon(category)

    Box(
        modifier = modifier
            .size(INCIDENT_BADGE_SIZE)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = Modifier.size(INCIDENT_BADGE_ICON_SIZE),
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
        )
    }
}

@Composable
private fun incidentBadgeBackgroundColor(category: RouteIncidentMarkerCategory): Color = when (category) {
    RouteIncidentMarkerCategory.Accident -> MaterialTheme.colorScheme.error
    RouteIncidentMarkerCategory.Regulation -> IncidentRegulationBadgeColor
}

private fun incidentBadgeIconTint(category: RouteIncidentMarkerCategory): Color = when (category) {
    RouteIncidentMarkerCategory.Accident -> Color.White
    RouteIncidentMarkerCategory.Regulation -> IncidentRegulationIconTint
}

private fun incidentBadgeIcon(category: RouteIncidentMarkerCategory): ImageVector = when (category) {
    RouteIncidentMarkerCategory.Accident -> Icons.Default.CarCrash
    RouteIncidentMarkerCategory.Regulation -> Icons.Default.ErrorOutline
}

private fun RouteIncidentMarker.toCallOutRequest(index: Int): MapCallOutRequest {
    val priority = INCIDENT_CALLOUT_PRIORITY_BASE - index

    return MapCallOutRequest(
        id = "incident-${coord.latitude}-${coord.longitude}-$index",
        target = MapCallOutTarget.PointFixed(coord),
        priority = priority,
        zIndexPriority = priority,
        contentKey = "${category.name}|$displayText",
    )
}

private fun visibleRouteIncidents(
    incidents: ImmutableList<RouteIncidentMarker>,
    routeProgressMeters: Double?,
): List<RouteIncidentMarker> {
    val minimumDistanceFromStartMeters = routeProgressMeters
        ?.coerceAtLeast(0.0)
        ?.toInt()

    if (minimumDistanceFromStartMeters == null) {
        return incidents.take(INCIDENT_CALLOUT_MAX_COUNT)
    }

    return incidents
        .asSequence()
        .filter { incident -> incident.distanceFromStartMeters >= minimumDistanceFromStartMeters }
        .take(INCIDENT_CALLOUT_MAX_COUNT)
        .toList()
}

/** 規制インシデントバッジの背景色。 */
private val IncidentRegulationBadgeColor = Color(0xFFFFD113)

/** 規制インシデントバッジのアイコン色。 */
private val IncidentRegulationIconTint = Color.Black.copy(alpha = 0.75f)

private val INCIDENT_BADGE_SIZE = 24.dp
private val INCIDENT_BADGE_ICON_SIZE = 16.dp
private val INCIDENT_CALLOUT_ICON_SPACING = 6.dp

private val INCIDENT_CALLOUT_VIEWPORT_PADDING = PaddingValues(
    horizontal = 12.dp,
    vertical = 12.dp,
)

/** インシデント CallOut を表示する最小 zoom。 */
private const val INCIDENT_CALLOUT_MIN_ZOOM = 12f

/** 同時に描画するインシデント CallOut の最大件数。 */
private const val INCIDENT_CALLOUT_MAX_COUNT = 5

/** インシデント CallOut の配置優先度起点。 */
private const val INCIDENT_CALLOUT_PRIORITY_BASE = 100
