package me.matsumo.onenavi.core.navigation.newguidance.progress

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.navigation.newguidance.model.EntrancePanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.ExitPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.FacilityPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceManeuverInfo
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelFacility
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceTextPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.Lane
import me.matsumo.onenavi.core.navigation.newguidance.model.LaneGuidance
import me.matsumo.onenavi.core.navigation.newguidance.model.ManeuverPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.RecommendedLanesPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.TollPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceManeuver
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute
import me.matsumo.onenavi.core.navigation.newguidance.semantic.HighwayBoundary
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneMark
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepFacility
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepSignpost
import kotlin.math.roundToInt

/**
 * selector が選んだ semantic イベントを、案内中 UI が読む既存モデル
 * ([GuidanceManeuverInfo] / [GuidancePanelItem] / [LaneGuidance]) へ射影する adapter
 * (L2 progress 層)。
 *
 * 表示形・優先順位は L3 presentation 差し替えまでの暫定で、旧 tracker のパネル構築と
 * 同じ結果を再現する (= 挙動互換)。道路種別・通過予想時刻など位置依存の補助値は
 * [RouteProjectionContext] 経由で解決する。状態を持たない。
 */
internal class GuidanceProgressAdapter {

    /**
     * 1 tick 分の [GuidanceProgressProjection] を組み立てる。
     *
     * @param guidanceRoute 料金合計などルート全体の値を読む semantic ルート
     * @param selection 現在地より先のイベントカーソル
     * @param context 道路種別 / ETA を解決する geometry コンテキスト
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @param timestampMillis 位置 tick の時刻
     * @return 案内 UI が読む projection
     */
    fun adapt(
        guidanceRoute: GuidanceRoute,
        selection: GuidanceSelection,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): GuidanceProgressProjection {
        val nextManeuver = selection.nextPrimaryEvent?.let { event -> maneuverInfo(event = event, currentCumulativeMeters = currentCumulativeMeters) }
        val followupManeuver = selection.followupPrimaryEvent?.let { event -> maneuverInfo(event = event, currentCumulativeMeters = currentCumulativeMeters) }

        return GuidanceProgressProjection(
            nextManeuver = nextManeuver,
            followupManeuver = followupManeuver,
            lanes = activeLanes(selection = selection, context = context, currentCumulativeMeters = currentCumulativeMeters),
            panelItems = panelItems(
                guidanceRoute = guidanceRoute,
                selection = selection,
                context = context,
                currentCumulativeMeters = currentCumulativeMeters,
                timestampMillis = timestampMillis,
            ),
        )
    }

    // ---------------------------------------------------------------------
    // Maneuver banner
    // ---------------------------------------------------------------------

    /** 主案内イベントを TBT バナー用 [GuidanceManeuverInfo] に変換する。主案内が無ければ null。 */
    private fun maneuverInfo(
        event: GuidanceEvent,
        currentCumulativeMeters: Double,
    ): GuidanceManeuverInfo? {
        val primary = event.primary ?: return null
        return GuidanceManeuverInfo(
            type = primary.type,
            modifier = primary.modifier,
            location = event.anchor.location,
            distanceToManeuverMeters = distanceToMeters(event = event, currentCumulativeMeters = currentCumulativeMeters),
            intersectionName = primary.intersectionName,
            exitNumber = primary.exitNumber,
            guidancePointIndex = event.anchor.sourceGuidancePointIndex ?: NO_GUIDANCE_POINT_INDEX,
        )
    }

    // ---------------------------------------------------------------------
    // Lane guidance
    // ---------------------------------------------------------------------

    /**
     * レーンを持つ直近イベントが可視距離内に迫っていれば、そのレーンガイダンスを取り出す。
     *
     * レーンは主案内に紐付くとは限らない (料金所レーン等は primary == null) ため、主案内カーソル
     * ではなく selector が選んだ [GuidanceSelection.activeLaneEvent] を使う。レーン方向は主案内が
     * あればその modifier、無ければ直進を既定とする。
     *
     * @param selection 現在地より先のイベントカーソル
     * @param context レーン矢印の向きを解決する geometry コンテキスト
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return 表示対象のレーンガイダンス。対象が無い場合は空
     */
    private fun activeLanes(
        selection: GuidanceSelection,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
    ): ImmutableList<LaneGuidance> {
        val event = selection.activeLaneEvent ?: return persistentListOf()
        val lane = event.details.lane ?: return persistentListOf()
        val distanceMeters = event.anchor.geometryDistanceFromStartMeters - currentCumulativeMeters
        if (distanceMeters !in 0.0..LANE_GUIDANCE_VISIBILITY_METRES) return persistentListOf()

        val modifier = context.maneuverModifierAt(event.anchor.geometryDistanceFromStartMeters)
        val laneGuidance = toLaneGuidance(lane = lane, modifier = modifier) ?: return persistentListOf()
        return persistentListOf(laneGuidance)
    }

