package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListDetail
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListIcon
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListItem
import me.matsumo.onenavi.core.navigation.newguidance.presentation.LanePresentation
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.HighwayBoundary
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_destination
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_direction_sign
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_entrance
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_exit
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_ic
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_jct
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_pa
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_sa
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_toll_gate_badge
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_guidance_point
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_waypoint
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import me.matsumo.onenavi.core.ui.theme.RouteColors
import me.matsumo.onenavi.feature.map.state.MapGeodesy
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun MapNavigationManeuverPanelSection(
    route: RouteDetail,
    listItems: ImmutableList<GuidanceListItem>,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    currentCumulativeMeters: Double,
    modifier: Modifier = Modifier,
) {
    val panelItems = remember(route, listItems, currentCumulativeMeters, timestampMillis) {
        buildNavigationPanelItems(
            route = route,
            listItems = listItems,
            currentCumulativeMeters = currentCumulativeMeters,
            timestampMillis = timestampMillis,
        )
    }
    val primaryGuidanceItemId = listItems.lastOrNull()?.id
    val bottomIndex = panelItems.size
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = bottomIndex)

    MapNavigationGuidancePanelAutoScroll(
        listState = listState,
        bottomIndex = bottomIndex,
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
    ) {
        itemsIndexed(
            items = panelItems,
            key = { _, item -> item.id },
        ) { _, item ->
            when (item) {
                is NavigationPanelGuidanceItem -> MapNavigationGuidancePanelRow(
                    modifier = Modifier.fillMaxWidth(),
                    item = item.guidanceItem,
                    timelineCongestionBands = item.timelineCongestionBands,
                    meterLabel = meterLabel,
                    kilometerLabel = kilometerLabel,
                    dayLabel = dayLabel,
                    hourLabel = hourLabel,
                    minuteLabel = minuteLabel,
                    timestampMillis = timestampMillis,
                    isPrimary = item.guidanceItem.id == primaryGuidanceItemId,
                )

                is NavigationPanelStopItem -> MapNavigationStopPanelRow(
                    modifier = Modifier.fillMaxWidth(),
                    item = item,
                    timelineCongestionBands = item.timelineCongestionBands,
                    meterLabel = meterLabel,
                    kilometerLabel = kilometerLabel,
                    dayLabel = dayLabel,
                    hourLabel = hourLabel,
                    minuteLabel = minuteLabel,
                    timestampMillis = timestampMillis,
                )
            }
        }
    }
}

/**
 * 案内パネルの自動スクロール制御。
 *
 * - 初期表示、もしくは案内地点を通過して件数が変化したら即座に最下部（自車位置）へ寄せる
 * - ユーザーが手動でスクロールした場合は、操作が止まって一定時間が経過したら最下部へ戻す
 */
@Composable
private fun MapNavigationGuidancePanelAutoScroll(
    listState: LazyListState,
    bottomIndex: Int,
) {
    LaunchedEffect(bottomIndex) {
        listState.scrollToItem(bottomIndex)
    }

    LaunchedEffect(listState, bottomIndex) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            listState.scheduleReturnOnDragFinished(
                interaction = interaction,
                bottomIndex = bottomIndex,
            )
        }
    }
}

/**
 * ユーザーのドラッグが終了したら、一定時間後に最下部（自車位置）へアニメーションで戻す。
 * プログラムによるスクロールは [interactionSource] に流れないため、戻し中に自己キャンセルされない。
 * ドラッグ再開時は [collectLatest] が本処理を取り消すため、待機タイマーがリセットされる。
 */
private suspend fun LazyListState.scheduleReturnOnDragFinished(
    interaction: Interaction,
    bottomIndex: Int,
) {
    val isDragFinished = interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel
    if (!isDragFinished) {
        return
    }

    delay(AutoScrollBackDelay)
    if (!isBottomReached(bottomIndex)) {
        animateScrollToItem(bottomIndex)
    }
}

/** フッター（自車位置）が表示範囲内にあれば true。 */
private fun LazyListState.isBottomReached(bottomIndex: Int): Boolean {
    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisibleIndex >= bottomIndex
}

/**
 * 案内パネルに表示する行の共通モデル。
 */
@Immutable
private sealed interface NavigationPanelItem {
    val id: String
    val distanceMeters: Int
    val roadClass: RoadClass
    val targetCumulativeMeters: Double
    val timelineCongestionBands: ImmutableList<TimelineCongestionBand>
}

