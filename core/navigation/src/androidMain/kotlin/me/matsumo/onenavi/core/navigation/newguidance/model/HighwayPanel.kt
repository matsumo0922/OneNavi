package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * 高速道路施設のパネル表示情報。
 *
 * @param kind 施設種別
 * @param name 施設名
 * @param distanceMeters 現在位置から施設までの距離
 * @param services SA / PA のサービス情報
 */
@Immutable
data class HighwayPanel(
    val kind: HighwayFacility,
    val name: String,
    val distanceMeters: Int,
    val services: ImmutableList<String>,
)

/**
 * 高速道路施設種別。
 */
enum class HighwayFacility {
    IC,
    JCT,
    SA,
    PA,
}
