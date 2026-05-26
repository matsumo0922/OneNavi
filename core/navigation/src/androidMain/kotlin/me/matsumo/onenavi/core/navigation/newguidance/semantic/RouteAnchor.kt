package me.matsumo.onenavi.core.navigation.newguidance.semantic

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 案内イベントがルート上で占める位置 (距離座標系を明示的に分離して保持する)。
 *
 * 外部データ上の距離 ([sourceDistanceFromStartMeters]) と route geometry 上の距離
 * ([geometryDistanceFromStartMeters]) は一致しないため、1 フィールドに潰さない。
 * UI・進捗判定には geometry 距離を、外部データとの対応付け・debug には source 系を使う。
 *
 * @property sourceDistanceFromStartMeters 外部データ上のルート始点からの距離 (m)。
 *   対応付けできなければ null。
 * @property geometryDistanceFromStartMeters route geometry に snap した始点からの距離 (m)。
 *   進捗・表示判定はこの値を使う。
 * @property location geometry 上の座標。
 * @property sourceGuidancePointIndex 対応付けた GuidancePoint の index。無ければ null。
 * @property sourceBlockIndex 対応付けた GuideBlock の index。無ければ null。
 * @property matchErrorMeters source ↔ geometry の近傍一致誤差 (m)。debug 用。算出不能なら null。
 */
@Immutable
data class RouteAnchor(
    val sourceDistanceFromStartMeters: Double?,
    val geometryDistanceFromStartMeters: Double,
    val location: RoutePoint,
    val sourceGuidancePointIndex: Int?,
    val sourceBlockIndex: Int?,
    val matchErrorMeters: Double?,
)
