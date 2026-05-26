package me.matsumo.onenavi.core.navigation.newguidance.presentation

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.navigation.newguidance.progress.GuidanceSelection
import me.matsumo.onenavi.core.navigation.newguidance.progress.RouteProjectionContext
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceManeuver
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute
import kotlin.math.roundToInt

/**
 * selector が選んだ semantic イベントを、案内中 UI 向けの [GuidancePresentation] へ射影する
 * projector (L3)。
 *
 * 同じイベント列を「コンパクトバナー」「フルリスト」「地図 CallOut 用の主案内」へ射影する。整形・
 * 優先順位 (案内ラベルの waterfall、バナー下段の lane/followup 排他、リスト detail の優先順位) は
 * すべてこの層に閉じ、L0/L1/L2 には持ち込まない。道路種別・通過予想時刻・レーン矢印の向きといった
 * 位置依存値は [RouteProjectionContext] 経由で解決する。状態を持たない。
 */
internal class GuidancePresentationProjector {

    private val laneFactory = LanePresentationFactory()
    private val detailPolicy = GuidanceListDetailPolicy()

    /**
     * 1 tick 分の [GuidancePresentation] を組み立てる。
     *
     * @param guidanceRoute 料金合計などルート全体の値を読む semantic ルート
     * @param selection 現在地より先のイベントカーソル
     * @param context 道路種別 / ETA / レーン矢印の向きを解決する geometry コンテキスト
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @param currentRoadClass バナー配色に使う現在走行中の道路種別
     * @param currentRoadName 案内ラベルの waterfall で使う走行中道路名。無ければ null
     * @param timestampMillis 位置 tick の時刻
     * @return 案内 UI が読む presentation 一式
     */
    fun project(
        guidanceRoute: GuidanceRoute,
        selection: GuidanceSelection,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        currentRoadClass: RoadClass,
        currentRoadName: String?,
        timestampMillis: Long,
    ): GuidancePresentation {
        val nextManeuver = calloutOrNull(event = selection.nextPrimaryEvent, currentCumulativeMeters = currentCumulativeMeters)
        val followupManeuver = calloutOrNull(event = selection.followupPrimaryEvent, currentCumulativeMeters = currentCumulativeMeters)
        val listItems = listItems(
            guidanceRoute = guidanceRoute,
            selection = selection,
            context = context,
            currentCumulativeMeters = currentCumulativeMeters,
            timestampMillis = timestampMillis,
        )
        val banner = banner(
            primary = nextManeuver,
            followupManeuver = followupManeuver,
            selection = selection,
            context = context,
            currentCumulativeMeters = currentCumulativeMeters,
            currentRoadClass = currentRoadClass,
            currentRoadName = currentRoadName,
            hasMoreEvents = listItems.isNotEmpty(),
        )

        return GuidancePresentation(
            nextManeuver = nextManeuver,
            followupManeuver = followupManeuver,
            banner = banner,
            listItems = listItems,
        )
    }

    // ---------------------------------------------------------------------
    // Maneuver callout
    // ---------------------------------------------------------------------

    /** 主案内イベントを [ManeuverCallout] へ変換する。主案内が無ければ null。 */
    private fun calloutOrNull(
        event: GuidanceEvent?,
        currentCumulativeMeters: Double,
    ): ManeuverCallout? {
        val targetEvent = event ?: return null
        val primary = targetEvent.primary ?: return null
        return ManeuverCallout(
            type = primary.type,
            modifier = primary.modifier,
            location = targetEvent.anchor.location,
            distanceToManeuverMeters = distanceToMeters(event = targetEvent, currentCumulativeMeters = currentCumulativeMeters),
            intersectionName = primary.intersectionName,
            exitNumber = primary.exitNumber,
            guidancePointIndex = targetEvent.anchor.sourceGuidancePointIndex ?: ManeuverCallout.NO_GUIDANCE_POINT_INDEX,
        )
    }

    // ---------------------------------------------------------------------
    // Compact banner
    // ---------------------------------------------------------------------

    /** コンパクトバナーを組み立てる。主案内が無ければ null。 */
    private fun banner(
        primary: ManeuverCallout?,
        followupManeuver: ManeuverCallout?,
        selection: GuidanceSelection,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        currentRoadClass: RoadClass,
        currentRoadName: String?,
        hasMoreEvents: Boolean,
    ): ManeuverBanner? {
        val primaryCallout = primary ?: return null
        val secondaryLabel = secondaryLabel(event = selection.nextPrimaryEvent, currentRoadName = currentRoadName)
        val support = bannerSupport(
            followupManeuver = followupManeuver,
            activeLaneEvent = selection.activeLaneEvent,
            context = context,
            currentCumulativeMeters = currentCumulativeMeters,
        )

        return ManeuverBanner(
            primary = primaryCallout,
            secondaryLabel = secondaryLabel,
            roadClass = currentRoadClass,
            support = support,
            hasMoreEvents = hasMoreEvents,
        )
    }

