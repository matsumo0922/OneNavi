package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート上の道路種別。ポリラインの色分け描画に使う。
 */
enum class RoadClass {
    /** 高速道路・有料道路。 */
    HIGHWAY,

    /** 一般道。 */
    ORDINARY,
}

/**
 * geometry 上の連続した区間と、その区間の道路種別の対応。
 * 区間の境界は経路サマリの距離情報から推定するため厳密ではない（近似）。
 *
 * @param startPointIndex 区間開始点の geometry 内 index（含む）
 * @param endPointIndex 区間終了点の geometry 内 index（含む）。隣接する次の区間の startPointIndex と一致するため、区間同士は連続して描画できる。
 * @param roadClass この区間の道路種別
 */
@Immutable
data class RoadClassSegment(
    val startPointIndex: Int,
    val endPointIndex: Int,
    val roadClass: RoadClass,
)
