package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 1 本の外部ルートを Routes API で再現した結果。
 *
 * spec/23 のアルゴリズムで refine された polyline を chunk のリストとして保持する。
 * Preview 時は [mergedPolyline] を自前 MapPolyline で描画、Guidance 時は active な
 * [RefinedChunk] の routeToken を Navigator.setDestinations に渡す。
 *
 * @param chunks chunk 列 (順序付き)。隣接 chunk の終点・始点は重複頂点になっている
 * @param origin ルートの出発点
 * @param destination ルートの目的地
 */
@Immutable
data class RefinedRoute(
    val chunks: ImmutableList<RefinedChunk>,
    val origin: RoutePoint,
    val destination: RoutePoint,
) {
    /**
     * 全 chunk の polyline を結合した全体形状。
     *
     * chunk 境界は隣接 chunk で重複した頂点になっているため、最後の chunk 以外は末尾を
     * 落として連結する。これにより mergedPolyline の頂点数 = sum(chunk.polyline.size) -
     * (chunks.size - 1) になる。
     */
    val mergedPolyline: ImmutableList<RoutePoint> by lazy(LazyThreadSafetyMode.NONE) {
        chunks
            .flatMapIndexed { index, chunk ->
                if (index == chunks.lastIndex) chunk.polyline else chunk.polyline.dropLast(1)
            }
            .toImmutableList()
    }

    val totalDistanceMeters: Int = chunks.sumOf { it.distanceMeters }

    val totalDurationSeconds: Long = chunks.sumOf { it.durationSeconds }
}