    /**
     * 上段の案内ラベルを「交差点 > 方面看板 > 道路名 > 出口」の waterfall で選ぶ。
     *
     * @param event 次の主案内イベント。無ければ null
     * @param currentRoadName 走行中道路名。無ければ null
     * @return 案内ラベル。出せるものが無ければ null
     */
    private fun secondaryLabel(
        event: GuidanceEvent?,
        currentRoadName: String?,
    ): String? {
        val primary = event?.primary
        val intersectionName = primary?.intersectionName?.takeIf { name -> name.isNotBlank() }
        if (intersectionName != null) return intersectionName

        val signpost = event?.details?.signpost?.primary?.takeIf { text -> text.isNotBlank() }
        if (signpost != null) return signpost

        val roadName = currentRoadName?.takeIf { name -> name.isNotBlank() }
        if (roadName != null) return roadName

        return primary?.exitNumber?.takeIf { number -> number.isNotBlank() }
    }

    /**
     * バナー下段の補助を選ぶ。レーンを優先し、無ければフォローアップ案内。どちらも無ければ null。
     *
     * @param followupManeuver フォローアップの主案内。無ければ null
     * @param activeLaneEvent 現在地より先で最初にレーンを持つイベント。無ければ null
     * @param context レーン矢印の向きを解決する geometry コンテキスト
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return 下段の補助。出せるものが無ければ null
     */
    private fun bannerSupport(
        followupManeuver: ManeuverCallout?,
        activeLaneEvent: GuidanceEvent?,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
    ): BannerSupport? {
        val laneSupport = bannerLaneSupport(
            activeLaneEvent = activeLaneEvent,
            context = context,
            currentCumulativeMeters = currentCumulativeMeters,
        )
        if (laneSupport != null) return laneSupport

        val followup = followupManeuver ?: return null
        return BannerSupport.Followup(maneuver = followup)
    }

    /**
     * レーンを持つ直近イベントが可視距離内に迫っていれば、バナー下段のレーン補助を作る。
     *
     * レーンは主案内に紐付くとは限らない (料金所レーン等は主案内 null) ため、selector が主案内
     * カーソルと独立に選んだ [GuidanceSelection.activeLaneEvent] を使う。
     *
     * @param activeLaneEvent 現在地より先で最初にレーンを持つイベント。無ければ null
     * @param context レーン矢印の向きを解決する geometry コンテキスト
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return レーン補助。表示対象が無い場合は null
     */
    private fun bannerLaneSupport(
        activeLaneEvent: GuidanceEvent?,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
    ): BannerSupport? {
        val event = activeLaneEvent ?: return null
        val lane = event.details.lane ?: return null
        val geometryMeters = event.anchor.geometryDistanceFromStartMeters
        val distanceMeters = geometryMeters - currentCumulativeMeters
        if (distanceMeters !in 0.0..LANE_GUIDANCE_VISIBILITY_METRES) return null

        val recommendedDirection = context.maneuverModifierAt(geometryMeters)
        val lanePresentation = laneFactory.create(lane = lane, recommendedDirection = recommendedDirection) ?: return null
        return BannerSupport.Lanes(lane = lanePresentation)
    }

    // ---------------------------------------------------------------------
    // Full list
    // ---------------------------------------------------------------------

    /**
     * 現在地より先のイベントをフルリスト行へ変換し、距離の降順で返す。
     *
     * 到着の主案内とリスト化対象でないイベントは除外する。
     *
     * @param guidanceRoute 料金合計を読む semantic ルート
     * @param selection 現在地より先のイベントカーソル
     * @param context 道路種別 / ETA を解決する geometry コンテキスト
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @param timestampMillis 位置 tick の時刻
     * @return フルリスト行 (距離の降順)
     */
    private fun listItems(
        guidanceRoute: GuidanceRoute,
        selection: GuidanceSelection,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): ImmutableList<GuidanceListItem> {
        val orderedEvents = selection.eventsAfterCurrent.sortedByDescending { event -> event.anchor.geometryDistanceFromStartMeters }
        val items = mutableListOf<GuidanceListItem>()
        for (event in orderedEvents) {
            val item = listItemOrNull(
                event = event,
                guidanceRoute = guidanceRoute,
                context = context,
                currentCumulativeMeters = currentCumulativeMeters,
                timestampMillis = timestampMillis,
            ) ?: continue
            items += item
        }
        return items.toImmutableList()
    }

    /**
     * 1 イベントをフルリスト行に変換する。到着の主案内とリスト化対象でないイベントは null。
     */
    private fun listItemOrNull(
        event: GuidanceEvent,
        guidanceRoute: GuidanceRoute,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): GuidanceListItem? {
        val primary = event.primary
        if (primary != null && primary.type == ManeuverType.ARRIVE) return null
        if (primary != null) {
            return maneuverListItem(
                event = event,
                primary = primary,
                guidanceRoute = guidanceRoute,
                context = context,
                currentCumulativeMeters = currentCumulativeMeters,
                timestampMillis = timestampMillis,
            )
        }
        if (event.details.facility == null) return null
        return facilityListItem(
            event = event,
            guidanceRoute = guidanceRoute,
            context = context,
            currentCumulativeMeters = currentCumulativeMeters,
            timestampMillis = timestampMillis,
        )
    }

