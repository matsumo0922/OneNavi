package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート上の1ステップ（案内地点）の情報。
 * RouteProgress から抽出され、パネル表示やログ出力に使用される。
 *
 * @param maneuverType マニューバ種別（"turn", "fork", "merge", "on ramp", "off ramp", "arrive" 等）
 * @param modifier 方向修飾子（"left", "right", "slight left", "sharp right", "straight", "uturn" 等）
 * @param distanceFromPreviousMeters 前のステップからの距離（メートル）
 * @param cumulativeDistanceMeters ルート開始からこのステップ開始までの累積距離（メートル）
 * @param maneuverLocation ステップ開始地点（マニューバ点）の緯度経度。Routes API が返さなかった場合は null。
 * @param instruction 案内テキスト（交差点名 / IC 名 / JCT 名 / Google が生成した案内文）
 * @param roadName 道路名。現状の Google Routes API 実装では未取得のため空文字を取りうる。
 * @param roadRef 道路番号（国道○号等）
 * @param highwayInfo 高速道路関連情報（IC / JCT / 料金所等）。該当しない場合は null。
 */
@Immutable
data class RouteStepInfo(
    val maneuverType: String,
    val modifier: String?,
    val distanceFromPreviousMeters: Double,
    val cumulativeDistanceMeters: Double,
    val maneuverLocation: RoutePoint?,
    val instruction: String,
    val roadName: String,
    val roadRef: String?,
    val highwayInfo: HighwayInfo?,
)

/**
 * 高速道路関連の付加情報。
 *
 * @param type 高速道路ポイントの種別
 * @param name IC / JCT / 料金所の名前（取得できない場合は空文字）
 */
@Immutable
data class HighwayInfo(
    val type: HighwayPointType,
    val name: String,
)

/**
 * 高速道路上のポイント種別。
 */
enum class HighwayPointType {
    /** インターチェンジ（IC） */
    INTERCHANGE,

    /** ジャンクション（JCT） */
    JUNCTION,

    /** 料金所 */
    TOLL_GATE,

    /** ランプ（on ramp / off ramp で IC/JCT と判別できない場合） */
    RAMP,
}
