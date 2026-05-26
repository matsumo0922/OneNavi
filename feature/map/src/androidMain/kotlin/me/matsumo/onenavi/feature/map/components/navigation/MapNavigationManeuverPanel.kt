package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.presentation.BannerSupport
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListItem
import me.matsumo.onenavi.core.navigation.newguidance.presentation.LaneCell
import me.matsumo.onenavi.core.navigation.newguidance.presentation.LanePresentation
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverBanner
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_navigation_followup
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import me.matsumo.onenavi.core.ui.theme.RouteColors
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

@Composable
internal fun MapNavigationManeuverPanel(
    banner: ManeuverBanner,
    listItems: ImmutableList<GuidanceListItem>,
    progress: GuidanceProgress,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val followupLabel = stringResource(Res.string.home_map_navigation_followup)
    var showPanel by rememberSaveable { mutableStateOf(false) }

    val laneCells = bannerLaneCells(banner.support)
    val followupCallout = bannerFollowupCallout(banner.support)
    val hasLanes = laneCells != null
    val hasHint = followupCallout != null
    val topShape = when {
        hasLanes -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        hasHint -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 0.dp,
            bottomEnd = 16.dp,
        )
        else -> RoundedCornerShape(16.dp)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (banner.hasMoreEvents && showPanel) {
            MapNavigationManeuverPanelSection(
                modifier = Modifier.fillMaxWidth(),
                listItems = listItems,
                hazeState = hazeState,
                meterLabel = meterLabel,
                kilometerLabel = kilometerLabel,
                dayLabel = dayLabel,
                hourLabel = hourLabel,
                minuteLabel = minuteLabel,
                timestampMillis = progress.locationTimestampMillis,
                elapsedSeconds = progress.elapsedSeconds,
                traveledMeters = progress.traveledMeters,
                onDismissPanelClicked = { showPanel = false },
            )
        } else {
            MapNavigationManeuverTopSection(
                modifier = Modifier.fillMaxWidth(),
                banner = banner,
                meterLabel = meterLabel,
                kilometerLabel = kilometerLabel,
                showPanelItems = showPanel,
                shape = topShape,
                onShowPanelItemsClicked = { showPanel = true },
            )

            MapNavigationManeuverBottomSection(
                modifier = Modifier.fillMaxWidth(),
                laneCells = laneCells,
                followupCallout = followupCallout,
                followupLabel = followupLabel,
                roadClass = banner.roadClass,
            )
        }
    }
}

/**
 * バナー下段に視覚レーンがあればそのレーンセルを返す。レーン以外の補助なら null。
 */
private fun bannerLaneCells(support: BannerSupport?): ImmutableList<LaneCell>? {
    val laneSupport = support as? BannerSupport.Lanes ?: return null
    val visualLanes = laneSupport.lane as? LanePresentation.VisualLanes ?: return null
    return visualLanes.lanes
}

/**
 * バナー下段がフォローアップ案内ならその主案内を返す。それ以外は null。
 */
private fun bannerFollowupCallout(support: BannerSupport?): ManeuverCallout? {
    val followupSupport = support as? BannerSupport.Followup ?: return null
    return followupSupport.maneuver
}

@Composable
private fun MapNavigationManeuverTopSection(
    banner: ManeuverBanner,
    meterLabel: String,
    kilometerLabel: String,
    showPanelItems: Boolean,
    shape: Shape,
    onShowPanelItemsClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maneuver = banner.primary
    val guidanceLabel = banner.secondaryLabel
    val distanceText = remember(maneuver.distanceToManeuverMeters, meterLabel, kilometerLabel) {
        formatGuidanceDistance(
            meters = maneuver.distanceToManeuverMeters.toDouble(),
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }
    val panelColors = RouteColors.accent(banner.roadClass)
    val contentColor = panelColors.onPrimary

    Surface(
        modifier = modifier
            .zIndex(1f)
            .shadow(
                elevation = 8.dp,
                shape = shape,
            ),
        shape = shape,
        color = panelColors.primary,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapNavigationManeuverTurnIcon(
                modifier = Modifier.size(48.dp),
                maneuver = maneuver,
                tint = contentColor,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = distanceText,
                    color = contentColor,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (guidanceLabel != null) {
                    Text(
                        text = guidanceLabel,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (banner.hasMoreEvents) {
                IconButton(onShowPanelItemsClicked) {
                    Icon(
                        imageVector = if (showPanelItems) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = contentColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNavigationManeuverBottomSection(
    laneCells: ImmutableList<LaneCell>?,
    followupCallout: ManeuverCallout?,
    followupLabel: String,
    roadClass: RoadClass,
    modifier: Modifier = Modifier,
) {
    when {
        laneCells != null -> {
            val panelColors = RouteColors.accent(roadClass)
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = panelColors.primary,
            ) {
                MapNavigationManeuverLaneRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    lanes = laneCells,
                    activeTint = panelColors.onPrimary,
                    inactiveTint = panelColors.onPrimary.copy(alpha = SecondaryContentAlpha),
                )
            }
        }

        followupCallout != null -> {
            val panelColors = RouteColors.accent(roadClass)
            Surface(
                modifier = modifier.wrapContentWidth(Alignment.Start),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 12.dp,
                ),
                color = panelColors.primary,
            ) {
                MapNavigationManeuverFollowupHint(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    maneuver = followupCallout,
                    label = followupLabel,
                    contentColor = panelColors.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationManeuverLaneRow(
    lanes: ImmutableList<LaneCell>,
    activeTint: Color,
    inactiveTint: Color,
    modifier: Modifier = Modifier,
) {
    MapNavigationManeuverLaneIcons(
        modifier = modifier,
        lanes = lanes,
        iconSize = ManeuverLaneIconSize,
        spacing = ManeuverLaneIconSpacing,
        horizontalAlignment = Alignment.CenterHorizontally,
        activeTint = activeTint,
        inactiveTint = inactiveTint,
    )
}

@Composable
private fun MapNavigationManeuverFollowupHint(
    maneuver: ManeuverCallout,
    label: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
        )

        MapNavigationManeuverTurnIcon(
            modifier = Modifier.size(20.dp),
            maneuver = maneuver,
            tint = contentColor,
        )
    }
}

@Composable
private fun MapNavigationManeuverTurnIcon(
    maneuver: ManeuverCallout,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    ManeuverIcon(
        modifier = modifier,
        type = maneuver.type,
        maneuverModifier = maneuver.modifier,
        contentDescription = null,
        tint = tint,
    )
}

/**
 * ナビ表示用に距離を 10m 単位へ切り捨ててからフォーマットする。
 */
private fun formatGuidanceDistance(
    meters: Double,
    meterLabel: String,
    kilometerLabel: String,
): String {
    val flooredMeters = floor(meters / 10.0) * 10.0
    return formatDistance(
        meters = flooredMeters,
        meterLabel = meterLabel,
        kilometerLabel = kilometerLabel,
    )
}

private val ManeuverLaneIconSize = 36.dp
private val ManeuverLaneIconSpacing = 12.dp
