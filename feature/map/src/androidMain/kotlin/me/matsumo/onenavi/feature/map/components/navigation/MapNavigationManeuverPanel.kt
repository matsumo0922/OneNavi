package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.car.CarDisplayInputTargetRect
import me.matsumo.onenavi.core.common.car.CarDisplayInputTargetReporter
import me.matsumo.onenavi.core.common.car.LocalCarDisplayInputTargetReporter
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
    isSplit: Boolean,
    availableHeight: Dp,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val followupLabel = stringResource(Res.string.home_map_navigation_followup)
    val currentRoadClass = progress.currentRoadClass
    var showPanel by rememberSaveable {
        mutableStateOf(resolveManeuverPanelExpandedState(currentRoadClass, false))
    }

    LaunchedEffect(currentRoadClass) {
        showPanel = resolveManeuverPanelExpandedState(currentRoadClass, showPanel)
    }

    val laneCells = bannerLaneCells(banner.support)
    val followupCallout = banner.followup ?: bannerFollowupCallout(banner.support)
    val hasGuideImageKey = banner.signpostImageKey != null
    val isGuideImageInVisibleRange = banner.primary.distanceToManeuverMeters <= NAVIGATION_GUIDE_IMAGE_VISIBILITY_METERS
    val visibleGuideImage = guideImage.takeIf { isGuideImageInVisibleRange }
    val shouldPreferFollowupHint = hasGuideImageKey && !isGuideImageInVisibleRange
    val hasPanelItems = banner.hasMoreEvents || route.geometry.isNotEmpty()
    val bottomContent = maneuverBottomContent(
        visibleGuideImage = visibleGuideImage,
        hasPanelItems = hasPanelItems,
        showPanel = showPanel,
        shouldPreferFollowupHint = shouldPreferFollowupHint,
        laneCells = laneCells,
        followupCallout = followupCallout,
    )
    val bottomSectionMaxHeight = navigationManeuverBottomSectionMaxHeight(
        isSplit = isSplit,
        availableHeight = availableHeight,
    )
    val topShape = navigationManeuverTopShape(content = bottomContent)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
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

        MapNavigationManeuverBottomContainer(
            modifier = Modifier.fillMaxWidth(),
            content = bottomContent,
            route = route,
            listItems = listItems,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
            timestampMillis = progress.locationTimestampMillis,
            currentCumulativeMeters = progress.currentCumulativeMeters,
            followupLabel = followupLabel,
            roadClass = banner.roadClass,
            isSplit = isSplit,
            maxHeight = bottomSectionMaxHeight,
            availableHeight = availableHeight,
        )
    }
}

