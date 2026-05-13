package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート上の渋滞度合い。
 * VICS の渋滞レベル（平常 / 混雑 / 渋滞）に対応する。
 */
enum class CongestionSeverity {
    /** 通常の流れ */
    NORMAL,

    /** やや渋滞（混雑。黄色表示） */
    SLOW,

    /** 強い渋滞（赤色表示） */
    TRAFFIC_JAM,

    /** 不明（渋滞データはあるがレベルが判別できない） */
    UNKNOWN,
}

/**
 * 渋滞の傾向。
 * 渋滞区間が今後伸びるか縮むか、断続的かを表す。参照元に細分類（一部増加 / 一部減少）が
 * ある場合は当面 [INCREASING] / [DECREASING] に丸めて受ける。
 */
enum class CongestionTrend {
    /** 変化なし（横ばい） */
    STABLE,

    /** 増加傾向（渋滞が伸びている） */
    INCREASING,

    /** 減少傾向（渋滞が解消に向かっている） */
    DECREASING,

    /** 断続渋滞 */
    INTERMITTENT,

    /** 不明 */
    UNKNOWN,
}

/**
 * ルートの形状（polyline）に沿った連続した渋滞区間 1 件。
 *
 * index ベースの 3 フィールド（[startPolylinePointIndex] / [endPolylinePointIndex] / [severity]）は
 * 地図上のポリライン色分け描画用。距離ベース・地点名・所要時間・傾向は横帯 UI や音声案内用。
 * 値はすべて取得元（外部ナビ API ライブラリ）が算出済みのものをそのまま保持し、OneNavi 側で
 * 座標マッチや測地系変換は行わない。
 *
 * @param startPolylinePointIndex 区間開始の geometry インデックス（包含）
 * @param endPolylinePointIndex 区間終了の geometry インデックス（包含）
 * @param severity 渋滞度合い
 * @param startDistanceMeters ルート始点から渋滞先頭までの累積距離（m）
 * @param endDistanceMeters ルート始点から渋滞末尾までの累積距離（m）
 * @param congestionDistanceMeters 渋滞区間長（m）。サーバ提供値。0 のときは利用側で
 * `endDistanceMeters - startDistanceMeters` を使う。
 * @param transitMinutes 渋滞区間の通過予想所要時間（分）。null=不明。
 * @param trend 渋滞の傾向
 * @param isIntermittent 断続渋滞を含むか
 * @param headPointName 渋滞先頭の地点名（例: 「◯◯ＩＣ」）。null=不明。
 * @param headPointKana 渋滞先頭の地点名の読み（半角カナまたはひらがな）。null=不明。
 * @param headRoadNumbering 渋滞先頭の路線番号（例: 「C2」）。null=不明。
 * @param tailPointName 渋滞末尾の地点名（例: 「△△ＳＡ」）。null=不明。
 * @param tailPointKana 渋滞末尾の地点名の読み。null=不明。
 * @param tailRoadNumbering 渋滞末尾の路線番号。null=不明。
 */
@Immutable
data class CongestionSegment(
    val startPolylinePointIndex: Int,
    val endPolylinePointIndex: Int,
    val severity: CongestionSeverity,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    val congestionDistanceMeters: Double,
    val transitMinutes: Int? = null,
    val trend: CongestionTrend = CongestionTrend.UNKNOWN,
    val isIntermittent: Boolean = false,
    val headPointName: String? = null,
    val headPointKana: String? = null,
    val headRoadNumbering: String? = null,
    val tailPointName: String? = null,
    val tailPointKana: String? = null,
    val tailRoadNumbering: String? = null,
)