    /**
     * semantic レーンを UI レーンガイダンスへ変換する。marker layout で推奨車線が無い場合は null。
     *
     * @param lane semantic レーン情報
     * @param modifier レーンアイコンの方向
     * @return UI レーンガイダンス。表示対象が無い場合は null
     */
    private fun toLaneGuidance(
        lane: GuidanceLane,
        modifier: ManeuverModifier,
    ): LaneGuidance? {
        val layout = lane.layout as? LaneLayout.MarkerLayout ?: return null
        if (layout.lanes.isEmpty() || layout.lanes.none { mark -> mark.isRecommended }) return null

        val lanes = layout.lanes.map { mark -> toUiLane(mark = mark, modifier = modifier) }.toImmutableList()
        return LaneGuidance(lanes = lanes)
    }

    /** 1 車線分のマーカーを UI レーンへ変換する。 */
    private fun toUiLane(
        mark: LaneMark,
        modifier: ManeuverModifier,
    ): Lane = Lane(
        allowedDirections = persistentListOf(modifier),
        recommendedDirection = modifier.takeIf { mark.isRecommended },
        isActive = mark.isRecommended,
    )

    // ---------------------------------------------------------------------
    // Panel list
    // ---------------------------------------------------------------------

    /**
     * 現在地より先のイベントをパネル行へ変換し、距離の降順で返す。
     *
     * @param guidanceRoute 料金合計を読む semantic ルート
     * @param selection 現在地より先のイベントカーソル
     * @param context 道路種別 / ETA を解決する geometry コンテキスト
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @param timestampMillis 位置 tick の時刻
     * @return 現在地より先のパネル行 (距離の降順)
     */
    private fun panelItems(
        guidanceRoute: GuidanceRoute,
        selection: GuidanceSelection,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): ImmutableList<GuidancePanelItem> {
        val items = mutableListOf<GuidancePanelItem>()
        for (event in selection.eventsAfterCurrent) {
            val item = toPanelItem(
                event = event,
                guidanceRoute = guidanceRoute,
                context = context,
                currentCumulativeMeters = currentCumulativeMeters,
                timestampMillis = timestampMillis,
            ) ?: continue
            items += item
        }
        items.sortByDescending { item -> item.distanceFromStartMeters }
        return items.toImmutableList()
    }

    /**
     * 1 イベントをパネル行に変換する。到着の主案内とパネル化対象でないイベントは null。
     */
    private fun toPanelItem(
        event: GuidanceEvent,
        guidanceRoute: GuidanceRoute,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): GuidancePanelItem? {
        val primary = event.primary
        if (primary != null && primary.type == ManeuverType.ARRIVE) return null
        if (primary != null) {
            return maneuverPanelItem(
                event = event,
                primary = primary,
                context = context,
                currentCumulativeMeters = currentCumulativeMeters,
                timestampMillis = timestampMillis,
            )
        }
        val facility = event.details.facility ?: return null
        return facilityPanelItem(
            event = event,
            facility = facility,
            guidanceRoute = guidanceRoute,
            context = context,
            currentCumulativeMeters = currentCumulativeMeters,
            timestampMillis = timestampMillis,
        )
    }

    /** 主案内イベントを [ManeuverPanelItem] に変換する。 */
    private fun maneuverPanelItem(
        event: GuidanceEvent,
        primary: GuidanceManeuver,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): ManeuverPanelItem {
        val geometryMeters = event.anchor.geometryDistanceFromStartMeters
        val guidancePointIndex = event.anchor.sourceGuidancePointIndex ?: NO_GUIDANCE_POINT_INDEX
        return ManeuverPanelItem(
            id = "maneuver-$guidancePointIndex",
            location = event.anchor.location,
            distanceFromStartMeters = geometryMeters,
            distanceToItemMeters = distanceToMeters(event = event, currentCumulativeMeters = currentCumulativeMeters),
            etaEpochMillis = etaEpochMillis(context = context, currentCumulativeMeters = currentCumulativeMeters, targetMeters = geometryMeters, timestampMillis = timestampMillis),
            type = primary.type,
            modifier = primary.modifier,
            intersectionName = primary.intersectionName,
            exitNumber = primary.exitNumber,
            roadClass = context.roadClassAt(geometryMeters),
            facility = event.details.facility?.kind?.toPanelFacility(),
            subtitle = maneuverSubtitle(event = event, modifier = primary.modifier),
        )
    }

    /** 通過施設イベントを [FacilityPanelItem] に変換する。 */
    private fun facilityPanelItem(
        event: GuidanceEvent,
        facility: StepFacility,
        guidanceRoute: GuidanceRoute,
        context: RouteProjectionContext,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): FacilityPanelItem {
        val geometryMeters = event.anchor.geometryDistanceFromStartMeters
        return FacilityPanelItem(
            id = event.id.value,
            location = event.anchor.location,
            distanceFromStartMeters = geometryMeters,
            distanceToItemMeters = distanceToMeters(event = event, currentCumulativeMeters = currentCumulativeMeters),
            etaEpochMillis = etaEpochMillis(context = context, currentCumulativeMeters = currentCumulativeMeters, targetMeters = geometryMeters, timestampMillis = timestampMillis),
            name = facility.name,
            kind = facility.kind.toPanelFacility(),
            roadClass = context.roadClassAt(geometryMeters),
            services = facility.services,
            subtitle = facilitySubtitle(event = event, facility = facility, guidanceRoute = guidanceRoute, context = context),
        )
    }

