package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceManeuverInfo
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon

/**
 * 案内地点に固定した maneuver CallOut marker effect。
 */
@Composable
internal fun MapGuidanceManeuverCallOutMarkerEffect(
    googleMap: GoogleMap?,
    guidanceState: GuidanceState,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (googleMap == null) return

    val guiding = guidanceState as? GuidanceState.Guiding ?: return
    val density = LocalDensity.current
    val topPadding = with(density) { topAppBarHeightPx.toDp() } + GUIDANCE_CALLOUT_VIEWPORT_PADDING
    val nextManeuver = guiding.progress.nextManeuver
    val followupManeuver = guiding.progress.followupManeuver

    val maneuvers = remember(
        guiding.route.id,
        nextManeuver?.guidancePointIndex,
        followupManeuver?.guidancePointIndex,
    ) {
        listOfNotNull(
            nextManeuver,
            followupManeuver,
        )
    }

    val requests = remember(guiding.route.id, maneuvers) {
        maneuvers.mapIndexed { index, maneuver ->
            maneuver.toCallOutRequest(
                routeId = guiding.route.id,
                order = index,
            )
        }.toImmutableList()
    }

    MapCallOutMarkerEffect(
        modifier = modifier,
        googleMap = googleMap,
        requests = requests,
        viewportPadding = PaddingValues(
            start = GUIDANCE_CALLOUT_VIEWPORT_PADDING,
            top = topPadding,
            end = GUIDANCE_CALLOUT_VIEWPORT_PADDING,
            bottom = bottomSheetPeekHeight + GUIDANCE_CALLOUT_VIEWPORT_PADDING,
        ),
        onCallOutClick = { _, _ -> },
    ) { index, _, tailSide ->
        val maneuver = maneuvers.getOrNull(index)

        if (maneuver != null) {
            MapGuidanceManeuverCallOut(
                maneuver = maneuver,
                tailSide = tailSide,
            )
        }
    }
}

@Composable
private fun MapGuidanceManeuverCallOut(
    maneuver: GuidanceManeuverInfo,
    tailSide: MapCallOutTailSide,
    modifier: Modifier = Modifier,
) {
    val label = maneuver.intersectionName?.takeIf { it.isNotBlank() }

    MapSelectedCallOutContentFrame(
        modifier = modifier,
        tailSide = tailSide,
        isSelected = true,
    ) { contentColor ->
        Row(
            modifier = Modifier.widthIn(max = GUIDANCE_CALLOUT_MAX_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(GUIDANCE_CALLOUT_CONTENT_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManeuverIcon(
                modifier = Modifier.size(GUIDANCE_CALLOUT_ICON_SIZE),
                type = maneuver.type,
                maneuverModifier = maneuver.modifier,
                contentDescription = null,
                tint = contentColor,
            )

            if (label != null) {
                Text(
                    modifier = Modifier.widthIn(max = GUIDANCE_CALLOUT_TEXT_MAX_WIDTH),
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                )
            }
        }
    }
}

private fun GuidanceManeuverInfo.toCallOutRequest(
    routeId: String,
    order: Int,
): MapCallOutRequest {
    val priority = GUIDANCE_CALLOUT_PRIORITY_BASE - order

    return MapCallOutRequest(
        id = "guidance-$routeId-$order-$guidancePointIndex",
        target = MapCallOutTarget.PointFixed(location),
        priority = priority,
        zIndexPriority = GUIDANCE_CALLOUT_Z_INDEX_PRIORITY_BASE - order,
        contentKey = listOf(
            routeId,
            order,
            guidancePointIndex,
            type.name,
            modifier.name,
            intersectionName.orEmpty(),
        ).joinToString(separator = CONTENT_KEY_SEPARATOR),
        allowsOffscreenPlacement = true,
    )
}

private val GUIDANCE_CALLOUT_VIEWPORT_PADDING = 12.dp
private val GUIDANCE_CALLOUT_ICON_SIZE = 20.dp
private val GUIDANCE_CALLOUT_CONTENT_SPACING = 6.dp
private val GUIDANCE_CALLOUT_MAX_WIDTH = 196.dp
private val GUIDANCE_CALLOUT_TEXT_MAX_WIDTH = 160.dp

private const val GUIDANCE_CALLOUT_PRIORITY_BASE = 200
private const val GUIDANCE_CALLOUT_Z_INDEX_PRIORITY_BASE = 200
private const val CONTENT_KEY_SEPARATOR = "|"
