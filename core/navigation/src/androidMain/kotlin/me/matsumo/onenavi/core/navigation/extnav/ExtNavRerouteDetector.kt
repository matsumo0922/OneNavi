package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import kotlin.math.roundToInt

/**
 * ルート逸脱が確定したときにリルート要求を出す判定器。
 *
 * 逸脱の検知・debounce は [ExtNavGuidanceTracker] が `OFF_ROUTE_CONFIRMED` として確定済みのため、
 * この判定器は独自の閾値や連続 tick カウントを持たない。確定状態を消費し、attach 直後の連発防止
 * (ウォームアップ)・残距離・生位置有無といった最小限のガードだけを足して
 * [ExtNavRerouteDecision] を返す薄い層に徹する。実際の再探索と状態遷移は
 * [me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager] が担う。
 */
internal class ExtNavRerouteDetector {

    private var attachedRoute: RouteDetail? = null
    private var firstSnapshotTimestampMillis: Long? = null

    /**
     * 案内対象ルートを紐付け、ウォームアップ時刻をリセットする。
     *
     * @param route 案内対象ルート
     */
    fun attach(route: RouteDetail) {
        attachedRoute = route
        firstSnapshotTimestampMillis = null
    }

    /** attach 済みルートとウォームアップ状態を破棄する。 */
    fun detach() {
        attachedRoute = null
        firstSnapshotTimestampMillis = null
    }

    /**
     * 1 件の進捗 snapshot からリルート要求の要否を判定する。
     *
     * @param snapshot tracker が発行した進捗 snapshot
     * @return リルート要求。発火条件を満たさない場合は [ExtNavRerouteDecision.None]
     */
    fun onSnapshot(snapshot: ExtNavProgressSnapshot): ExtNavRerouteDecision {
        val route = attachedRoute ?: return ExtNavRerouteDecision.None

        val timestampMillis = snapshot.locationTimestampMillis
        if (firstSnapshotTimestampMillis == null) {
            firstSnapshotTimestampMillis = timestampMillis
        }

        if (snapshot.routeMatchState != RouteMatchState.OFF_ROUTE_CONFIRMED) {
            return ExtNavRerouteDecision.None
        }
        if (isWithinWarmup(timestampMillis)) return ExtNavRerouteDecision.None
        if (snapshot.distanceRemainingMeters <= MIN_REMAINING_METRES) return ExtNavRerouteDecision.None

        val rawLocation = snapshot.rawLocation ?: return ExtNavRerouteDecision.None
        val origin = RoutePoint(
            latitude = rawLocation.latitude,
            longitude = rawLocation.longitude,
        )

        return ExtNavRerouteDecision.Request(
            origin = origin,
            destination = route.destination,
            remainingViaPoints = remainingViaPoints(
                route = route,
                currentCumulativeMeters = snapshot.currentCumulativeMeters,
            ),
            currentCumulativeMeters = snapshot.currentCumulativeMeters,
            originDirectionDegrees = compassDirectionDegrees(snapshot.headingDegrees),
            reason = ExtNavRerouteReason.OffRoute,
        )
    }

    /**
     * 進行方位をコンパス方位角の整数 (0..359) に丸める。
     *
     * 負値や 360 以上も 0..359 へ正規化する。方位が無ければ null。
     *
     * @param headingDegrees 解決済みの車両進行方位
     * @return 0..359 のコンパス方位角。求められなければ null
     */
    private fun compassDirectionDegrees(headingDegrees: Float?): Int? {
        val heading = headingDegrees?.takeIf { value -> value.isFinite() } ?: return null
        return ((heading.roundToInt() % FULL_CIRCLE_DEGREES) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
    }

    /**
     * attach (初回 snapshot) からウォームアップ時間内かを返す。
     *
     * attach 直後はまだ新ルートへ snap し切っておらず一時的に逸脱と判定されやすいため、
     * この間はリルートを発火させない。
     *
     * @param timestampMillis 今回 snapshot の時刻
     * @return ウォームアップ時間内なら true
     */
    private fun isWithinWarmup(timestampMillis: Long): Boolean {
        val firstTimestampMillis = firstSnapshotTimestampMillis ?: return true
        return timestampMillis - firstTimestampMillis < REROUTE_WARMUP_MILLIS
    }

    /**
     * まだ通過していない経由地だけを抽出する。
     *
     * 各経由地を最近接 geometry 点の累積距離に換算し、現在の累積距離より十分先にある点だけを残す。
     * geometry を持たないルートでは判定できないため全件そのまま返す。
     *
     * @param route 経由地と geometry を持つ案内ルート
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return 未通過の経由地
     */
    private fun remainingViaPoints(
        route: RouteDetail,
        currentCumulativeMeters: Double,
    ): ImmutableList<RoutePoint> {
        val viaPoints = route.intermediateWaypoints
        if (viaPoints.isEmpty()) return persistentListOf()

        val geometry = route.geometry
        if (geometry.isEmpty()) return viaPoints

        val cumulativeMetres = RouteGeometryMath.cumulativeMetres(geometry)
        return viaPoints
            .filter { viaPoint ->
                val nearestIndex = nearestGeometryIndex(geometry = geometry, point = viaPoint)
                cumulativeMetres[nearestIndex] > currentCumulativeMeters + REMAINING_VIA_MARGIN_METRES
            }
            .toImmutableList()
    }

    /**
     * 指定点に最も近い geometry 点の index を線形探索で求める。
     *
     * @param geometry route polyline
     * @param point 距離を測る対象点
     * @return 最近接 geometry 点の index
     */
    private fun nearestGeometryIndex(
        geometry: List<RoutePoint>,
        point: RoutePoint,
    ): Int {
        var bestIndex = 0
        var bestDistanceMetres = Double.MAX_VALUE
        for (index in geometry.indices) {
            val distanceMetres = RouteGeometryMath.haversineMetres(geometry[index], point)
            if (distanceMetres < bestDistanceMetres) {
                bestDistanceMetres = distanceMetres
                bestIndex = index
            }
        }
        return bestIndex
    }

    /** リルート判定の数値閾値定義。 */
    private companion object {

        /** attach 後にリルート発火を抑制するウォームアップ時間。 */
        private const val REROUTE_WARMUP_MILLIS: Long = 10_000L

        /** リルートを許可する最小残距離 (目的地直前では再探索しない)。 */
        private const val MIN_REMAINING_METRES: Double = 100.0

        /** 経由地を未通過とみなすために現在累積距離へ足す余裕距離。 */
        private const val REMAINING_VIA_MARGIN_METRES: Double = 30.0

        /** コンパス方位角を正規化するときの 1 周分の度数。 */
        private const val FULL_CIRCLE_DEGREES: Int = 360
    }
}