@Composable
internal fun MapNavigationReroutingPanel(
    routePriority: RoutePriority?,
    roadClass: RoadClass,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    val title = stringResource(Res.string.home_map_navigation_rerouting_title)
    val routeModeLabel = (routePriority ?: RoutePriority.Recommended).label

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
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

/**
 * 現在道路種別から Maneuver パネルの展開状態を解決する。
 */
internal fun resolveManeuverPanelExpandedState(currentRoadClass: RoadClass, isExpanded: Boolean): Boolean {
    if (currentRoadClass == RoadClass.HIGHWAY) {
        return true
    }

    return isExpanded
}

private fun navigationManeuverBottomSectionMaxHeight(
    isSplit: Boolean,
    availableHeight: Dp,
): Dp {
    if (isSplit) {
        return (availableHeight / 2f)
            .coerceAtMost(ManeuverGuideImageMaxHeight)
            .coerceAtLeast(0.dp)
    }

    return ManeuverGuideImageMaxHeight
}

private fun maneuverBottomContent(
    visibleGuideImage: NavigationGuideImage?,
    hasPanelItems: Boolean,
    showPanel: Boolean,
    shouldPreferFollowupHint: Boolean,
    laneCells: ImmutableList<LaneCell>?,
    followupCallout: ManeuverCallout?,
): ManeuverBottomContent {
    return when (
        resolveManeuverBottomContentType(
            hasVisibleGuideImage = visibleGuideImage != null,
            hasPanelItems = hasPanelItems,
            showPanel = showPanel,
            shouldPreferFollowupHint = shouldPreferFollowupHint,
            hasLaneCells = laneCells != null,
            hasFollowupCallout = followupCallout != null,
        )
    ) {
        ManeuverBottomContentType.GuideImage -> ManeuverBottomContent.GuideImage(
            guideImage = requireNotNull(visibleGuideImage),
        )
        ManeuverBottomContentType.Panel -> ManeuverBottomContent.Panel
        ManeuverBottomContentType.Followup -> ManeuverBottomContent.Followup(
            maneuver = requireNotNull(followupCallout),
        )
        ManeuverBottomContentType.Lanes -> ManeuverBottomContent.Lanes(
            lanes = requireNotNull(laneCells),
        )
        ManeuverBottomContentType.None -> ManeuverBottomContent.None
    }
}

/**
 * Maneuver カード下段に表示するコンテンツ種別を優先度順に解決する。
 */
internal fun resolveManeuverBottomContentType(
    hasVisibleGuideImage: Boolean,
    hasPanelItems: Boolean,
    showPanel: Boolean,
    shouldPreferFollowupHint: Boolean,
    hasLaneCells: Boolean,
    hasFollowupCallout: Boolean,
): ManeuverBottomContentType {
    return when {
        hasVisibleGuideImage -> ManeuverBottomContentType.GuideImage
        hasPanelItems && showPanel -> ManeuverBottomContentType.Panel
        shouldPreferFollowupHint && hasFollowupCallout -> ManeuverBottomContentType.Followup
        hasLaneCells -> ManeuverBottomContentType.Lanes
        hasFollowupCallout -> ManeuverBottomContentType.Followup
        else -> ManeuverBottomContentType.None
    }
}

/**
 * Maneuver カード下段に表示するコンテンツ種別。
 */
internal enum class ManeuverBottomContentType {

    /** 案内画像。 */
    GuideImage,

    /** 案内地点リストのパネル。 */
    Panel,

    /** 通常のフォローアップ案内。 */
    Followup,

    /** 推奨レーン。 */
    Lanes,

    /** 下段なし。 */
    None,
}

private fun navigationManeuverTopShape(content: ManeuverBottomContent): Shape {
    if (content.isWideContent()) {
        return RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    }

    if (content is ManeuverBottomContent.Followup) {
        return RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 0.dp,
            bottomEnd = 16.dp,
        )
    }

    return RoundedCornerShape(16.dp)
}

@Composable
private fun MapNavigationManeuverBottomContainer(
    content: ManeuverBottomContent,
    route: RouteDetail,
    listItems: ImmutableList<GuidanceListItem>,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    currentCumulativeMeters: Double,
    followupLabel: String,
    roadClass: RoadClass,
    isSplit: Boolean,
    maxHeight: Dp,
    availableHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val panelColors = RouteColors.maneuver(roadClass)
    val bottomShape = content.bottomShape()
    val containerMaxHeight = content.bottomContainerMaxHeight(
        isSplit = isSplit,
        sectionMaxHeight = maxHeight,
        availableHeight = availableHeight,
    )

    Surface(
        modifier = modifier
            .maneuverBottomContainerLayout(
                content = content,
                maxHeight = containerMaxHeight,
                fillHeight = content.shouldFillBottomContainerHeight(isSplit),
            )
            .animateContentSize(),
        shape = bottomShape,
        color = panelColors.container,
    ) {
        AnimatedContent(
            targetState = content,
            transitionSpec = { fadeIn().togetherWith(fadeOut()) },
            contentKey = { targetContent -> targetContent::class },
            label = "ManeuverBottomContent",
        ) { targetContent ->
            MapNavigationManeuverBottomContent(
                content = targetContent,
                route = route,
                listItems = listItems,
                meterLabel = meterLabel,
                kilometerLabel = kilometerLabel,
                dayLabel = dayLabel,
                hourLabel = hourLabel,
                minuteLabel = minuteLabel,
                timestampMillis = timestampMillis,
                currentCumulativeMeters = currentCumulativeMeters,
                followupLabel = followupLabel,
                panelColors = panelColors,
                maxHeight = maxHeight,
                isSplit = isSplit,
                shouldReportPanelScrollTarget = content is ManeuverBottomContent.Panel && isSplit,
            )
        }
    }
}