/**
 * 通常の案内地点行。
 *
 * @property guidanceItem 表示元の案内地点。
 */
@Immutable
private data class NavigationPanelGuidanceItem(
    val guidanceItem: GuidanceListItem,
    override val targetCumulativeMeters: Double,
    override val timelineCongestionBands: ImmutableList<TimelineCongestionBand> = persistentListOf(),
) : NavigationPanelItem {
    override val id: String = guidanceItem.id
    override val distanceMeters: Int = guidanceItem.distanceMeters
    override val roadClass: RoadClass = guidanceItem.roadClass
}

/**
 * 経由地または目的地の行。
 *
 * @property id LazyColumn 用の安定 ID。
 * @property title 地点名。空の場合は UI で既定文言を表示する。
 * @property waypointNumber 経由地番号。目的地では null。
 * @property isDestination 目的地行なら true。
 * @property distanceMeters 現在位置から地点までの残距離。
 * @property etaEpochMillis 推定到着時刻。算出できなければ null。
 * @property roadClass 地点付近の道路種別。
 */
@Immutable
private data class NavigationPanelStopItem(
    override val id: String,
    val title: String,
    val waypointNumber: Int?,
    val isDestination: Boolean,
    override val distanceMeters: Int,
    val etaEpochMillis: Long?,
    override val roadClass: RoadClass,
    override val targetCumulativeMeters: Double,
    override val timelineCongestionBands: ImmutableList<TimelineCongestionBand> = persistentListOf(),
) : NavigationPanelItem

/**
 * ルート上の停車地点候補。
 *
 * @property id LazyColumn 用の安定 ID。
 * @property title 地点名。空の場合は UI で既定文言を表示する。
 * @property waypointNumber 経由地番号。目的地では null。
 * @property isDestination 目的地候補なら true。
 * @property point 地点座標。
 */
@Immutable
private data class NavigationPanelStopCandidate(
    val id: String,
    val title: String,
    val waypointNumber: Int?,
    val isDestination: Boolean,
    val point: RoutePoint,
)

private fun buildNavigationPanelItems(
    route: RouteDetail,
    listItems: ImmutableList<GuidanceListItem>,
    currentCumulativeMeters: Double,
    timestampMillis: Long,
): List<NavigationPanelItem> {
    val panelItems = mutableListOf<NavigationPanelItem>()
    for (listItem in listItems) {
        panelItems += NavigationPanelGuidanceItem(
            guidanceItem = listItem,
            targetCumulativeMeters = currentCumulativeMeters + listItem.distanceMeters.toDouble(),
        )
    }
    panelItems += buildNavigationStopPanelItems(
        route = route,
        currentCumulativeMeters = currentCumulativeMeters,
        timestampMillis = timestampMillis,
    )
    return panelItems
        .sortedByDescending { item -> item.distanceMeters }
        .withTimelineCongestionBands(
            congestionSegments = route.congestionSegments,
            currentCumulativeMeters = currentCumulativeMeters,
        )
}

private fun List<NavigationPanelItem>.withTimelineCongestionBands(
    congestionSegments: ImmutableList<CongestionSegment>,
    currentCumulativeMeters: Double,
): List<NavigationPanelItem> {
    if (isEmpty() || congestionSegments.isEmpty()) return this

    return mapIndexed { itemIndex, item ->
        val nextTargetMeters = getOrNull(itemIndex + 1)?.targetCumulativeMeters ?: currentCumulativeMeters
        val congestionBands = buildTimelineCongestionBands(
            congestionSegments = congestionSegments,
            rowStartMeters = item.targetCumulativeMeters,
            rowEndMeters = nextTargetMeters,
        )

        item.withTimelineCongestionBands(congestionBands)
    }
}

private fun NavigationPanelItem.withTimelineCongestionBands(
    congestionBands: ImmutableList<TimelineCongestionBand>,
): NavigationPanelItem {
    return when (this) {
        is NavigationPanelGuidanceItem -> copy(timelineCongestionBands = congestionBands)
        is NavigationPanelStopItem -> copy(timelineCongestionBands = congestionBands)
    }
}

