package me.matsumo.onenavi.core.navigation.newguidance.progress

import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.navigation.extnav.ManeuverClassifier
import me.matsumo.onenavi.core.navigation.extnav.RouteGeometryMath

/**
 * 案内中に tick ごとの projection で再利用する route geometry コンテキスト。
 *
 * attach 時に 1 回だけ構築し、距離から道路種別・進行方向・通過予想時刻を解決するための
 * route と累積距離を保持する。semantic イベント自体は道路種別やレーン矢印の向きを持たない
 * ため、adapter がここを経由して位置依存の補助値 (道路種別 / 方向 / ETA) を求める。
 *
 * @property route roadClassSegments と所要時間を持つ中立 route
 * @property cumulativeMetres geometry index ごとの累積距離
 * @property totalGeometryMetres geometry 上の総距離
 */
internal class RouteProjectionContext(
    val route: RouteDetail,
    val cumulativeMetres: DoubleArray,
    val totalGeometryMetres: Double,
) {

    /**
     * 指定 geometry 距離付近の道路種別を返す。
     *
     * @param geometryMeters 判定対象の geometry 累積距離
     * @return 該当する道路種別。見つからない場合は一般道
     */
    fun roadClassAt(geometryMeters: Double): RoadClass {
        val segmentIndex = RouteGeometryMath.segmentIndexAt(cumulativeMetres, geometryMeters)
        val segment = route.roadClassSegments.firstOrNull { segment -> segmentIndex >= segment.startPointIndex && segmentIndex < segment.endPointIndex }
        return segment?.roadClass ?: RoadClass.ORDINARY
    }

    /**
     * 指定 geometry 距離付近の進行方向 modifier を、前後 segment の方位差から返す。
     *
     * レーン矢印の向きに使う。主案内の有無に依らず geometry から一意に決まるため、主案内が
     * 無いレーンイベント (料金所レーン等) でも正しい向きを得られる。
     *
     * @param geometryMeters 判定対象の geometry 累積距離
     * @return 進行方向 modifier
     */
    fun maneuverModifierAt(geometryMeters: Double): ManeuverModifier {
        val bearingDiffDegrees = RouteGeometryMath.bearingDiffAt(
            geometry = route.geometry,
            cumulativeMetres = cumulativeMetres,
            targetMetres = geometryMeters,
        )
        return ManeuverClassifier.maneuverModifier(bearingDiffDegrees)
    }
}