private fun Modifier.maneuverBottomContainerLayout(
    content: ManeuverBottomContent,
    maxHeight: Dp,
    fillHeight: Boolean,
): Modifier {
    val constrainedModifier = if (fillHeight) {
        heightIn(max = maxHeight)
            .fillMaxHeight()
    } else {
        heightIn(max = maxHeight)
    }

    if (content is ManeuverBottomContent.Followup) {
        return constrainedModifier.wrapContentWidth(Alignment.Start)
    }

    return constrainedModifier.fillMaxWidth()
}

@Composable
private fun MapNavigationManeuverBottomContent(
    content: ManeuverBottomContent,
    route: RouteDetail,
    listItems: ImmutableList<GuidanceListItem>,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    currentCumulativeMeters: Double,
    followupLabel: String,
    panelColors: RouteColors.ManeuverColors,
    maxHeight: Dp,
    isSplit: Boolean,
    shouldReportPanelScrollTarget: Boolean,
    modifier: Modifier = Modifier,
) {
    val inputTargetReporter = LocalCarDisplayInputTargetReporter.current

    when (content) {
        is ManeuverBottomContent.GuideImage -> MapNavigationManeuverGuideImage(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            guideImage = content.guideImage,
        )

        ManeuverBottomContent.Panel -> {
            DisposableEffect(inputTargetReporter, shouldReportPanelScrollTarget) {
                if (!shouldReportPanelScrollTarget) {
                    inputTargetReporter.updateScrollTargetRect(null)
                }

                onDispose {
                    if (shouldReportPanelScrollTarget) {
                        inputTargetReporter.updateScrollTargetRect(null)
                    }
                }
            }

            MapNavigationManeuverPanelSection(
                modifier = modifier
                    .maneuverPanelContentLayout(isSplit)
                    .maneuverPanelScrollTargetLayout(
                        isEnabled = shouldReportPanelScrollTarget,
                        inputTargetReporter = inputTargetReporter,
                    ),
                route = route,
                listItems = listItems,
                meterLabel = meterLabel,
                kilometerLabel = kilometerLabel,
                dayLabel = dayLabel,
                hourLabel = hourLabel,
                minuteLabel = minuteLabel,
                timestampMillis = timestampMillis,
                currentCumulativeMeters = currentCumulativeMeters,
            )
        }

        is ManeuverBottomContent.Lanes -> MapNavigationManeuverLaneRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            lanes = content.lanes,
            activeTint = panelColors.onContainer,
            inactiveTint = panelColors.onContainer.copy(alpha = ManeuverLaneInactiveAlpha),
        )

        is ManeuverBottomContent.Followup -> MapNavigationManeuverFollowupHint(
            modifier = modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            maneuver = content.maneuver,
            label = followupLabel,
            contentColor = panelColors.onContainer,
        )

        ManeuverBottomContent.None -> Box(modifier = modifier.fillMaxWidth())
    }
}

/**
 * 下段コンテナの最大高さを返す。
 *
 * split の Panel は Android Auto の固定 scroll anchor を拾うため、案内画像用の高さ上限から分離する。
 */
private fun ManeuverBottomContent.bottomContainerMaxHeight(
    isSplit: Boolean,
    sectionMaxHeight: Dp,
    availableHeight: Dp,
): Dp {
    if (this is ManeuverBottomContent.Panel && isSplit) {
        return (availableHeight / 2f).coerceAtLeast(0.dp)
    }

    return sectionMaxHeight
}