private fun buildNavigationStopPanelItems(
    route: RouteDetail,
    currentCumulativeMeters: Double,
    timestampMillis: Long,
): List<NavigationPanelStopItem> {
    if (route.geometry.isEmpty()) return emptyList()

    val cumulativeMeters = cumulativeMeters(route.geometry)
    val totalGeometryMeters = cumulativeMeters.last()
    val stopItems = mutableListOf<NavigationPanelStopItem>()

    for (stopCandidate in route.navigationStopCandidates()) {
        val stopItem = stopCandidate.toNavigationPanelStopItem(
            route = route,
            cumulativeMeters = cumulativeMeters,
            totalGeometryMeters = totalGeometryMeters,
            currentCumulativeMeters = currentCumulativeMeters,
            timestampMillis = timestampMillis,
        )
        if (stopItem != null) {
            stopItems += stopItem
        }
    }

    return stopItems
}

private fun RouteDetail.navigationStopCandidates(): List<NavigationPanelStopCandidate> {
    val stopCandidates = mutableListOf<NavigationPanelStopCandidate>()

    for (waypointIndex in intermediateWaypoints.indices) {
        val waypointNumber = waypointIndex + 1
        val point = intermediateWaypoints[waypointIndex]
        val displayWaypoint = routeWaypoints.displayPlaceForPoint(
            point = point,
            fallbackIndex = waypointNumber,
        )
        stopCandidates += NavigationPanelStopCandidate(
            id = "waypoint:$waypointNumber:${point.latitude}:${point.longitude}",
            title = displayWaypoint.name,
            waypointNumber = waypointNumber,
            isDestination = false,
            point = point,
        )
    }

    val destinationWaypoint = routeWaypoints.displayPlaceForPoint(
        point = destination,
        fallbackIndex = routeWaypoints.lastIndex,
    )
    stopCandidates += NavigationPanelStopCandidate(
        id = "destination:${destination.latitude}:${destination.longitude}",
        title = destinationWaypoint.name,
        waypointNumber = null,
        isDestination = true,
        point = destination,
    )

    return stopCandidates
}

private fun NavigationPanelStopCandidate.toNavigationPanelStopItem(
    route: RouteDetail,
    cumulativeMeters: DoubleArray,
    totalGeometryMeters: Double,
    currentCumulativeMeters: Double,
    timestampMillis: Long,
): NavigationPanelStopItem? {
    val targetIndex = nearestGeometryIndex(
        geometry = route.geometry,
        point = point,
    )
    val targetMeters = cumulativeMeters[targetIndex]
    val shouldHidePassedWaypoint = !isDestination && targetMeters <= currentCumulativeMeters + PASSED_STOP_HIDE_MARGIN_METERS
    if (shouldHidePassedWaypoint) return null

    val distanceMeters = (targetMeters - currentCumulativeMeters).coerceAtLeast(0.0).roundToInt()
    return NavigationPanelStopItem(
        id = id,
        title = title,
        waypointNumber = waypointNumber,
        isDestination = isDestination,
        distanceMeters = distanceMeters,
        etaEpochMillis = stopEtaEpochMillis(
            route = route,
            totalGeometryMeters = totalGeometryMeters,
            currentCumulativeMeters = currentCumulativeMeters,
            targetMeters = targetMeters,
            timestampMillis = timestampMillis,
        ),
        roadClass = route.roadClassAt(pointIndex = targetIndex),
        targetCumulativeMeters = targetMeters,
    )
}

private fun List<RouteWaypoint>.displayPlaceForPoint(
    point: RoutePoint,
    fallbackIndex: Int,
): RouteWaypoint.Place {
    val fallbackWaypoint = getOrNull(fallbackIndex) as? RouteWaypoint.Place
    val matchedWaypoint = fallbackWaypoint ?: findPlaceNear(point = point)
    return RouteWaypoint.Place(
        name = matchedWaypoint?.name.orEmpty(),
        latitude = point.latitude,
        longitude = point.longitude,
    )
}

private fun List<RouteWaypoint>.findPlaceNear(point: RoutePoint): RouteWaypoint.Place? {
    for (waypoint in this) {
        val place = waypoint as? RouteWaypoint.Place ?: continue
        val distanceMeters = MapGeodesy.haversineMeters(place.toRoutePoint(), point)
        if (distanceMeters <= WAYPOINT_NAME_MATCH_DISTANCE_METERS) return place
    }
    return null
}

private fun RouteWaypoint.toRoutePoint(): RoutePoint = RoutePoint(
    latitude = latitude,
    longitude = longitude,
)