    /** 主案内イベントをフルリスト行へ変換する。 */
    private fun maneuverListItem(
        event: GuidanceEvent,
        primary: GuidanceManeuver,
        guidanceRoute: GuidanceRoute,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): GuidanceListItem {
        val geometryMeters = event.anchor.geometryDistanceFromStartMeters
        val guidancePointIndex = event.anchor.sourceGuidancePointIndex ?: ManeuverCallout.NO_GUIDANCE_POINT_INDEX
        return GuidanceListItem(
            id = "maneuver-$guidancePointIndex",
            icon = GuidanceListIcon.Maneuver(type = primary.type, modifier = primary.modifier),
            title = maneuverTitle(primary),
            detail = detail(event = event, guidanceRoute = guidanceRoute, context = context),
            distanceMeters = distanceToMeters(event = event, currentCumulativeMeters = currentCumulativeMeters),
            etaEpochMillis = etaEpochMillis(context = context, currentCumulativeMeters = currentCumulativeMeters, targetMeters = geometryMeters, timestampMillis = timestampMillis),
            roadClass = context.roadClassAt(geometryMeters),
        )
    }

    /** 通過施設イベントをフルリスト行へ変換する。 */
    private fun facilityListItem(
        event: GuidanceEvent,
        guidanceRoute: GuidanceRoute,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): GuidanceListItem {
        val geometryMeters = event.anchor.geometryDistanceFromStartMeters
        val facility = event.details.facility
        return GuidanceListItem(
            id = event.id.value,
            icon = GuidanceListIcon.FacilityBadge(kind = requireNotNull(facility).kind),
            title = facility.name.takeIf { name -> name.isNotBlank() },
            detail = detail(event = event, guidanceRoute = guidanceRoute, context = context),
            distanceMeters = distanceToMeters(event = event, currentCumulativeMeters = currentCumulativeMeters),
            etaEpochMillis = etaEpochMillis(context = context, currentCumulativeMeters = currentCumulativeMeters, targetMeters = geometryMeters, timestampMillis = timestampMillis),
            roadClass = context.roadClassAt(geometryMeters),
        )
    }

    /** 主案内行のタイトルを「交差点名 > 出口番号」で選ぶ。無ければ null。 */
    private fun maneuverTitle(primary: GuidanceManeuver): String? {
        val intersectionName = primary.intersectionName?.takeIf { name -> name.isNotBlank() }
        if (intersectionName != null) return intersectionName
        return primary.exitNumber?.takeIf { number -> number.isNotBlank() }
    }

    /** イベントの detail 枠を policy で 1 つ選ぶ。レーンは位置依存のためここで構築して渡す。 */
    private fun detail(
        event: GuidanceEvent,
        guidanceRoute: GuidanceRoute,
        context: RouteProjectionContext,
    ): GuidanceListDetail? {
        val lanePresentation = listLanePresentation(event = event, context = context)
        return detailPolicy.selectDetail(
            event = event,
            lanePresentation = lanePresentation,
            tollTotalYen = guidanceRoute.tollTotalYen,
        )
    }

    /**
     * フルリスト行のレーンを構築する。バナーと違い距離ゲートは掛けず、行が示す地点のレーンを出す。
     *
     * @param event 対象イベント
     * @param context レーン矢印の向きを解決する geometry コンテキスト
     * @return 表示用レーン。描画対象が無い場合は null
     */
    private fun listLanePresentation(
        event: GuidanceEvent,
        context: RouteProjectionContext,
    ): LanePresentation? {
        val lane = event.details.lane ?: return null
        val recommendedDirection = context.maneuverModifierAt(event.anchor.geometryDistanceFromStartMeters)
        return laneFactory.create(lane = lane, recommendedDirection = recommendedDirection)
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /** 現在地からイベントまでの残距離 (m) を返す。負値は 0 に丸める。 */
    private fun distanceToMeters(
        event: GuidanceEvent,
        currentCumulativeMeters: Double,
    ): Int = (event.anchor.geometryDistanceFromStartMeters - currentCumulativeMeters).coerceAtLeast(0.0).roundToInt()

    /**
     * 指定地点の推定通過時刻を route 全体の平均所要時間から求める。算出できない場合は null。
     */
    private fun etaEpochMillis(
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        targetMeters: Double,
        timestampMillis: Long,
    ): Long? {
        if (context.route.durationSeconds <= 0.0 || context.totalGeometryMetres <= 0.0) return null

        val distanceToTarget = (targetMeters - currentCumulativeMeters).coerceAtLeast(0.0)
        val secondsToTarget = context.route.durationSeconds * (distanceToTarget / context.totalGeometryMetres)
        return timestampMillis + secondsToTarget.roundToInt().toLong() * MILLIS_PER_SECOND
    }

    private companion object {
        /** バナー下段でレーンを表示し始める手前距離。 */
        private const val LANE_GUIDANCE_VISIBILITY_METRES: Double = 800.0

        /** 秒からミリ秒へ変換する係数。 */
        private const val MILLIS_PER_SECOND: Long = 1_000L
    }
}
