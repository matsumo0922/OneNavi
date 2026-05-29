package me.matsumo.onenavi.core.navigation.extnav

/**
 * 外部 API の source 距離基準を route geometry 距離基準へ変換する attach 時の成果物。
 *
 * UI 向けの semantic 射影 ([GuidanceRouteMapper]) と音声プラン (`VoiceAnnouncementPlanBuilder`)
 * が同一の変換を共有し、表示地点と発話地点がずれないようにするために用意する。生成は
 * [RouteDistanceContextFactory] が一手に担い、同じ payload / route から作れば常に同じ結果に
 * なる (決定的) ことを前提とする。
 *
 * @property distanceMapper source→geometry の区分線形変換器
 * @property totalGeometryMetres geometry 上の総距離。変換結果の上限クランプに使う
 */
internal class ExtNavRouteDistanceContext(
    val distanceMapper: RouteDistanceMapper,
    val totalGeometryMetres: Double,
) {

    /**
     * source 距離を geometry 距離へ変換し、`[0, totalGeometryMetres]` にクランプして返す。
     *
     * @param sourceMetres 外部データ上の始点からの距離 (m)
     * @return geometry 上の始点からの距離 (m)
     */
    fun geometryMetresFor(sourceMetres: Double): Double =
        distanceMapper.mapSourceToGeometry(sourceMetres).coerceIn(0.0, totalGeometryMetres)
}
