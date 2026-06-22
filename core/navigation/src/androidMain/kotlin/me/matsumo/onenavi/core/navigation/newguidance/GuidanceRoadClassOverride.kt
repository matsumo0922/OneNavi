package me.matsumo.onenavi.core.navigation.newguidance

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.extnav.RouteGeometryMath

/**
 * 座標道路種別 API で得た現在地周辺の道路種別補正。
 *
 * @param roadClass 補正後の道路種別
 * @param coordinate 判定に使った座標
 * @param updatedAtMillis 補正を取得した wall-clock 時刻
 */
@Immutable
internal data class RoadClassOverride(
    val roadClass: RoadClass,
    val coordinate: RoutePoint,
    val updatedAtMillis: Long,
)

/**
 * tracker snapshot へ現在地道路種別の補正を適用する helper。
 */
internal object GuidanceRoadClassOverride {

    /** 現在地道路種別補正を保持する最大時間。 */
    private const val OVERRIDE_MAX_AGE_MILLIS = 15_000L

    /** 補正座標と snapshot 現在地を同じ道路周辺とみなす最大距離。 */
    private const val OVERRIDE_MAX_DISTANCE_METRES = 120.0

    /**
     * [override] が新鮮かつ snapshot の現在地に近い場合だけ道路種別を差し替える。
     */
    fun apply(
        snapshot: ExtNavProgressSnapshot,
        override: RoadClassOverride?,
        nowMillis: Long,
    ): ExtNavProgressSnapshot {
        if (override == null) return snapshot
        if (!override.isFresh(nowMillis)) return snapshot

        val referencePoint = snapshot.rawLocation?.let { location ->
            RoutePoint(
                latitude = location.latitude,
                longitude = location.longitude,
            )
        } ?: snapshot.progress.snappedLocation
        val distanceMeters = RouteGeometryMath.haversineMetres(referencePoint, override.coordinate)
        if (distanceMeters > OVERRIDE_MAX_DISTANCE_METRES) return snapshot

        val progress = snapshot.progress.copy(
            currentRoadClass = override.roadClass,
        )
        val presentation = snapshot.presentation.copy(
            banner = snapshot.presentation.banner?.copy(
                roadClass = override.roadClass,
            ),
        )
        return snapshot.copy(
            progress = progress,
            presentation = presentation,
        )
    }

    private fun RoadClassOverride.isFresh(nowMillis: Long): Boolean =
        nowMillis - updatedAtMillis <= OVERRIDE_MAX_AGE_MILLIS
}