    // ---------------------------------------------------------------------
    // Panel subtitle resolution
    // ---------------------------------------------------------------------

    /**
     * 主案内パネル行の補助表示を返す。レーン > 境界 > 看板テキストの優先で 1 つだけ選ぶ。
     */
    private fun maneuverSubtitle(
        event: GuidanceEvent,
        modifier: ManeuverModifier,
    ): GuidancePanelSubtitle? {
        val laneSubtitle = laneSubtitleOrNull(lane = event.details.lane, modifier = modifier)
        if (laneSubtitle != null) return laneSubtitle
        val boundarySubtitle = boundarySubtitleOrNull(event.details.boundary)
        if (boundarySubtitle != null) return boundarySubtitle
        return signpostSubtitleOrNull(event.details.signpost)
    }

    /**
     * 施設パネル行の補助表示を返す。レーン優先で、無ければ施設種別ごとに料金 / 境界を選ぶ。
     */
    private fun facilitySubtitle(
        event: GuidanceEvent,
        facility: StepFacility,
        guidanceRoute: GuidanceRoute,
        context: RouteProjectionContext,
    ): GuidancePanelSubtitle? {
        val laneModifier = context.maneuverModifierAt(event.anchor.geometryDistanceFromStartMeters)
        val laneSubtitle = laneSubtitleOrNull(lane = event.details.lane, modifier = laneModifier)
        if (laneSubtitle != null) return laneSubtitle

        return when (facility.kind) {
            FacilityKind.TOLL_GATE -> guidanceRoute.tollTotalYen?.let { amountYen -> TollPanelSubtitle(amountYen = amountYen) }
            FacilityKind.IC,
            FacilityKind.JCT,
            -> boundarySubtitleOrNull(event.details.boundary)
            FacilityKind.SA,
            FacilityKind.PA,
            -> null
        }
    }

    /** レーンガイダンスを推奨レーン補助表示へ変換する。レーンが無ければ null。 */
    private fun laneSubtitleOrNull(
        lane: GuidanceLane?,
        modifier: ManeuverModifier,
    ): GuidancePanelSubtitle? {
        val laneGuidance = lane?.let { value -> toLaneGuidance(lane = value, modifier = modifier) } ?: return null
        return RecommendedLanesPanelSubtitle(lanes = laneGuidance.lanes)
    }

    /** 高速の入口 / 出口境界を補助表示へ変換する。境界が無ければ null。 */
    private fun boundarySubtitleOrNull(boundary: HighwayBoundary?): GuidancePanelSubtitle? = when (boundary) {
        HighwayBoundary.ENTRANCE -> EntrancePanelSubtitle
        HighwayBoundary.EXIT -> ExitPanelSubtitle
        null -> null
    }

    /** 方面看板を案内文補助表示へ変換する。看板が無ければ null。 */
    private fun signpostSubtitleOrNull(signpost: StepSignpost?): GuidancePanelSubtitle? =
        signpost?.let { value -> GuidanceTextPanelSubtitle(text = value.primary) }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /** semantic 施設種別を UI パネル施設種別へ変換する。 */
    private fun FacilityKind.toPanelFacility(): GuidancePanelFacility = when (this) {
        FacilityKind.IC -> GuidancePanelFacility.IC
        FacilityKind.JCT -> GuidancePanelFacility.JCT
        FacilityKind.SA -> GuidancePanelFacility.SA
        FacilityKind.PA -> GuidancePanelFacility.PA
        FacilityKind.TOLL_GATE -> GuidancePanelFacility.TOLL_GATE
    }

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
        /** メインのレーンガイダンスを表示し始める手前距離。 */
        private const val LANE_GUIDANCE_VISIBILITY_METRES: Double = 800.0

        /** 秒からミリ秒へ変換する係数。 */
        private const val MILLIS_PER_SECOND: Long = 1_000L

        /** 主案内に GP index が紐付かない場合の番兵値。 */
        private const val NO_GUIDANCE_POINT_INDEX: Int = -1
    }
}

/**
 * adapter が 1 tick で組み立てた、案内 UI 向け projection。
 *
 * @property nextManeuver 次の主案内。無ければ null。
 * @property followupManeuver 次の次の主案内。無ければ null。
 * @property lanes 表示対象のレーンガイダンス。
 * @property panelItems 現在地より先のパネル行 (距離の降順)。
 */
@Immutable
data class GuidanceProgressProjection(
    val nextManeuver: GuidanceManeuverInfo?,
    val followupManeuver: GuidanceManeuverInfo?,
    val lanes: ImmutableList<LaneGuidance>,
    val panelItems: ImmutableList<GuidancePanelItem>,
)
