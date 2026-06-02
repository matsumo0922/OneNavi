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
    ;

    fun reverse(): RoadClass {
        return when (this) {
            HIGHWAY -> ORDINARY
            ORDINARY -> HIGHWAY
        }
    }
}

/**
 * geometry 上の連続した区間と、その区間の道路種別の対応。
 * 区間の境界は経路サマリの距離情報から推定するため厳密ではない（近似）。
 *
 * @param startPointIndex 区間開始点の geometry 内 index（含む）
 * @param endPointIndex 区間終了点の geometry 内 index（含む）。隣接する次の区間の startPointIndex と一致するため、区間同士は連続して描画できる。
 * @param roadClass この区間の道路種別
 * @param entryInterchangeName 区間開始位置に対応する IC / JCT 名。`HIGHWAY` 区間の入口 IC 等。境界が交差点と一致しない、または交差点名が空の場合は null。
 * @param exitInterchangeName 区間終了位置に対応する IC / JCT 名。`HIGHWAY` 区間の出口 IC 等。境界が交差点と一致しない、または交差点名が空の場合は null。
 */
@Immutable
data class RoadClassSegment(
    val startPointIndex: Int,
    val endPointIndex: Int,
    val roadClass: RoadClass,
    val entryInterchangeName: String? = null,
    val exitInterchangeName: String? = null,
)
