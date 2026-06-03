package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePriority
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
import me.matsumo.onenavi.core.resource.home_map_navigation_rerouting_title
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import me.matsumo.onenavi.core.ui.theme.RouteColors
import me.matsumo.onenavi.feature.map.state.NAVIGATION_GUIDE_IMAGE_VISIBILITY_METERS
import me.matsumo.onenavi.feature.map.state.NavigationGuideImage
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

@Composable
internal fun MapNavigationManeuverPanel(
    route: RouteDetail,
    banner: ManeuverBanner,
    listItems: ImmutableList<GuidanceListItem>,
    progress: GuidanceProgress,
    guideImage: NavigationGuideImage?,
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
    val followupCallout = banner.followup ?: bannerFollowupCallout(banner.support)
    val hasGuideImageKey = banner.signpostImageKey != null
    val isGuideImageInVisibleRange = banner.primary.distanceToManeuverMeters <= NAVIGATION_GUIDE_IMAGE_VISIBILITY_METERS
    val visibleGuideImage = guideImage.takeIf { isGuideImageInVisibleRange }
    val shouldPreferFollowupHint = hasGuideImageKey && !isGuideImageInVisibleRange
    val hasLanes = laneCells != null
    val hasPrioritizedHint = shouldPreferFollowupHint && followupCallout != null
    val hasHint = followupCallout != null
    val hasGuideImage = visibleGuideImage != null
    val hasPanelItems = banner.hasMoreEvents || route.geometry.isNotEmpty()
    val topShape = when {
        hasGuideImage || showPanel -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        hasPrioritizedHint -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 0.dp,
            bottomEnd = 16.dp,
        )
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
        MapNavigationManeuverTopSection(
            modifier = Modifier.fillMaxWidth(),
            banner = banner,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
            showPanelItems = showPanel,
            hasPanelItems = hasPanelItems,
            shape = topShape,
            onShowPanelItemsClicked = { showPanel = !showPanel },
        )

        if (hasPanelItems && showPanel) {
            MapNavigationManeuverPanelSection(
                modifier = Modifier.fillMaxWidth(),
                route = route,
                listItems = listItems,
                hazeState = hazeState,
                meterLabel = meterLabel,
                kilometerLabel = kilometerLabel,
                dayLabel = dayLabel,
                hourLabel = hourLabel,
                minuteLabel = minuteLabel,
                timestampMillis = progress.locationTimestampMillis,
                currentCumulativeMeters = progress.currentCumulativeMeters,
                elapsedSeconds = progress.elapsedSeconds,
                traveledMeters = progress.traveledMeters,
            )
        } else {
            MapNavigationManeuverBottomSection(
                modifier = Modifier.fillMaxWidth(),
                guideImage = visibleGuideImage,
                shouldPreferFollowupHint = shouldPreferFollowupHint,
                laneCells = laneCells,
                followupCallout = followupCallout,
                followupLabel = followupLabel,
                roadClass = banner.roadClass,
            )
        }
    }
}

@Composable
internal fun MapNavigationReroutingPanel(
    routePriority: RoutePriority?,
    roadClass: RoadClass,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.home_map_navigation_rerouting_title)
    val routeModeLabel = (routePriority ?: RoutePriority.Recommended).label

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MapNavigationReroutingTopSection(
            modifier = Modifier.fillMaxWidth(),
            title = title,
            subtitle = routeModeLabel,
            roadClass = roadClass,
            shape = RoundedCornerShape(16.dp),
        )
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
    hasPanelItems: Boolean,
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
    val panelColors = RouteColors.maneuver(banner.roadClass)
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

            if (hasPanelItems) {
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
private fun MapNavigationReroutingTopSection(
    title: String,
    subtitle: String,
    roadClass: RoadClass,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val panelColors = RouteColors.maneuver(roadClass)
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
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = contentColor,
                strokeWidth = 3.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    color = contentColor,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = subtitle,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationManeuverBottomSection(
    guideImage: NavigationGuideImage?,
    shouldPreferFollowupHint: Boolean,
    laneCells: ImmutableList<LaneCell>?,
    followupCallout: ManeuverCallout?,
    followupLabel: String,
    roadClass: RoadClass,
    modifier: Modifier = Modifier,
) {
    when {
        guideImage != null -> {
            val panelColors = RouteColors.maneuver(roadClass)
            Surface(
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = panelColors.container,
            ) {
                MapNavigationManeuverGuideImage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ManeuverGuideImageMaxHeight),
                    guideImage = guideImage,
                )
            }
        }

        shouldPreferFollowupHint && followupCallout != null -> {
            val panelColors = RouteColors.maneuver(roadClass)
            Surface(
                modifier = modifier.wrapContentWidth(Alignment.Start),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
                color = panelColors.container,
            ) {
                MapNavigationManeuverFollowupHint(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    maneuver = followupCallout,
                    label = followupLabel,
                    contentColor = panelColors.onContainer,
                )
            }
        }

        laneCells != null -> {
            val panelColors = RouteColors.maneuver(roadClass)
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = panelColors.container,
            ) {
                MapNavigationManeuverLaneRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    lanes = laneCells,
                    activeTint = panelColors.onContainer,
                    inactiveTint = panelColors.onContainer.copy(alpha = SecondaryContentAlpha),
                )
            }
        }

        followupCallout != null -> {
            val panelColors = RouteColors.maneuver(roadClass)
            Surface(
                modifier = modifier.wrapContentWidth(Alignment.Start),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
                color = panelColors.container,
            ) {
                MapNavigationManeuverFollowupHint(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    maneuver = followupCallout,
                    label = followupLabel,
                    contentColor = panelColors.onContainer,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationManeuverGuideImage(
    guideImage: NavigationGuideImage,
    modifier: Modifier = Modifier,
) {
    Image(
        modifier = modifier.padding(horizontal = 24.dp),
        bitmap = guideImage.bitmap,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
    )
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

/** 案内画像がバナー下段で占有できる最大高さ。 */
private val ManeuverGuideImageMaxHeight = 240.dp