private fun cumulativeMeters(geometry: List<RoutePoint>): DoubleArray {
    val cumulativeMeters = DoubleArray(geometry.size)
    for (pointIndex in 1 until geometry.size) {
        cumulativeMeters[pointIndex] = cumulativeMeters[pointIndex - 1] +
            MapGeodesy.haversineMeters(geometry[pointIndex - 1], geometry[pointIndex])
    }
    return cumulativeMeters
}

private fun nearestGeometryIndex(
    geometry: List<RoutePoint>,
    point: RoutePoint,
): Int {
    var nearestIndex = 0
    var nearestDistanceMeters = Double.POSITIVE_INFINITY

    for (pointIndex in geometry.indices) {
        val distanceMeters = MapGeodesy.haversineMeters(geometry[pointIndex], point)
        if (distanceMeters < nearestDistanceMeters) {
            nearestIndex = pointIndex
            nearestDistanceMeters = distanceMeters
        }
    }

    return nearestIndex
}

private fun stopEtaEpochMillis(
    route: RouteDetail,
    totalGeometryMeters: Double,
    currentCumulativeMeters: Double,
    targetMeters: Double,
    timestampMillis: Long,
): Long? {
    if (route.durationSeconds <= 0.0 || totalGeometryMeters <= 0.0) return null

    val distanceToTargetMeters = (targetMeters - currentCumulativeMeters).coerceAtLeast(0.0)
    val secondsToTarget = route.durationSeconds * (distanceToTargetMeters / totalGeometryMeters)
    return timestampMillis + secondsToTarget.roundToInt().toLong() * MILLIS_PER_SECOND.toLong()
}

private fun RouteDetail.roadClassAt(pointIndex: Int): RoadClass {
    for (segment in roadClassSegments) {
        val startPointIndex = minOf(segment.startPointIndex, segment.endPointIndex)
        val endPointIndex = maxOf(segment.startPointIndex, segment.endPointIndex)
        if (pointIndex in startPointIndex..endPointIndex) return segment.roadClass
    }
    return roadClassSegments.lastOrNull()?.roadClass ?: RoadClass.ORDINARY
}