/**
 * 下段コンテナを縦方向に充填するかどうかを返す。
 *
 * split の Panel では固定 scroll anchor が LazyColumn に当たるよう、Surface の hit target を広げる。
 */
private fun ManeuverBottomContent.shouldFillBottomContainerHeight(isSplit: Boolean): Boolean {
    return this is ManeuverBottomContent.Panel && isSplit
}

/**
 * Panel 本体のサイズを整える。
 *
 * split では項目数が少ない場合でも LazyColumn が固定 scroll anchor を覆うように縦方向を充填する。
 */
private fun Modifier.maneuverPanelContentLayout(isSplit: Boolean): Modifier {
    val widthModifier = fillMaxWidth()
    if (!isSplit) return widthModifier

    return widthModifier.fillMaxHeight()
}

private fun Modifier.maneuverPanelScrollTargetLayout(
    isEnabled: Boolean,
    inputTargetReporter: CarDisplayInputTargetReporter,
): Modifier {
    if (!isEnabled) return this

    return onGloballyPositioned { coordinates ->
        inputTargetReporter.updateScrollTargetRect(
            coordinates.toCarDisplayInputTargetRect(),
        )
    }
}

private fun LayoutCoordinates.toCarDisplayInputTargetRect(): CarDisplayInputTargetRect {
    val bounds = boundsInRoot()

    return CarDisplayInputTargetRect(
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
    )
}

/**
 * Maneuver カード下段の表示内容。優先度解決済みの結果だけを保持する。
 */
@Stable
private sealed interface ManeuverBottomContent {

    /**
     * 案内画像。
     *
     * @property guideImage 表示する案内画像。
     */
    @Stable
    data class GuideImage(
        val guideImage: NavigationGuideImage,
    ) : ManeuverBottomContent

    /** 案内地点リストのパネル。 */
    @Stable
    data object Panel : ManeuverBottomContent

    /**
     * 推奨レーン。
     *
     * @property lanes 表示するレーンセル一覧。
     */
    @Immutable
    data class Lanes(
        val lanes: ImmutableList<LaneCell>,
    ) : ManeuverBottomContent

    /**
     * 通常のフォローアップ案内。
     *
     * @property maneuver 表示するフォローアップ案内。
     */
    @Stable
    data class Followup(
        val maneuver: ManeuverCallout,
    ) : ManeuverBottomContent

    /** 下段なし。 */
    @Stable
    data object None : ManeuverBottomContent
}

private fun ManeuverBottomContent.bottomShape(): Shape {
    if (this is ManeuverBottomContent.Followup) {
        return RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp,
        )
    }

    return RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
}

private fun ManeuverBottomContent.isWideContent(): Boolean {
    return when (this) {
        is ManeuverBottomContent.GuideImage,
        ManeuverBottomContent.Panel,
        is ManeuverBottomContent.Lanes,
        -> true

        is ManeuverBottomContent.Followup,
        ManeuverBottomContent.None,
        -> false
    }
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
private fun MapNavigationManeuverGuideImage(
    guideImage: NavigationGuideImage,
    modifier: Modifier = Modifier,
) {
    Image(
        modifier = modifier.padding(horizontal = 24.dp),
        bitmap = guideImage.bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
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

/** マニューバレーンアイコンの標準サイズ。 */
private val ManeuverLaneIconSize = 36.dp

/** マニューバレーンアイコン同士の標準間隔。 */
private val ManeuverLaneIconSpacing = 12.dp

/** マニューバレーンの非推奨矢印に適用する透明度。 */
private const val ManeuverLaneInactiveAlpha: Float = 0.46f

/** 案内画像がバナー下段で占有できる最大高さ。 */
private val ManeuverGuideImageMaxHeight = 240.dp