@Composable
private fun MapNavigationGuidancePanelRow(
    item: GuidanceListItem,
    timelineCongestionBands: ImmutableList<TimelineCongestionBand>,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
) {
    val distanceText = remember(item.distanceMeters, meterLabel, kilometerLabel) {
        formatPanelDistance(
            meters = item.distanceMeters.toDouble(),
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }
    val etaText = remember(item.etaEpochMillis, timestampMillis, dayLabel, hourLabel, minuteLabel) {
        panelDurationText(
            etaEpochMillis = item.etaEpochMillis,
            timestampMillis = timestampMillis,
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
        )
    }
    val title = item.panelTitle()
    val detail = item.detail
    val panelColors = RouteColors.maneuver(item.roadClass)
    val timelineColor = guidancePanelTimelineColor(item.roadClass)
    val primaryBackgroundColor = if (isPrimary) {
        guidancePanelPrimaryBackgroundColor(item.roadClass)
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier.heightIn(min = GuidancePanelRowMinHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GuidancePanelRowMinHeight + 16.dp)
                .offset(y = 8.dp)
                .background(primaryBackgroundColor),
        )

        Column(
            modifier = Modifier
                .padding(
                    start = 20.dp,
                    end = 16.dp,
                )
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(
                            width = 4.dp,
                            color = timelineColor,
                            shape = CircleShape,
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                )

                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = panelColors.onPrimary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(GuidancePanelRowMinHeight)
                        .width(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MapNavigationTimelineTrack(
                        modifier = Modifier
                            .requiredHeight(GuidancePanelRowMinHeight + 6.dp)
                            .offset(y = 1.dp),
                        baseColor = timelineColor,
                        congestionBands = timelineCongestionBands,
                    )
                }

                MapNavigationGuidancePanelIcon(
                    modifier = Modifier.size(40.dp),
                    icon = item.icon,
                    tint = panelColors.onPrimary,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        color = panelColors.onPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (detail != null) {
                        MapNavigationGuidancePanelDetail(
                            detail = detail,
                            contentColor = panelColors.onPrimary,
                            secondaryContentColor = panelColors.onPrimary,
                        )
                    }
                }

                Column(
                    modifier = Modifier.widthIn(min = 64.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (etaText != null) {
                        Text(
                            text = etaText,
                            color = panelColors.onPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    Text(
                        text = distanceText,
                        color = panelColors.onPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNavigationStopPanelRow(
    item: NavigationPanelStopItem,
    timelineCongestionBands: ImmutableList<TimelineCongestionBand>,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    modifier: Modifier = Modifier,
) {
    val distanceText = remember(item.distanceMeters, meterLabel, kilometerLabel) {
        formatPanelDistance(
            meters = item.distanceMeters.toDouble(),
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }
    val etaText = remember(item.etaEpochMillis, timestampMillis, dayLabel, hourLabel, minuteLabel) {
        panelDurationText(
            etaEpochMillis = item.etaEpochMillis,
            timestampMillis = timestampMillis,
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
        )
    }
    val title = item.panelTitle()
    val panelColors = RouteColors.maneuver(item.roadClass)
    val timelineColor = guidancePanelTimelineColor(item.roadClass)

    Box(
        modifier = modifier.heightIn(min = GuidancePanelRowMinHeight),
    ) {
        Column(
            modifier = Modifier
                .padding(
                    start = 20.dp,
                    end = 16.dp,
                )
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(
                            width = 4.dp,
                            color = timelineColor,
                            shape = CircleShape,
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                )

                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = panelColors.onPrimary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(GuidancePanelRowMinHeight)
                        .width(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MapNavigationTimelineTrack(
                        modifier = Modifier
                            .requiredHeight(GuidancePanelRowMinHeight + 6.dp)
                            .offset(y = 1.dp),
                        baseColor = timelineColor,
                        congestionBands = timelineCongestionBands,
                    )
                }

                MapNavigationStopPanelIcon(
                    modifier = Modifier.size(40.dp),
                    item = item,
                    tint = panelColors.onPrimary,
                )

                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    color = panelColors.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Column(
                    modifier = Modifier.widthIn(min = 64.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (etaText != null) {
                        Text(
                            text = etaText,
                            color = panelColors.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    Text(
                        text = distanceText,
                        color = panelColors.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNavigationStopPanelIcon(
    item: NavigationPanelStopItem,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (item.isDestination) {
            ManeuverIcon(
                modifier = Modifier.size(34.dp),
                type = ManeuverType.ARRIVE,
                maneuverModifier = ManeuverModifier.STRAIGHT,
                contentDescription = null,
                tint = tint,
            )
        } else {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.waypointNumber?.toString().orEmpty(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelDetail(
    detail: GuidanceListDetail,
    contentColor: Color,
    secondaryContentColor: Color,
    modifier: Modifier = Modifier,
) {
    when (detail) {
        is GuidanceListDetail.Signpost -> Text(
            modifier = modifier,
            text = stringResource(Res.string.home_map_navigation_panel_direction_sign, detail.text),
            color = secondaryContentColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is GuidanceListDetail.Toll -> Text(
            modifier = modifier,
            text = formatYen(detail.amountYen),
            color = secondaryContentColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is GuidanceListDetail.Boundary -> Text(
            modifier = modifier,
            text = stringResource(detail.kind.boundaryLabel()),
            color = secondaryContentColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is GuidanceListDetail.Warning -> Text(
            modifier = modifier,
            text = detail.text,
            color = secondaryContentColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is GuidanceListDetail.Lanes -> MapNavigationGuidancePanelLaneDetail(
            modifier = modifier,
            lane = detail.lane,
            contentColor = contentColor,
            secondaryContentColor = secondaryContentColor,
        )
    }
}

@Composable
private fun MapNavigationGuidancePanelLaneDetail(
    lane: LanePresentation,
    contentColor: Color,
    secondaryContentColor: Color,
    modifier: Modifier = Modifier,
) {
    // テキスト由来のレーン (側 + 本数 / 警告文) は発話テキスト解析経路で対応する。現状は視覚レーンのみ描く。
    val visualLanes = lane as? LanePresentation.VisualLanes ?: return
    MapNavigationManeuverLaneIcons(
        modifier = modifier,
        lanes = visualLanes.lanes,
        iconSize = GuidancePanelSubtitleLaneIconSize,
        spacing = GuidancePanelSubtitleLaneSpacing,
        activeTint = contentColor,
        inactiveTint = secondaryContentColor,
    )
}

@Composable
private fun MapNavigationGuidancePanelIcon(
    icon: GuidanceListIcon,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            is GuidanceListIcon.Maneuver -> ManeuverIcon(
                modifier = Modifier.size(34.dp),
                type = icon.type,
                maneuverModifier = icon.modifier,
                contentDescription = null,
                tint = tint,
            )

            is GuidanceListIcon.FacilityBadge -> MapNavigationFacilityBadge(
                modifier = Modifier.size(width = 38.dp, height = 28.dp),
                facility = icon.kind,
                tint = tint,
            )
        }
    }
}

@Composable
private fun MapNavigationFacilityBadge(
    facility: FacilityKind,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = Color.Transparent,
        border = BorderStroke(2.dp, tint),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(facility.badgeLabel()),
                color = tint,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

private fun formatPanelDistance(
    meters: Double,
    meterLabel: String,
    kilometerLabel: String,
): String = formatDistance(
    meters = meters,
    meterLabel = meterLabel,
    kilometerLabel = kilometerLabel,
)

private fun panelDurationText(
    etaEpochMillis: Long?,
    timestampMillis: Long,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
): String? {
    val eta = etaEpochMillis ?: return null
    val remainingSeconds = (eta - timestampMillis)
        .coerceAtLeast(0L)
        .toDouble() / MILLIS_PER_SECOND
    return formatDuration(
        totalSeconds = remainingSeconds,
        dayLabel = dayLabel,
        hourLabel = hourLabel,
        minuteLabel = minuteLabel,
    )
}

@Composable
private fun GuidanceListItem.panelTitle(): String {
    val fallbackTitle = stringResource(Res.string.home_map_navigation_panel_guidance_point)
    return title?.takeIf { value -> value.isNotBlank() } ?: fallbackTitle
}

@Composable
private fun NavigationPanelStopItem.panelTitle(): String {
    val fallbackTitle = if (isDestination) {
        stringResource(Res.string.home_map_navigation_panel_destination)
    } else {
        stringResource(Res.string.home_map_navigation_panel_waypoint, waypointNumber ?: 0)
    }
    return title.takeIf { value -> value.isNotBlank() } ?: fallbackTitle
}

/** 境界種別ごとの表示文字列リソースを返す。 */
private fun HighwayBoundary.boundaryLabel() = when (this) {
    HighwayBoundary.ENTRANCE -> Res.string.home_map_navigation_panel_entrance
    HighwayBoundary.EXIT -> Res.string.home_map_navigation_panel_exit
}

/** 施設種別ごとのバッジ文字列リソースを返す。 */
private fun FacilityKind.badgeLabel() = when (this) {
    FacilityKind.IC -> Res.string.home_map_navigation_panel_facility_ic
    FacilityKind.JCT -> Res.string.home_map_navigation_panel_facility_jct
    FacilityKind.SA -> Res.string.home_map_navigation_panel_facility_sa
    FacilityKind.PA -> Res.string.home_map_navigation_panel_facility_pa
    FacilityKind.TOLL_GATE -> Res.string.home_map_navigation_panel_facility_toll_gate_badge
}

private fun guidancePanelTimelineColor(roadClass: RoadClass): Color =
    RouteColors.polyline(roadClass.reverse()).body

private fun guidancePanelPrimaryBackgroundColor(roadClass: RoadClass): Color =
    RouteColors.accent(roadClass).container.copy(alpha = GUIDANCE_PANEL_PRIMARY_BACKGROUND_ALPHA)

private val GuidancePanelRowMinHeight = 60.dp
private val GuidancePanelSubtitleLaneIconSize = 22.dp
private val GuidancePanelSubtitleLaneSpacing = 6.dp

/** 最新案内行の背景に使う道路種別色の透明度。 */
private const val GUIDANCE_PANEL_PRIMARY_BACKGROUND_ALPHA = 0.24f

/** 通過済み経由地としてパネルから隠すため現在位置に足す余裕距離。 */
private const val PASSED_STOP_HIDE_MARGIN_METERS: Double = 30.0

/** 経由地名を既存地点から引き継ぐため同一点とみなす距離。 */
private const val WAYPOINT_NAME_MATCH_DISTANCE_METERS: Double = 30.0

/** 手動スクロール後に自動で最下部へ戻すまでの待機時間。 */
private val AutoScrollBackDelay = 5.seconds

private const val MILLIS_PER_SECOND: Double = 1_000.0
