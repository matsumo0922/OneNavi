package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * ルート検索結果の1経路分のデータ。
 * Google Routes API のレスポンスから変換される。
 *
 * @param durationSeconds 所要時間（秒）。交通状況を加味した値。
 * @param distanceMeters 距離（メートル）
 * @param geometry 経路の座標リスト（地図上のポリライン描画用）
 * @param viaRoadNames 経由する主要道路名。現状の Google Routes API 実装では未取得のため通常は空。
 * @param hasTolls 有料道路区間を含むかどうか
 * @param tollFee 有料道路の料金（円）。null の場合は料金不明。
 * @param congestionSegments geometry に沿った渋滞区間。ルート計算時点のスナップショット。渋滞が無ければ空。
 * @param priorityLabel ルート種別の表示名（例: 「推奨」「渋滞回避」）。外部ナビ API 由来の場合のみ設定される。
 */
@Immutable
data class RouteItem(
    val durationSeconds: Double,
    val distanceMeters: Double,
    val geometry: ImmutableList<RoutePoint>,
    val viaRoadNames: ImmutableList<String>,
    val hasTolls: Boolean,
    val tollFee: Int? = null,
    val congestionSegments: ImmutableList<CongestionSegment> = persistentListOf(),
    val priorityLabel: String? = null,
)

/**
 * 経路上の1座標点。
 *
 * @param latitude 緯度
 * @param longitude 経度
 */
@Immutable
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
)

/**
 * 有料道路1区間ぶんの料金内訳。
 *
 * @param roadName 有料道路の名称（例: 「東名高速道路」）
 * @param amount 料金（円）
 */
@Immutable
data class TollSegmentFee(
    val roadName: String,
    val amount: Int,
)

/**
 * ルートが通る道路1区間ぶんの距離。
 *
 * @param roadName 道路の名称（例: 「東名高速道路」「環状七号線」）。空欄や匿名区間は持たない前提。
 * @param distanceMeters 区間の走行距離（メートル）
 */
@Immutable
data class RoadSegmentDistance(
    val roadName: String,
    val distanceMeters: Int,
)

/**
 * ルート全体の詳細情報。案内開始に必要なルートデータを保持する。
 * 取得元の DataSource（外部ナビ API など）に依存しない中立なモデル。
 *
 * @param id アプリ内で使うルート ID
 * @param origin 出発地
 * @param destination 目的地
 * @param intermediateWaypoints 経由地
 * @param geometry ルート形状
 * @param distanceMeters ルート全体の距離
 * @param durationSeconds ルート全体の所要時間
 * @param steps 案内ステップ
 * @param roadClassSegments geometry を道路種別（高速 / 一般道）ごとに区切ったセグメント列。経路サマリ由来で境界は近似。空の場合は色分けしない。
 * @param congestionSegments geometry に沿った渋滞区間。ルート計算時点のスナップショット。渋滞が無ければ空。
 * @param priority ルート種別（推奨 / 渋滞回避 / 等）。取得元が複数候補を返さないソースでは null。
 * @param tollFee 有料道路の合計料金（円）。料金不明 / 有料区間なしの場合は null。
 * @param tollDetails 有料道路の道路別料金内訳。空の場合は内訳不明 / 有料区間なし。
 * @param roadSegments ルートが通る道路の名前と距離。走行順は保持しない（同じ道路は複数区間に分割されている場合がある）。
 * @param routeWaypoints 出発地、経由地、目的地を含む表示用の地点列。地点名を持たないルートでは空。
 */
@Immutable
data class RouteDetail(
    val id: String,
    val origin: RoutePoint,
    val destination: RoutePoint,
    val intermediateWaypoints: ImmutableList<RoutePoint>,
    val geometry: ImmutableList<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: ImmutableList<RouteStepInfo>,
    val roadClassSegments: ImmutableList<RoadClassSegment> = persistentListOf(),
    val congestionSegments: ImmutableList<CongestionSegment> = persistentListOf(),
    val priority: RoutePriority? = null,
    val tollFee: Int? = null,
    val tollDetails: ImmutableList<TollSegmentFee> = persistentListOf(),
    val roadSegments: ImmutableList<RoadSegmentDistance> = persistentListOf(),
    val routeWaypoints: ImmutableList<RouteWaypoint> = persistentListOf(),
) {
    /**
     * ルート上で最初に高速道路に入る IC / JCT 名。
     * 高速区間が複数ある場合は最初の入口、高速区間が無い / 名前が取れない場合は null。
     */
    val entryInterchangeName: String?
        get() = roadClassSegments
            .firstOrNull { segment -> segment.roadClass == RoadClass.HIGHWAY }
            ?.entryInterchangeName

    /**
     * ルート上で最後に高速道路から出る IC / JCT 名。
     * 高速区間が複数ある場合は最後の出口、高速区間が無い / 名前が取れない場合は null。
     */
    val exitInterchangeName: String?
        get() = roadClassSegments
            .lastOrNull { segment -> segment.roadClass == RoadClass.HIGHWAY }
            ?.exitInterchangeName

    /**
     * 通る道路を走行距離の長い順に並べた名前リスト。
     * 同じ道路名が分割されて [roadSegments] に複数入っていても距離を合算した上で 1 件に集約する。
     */
    val roadNamesByDistance: ImmutableList<String>
        get() = roadSegments
            .groupingBy { segment -> segment.roadName }
            .fold(0L) { accumulated, segment -> accumulated + segment.distanceMeters }
            .entries
            .sortedByDescending { entry -> entry.value }
            .map { entry -> entry.key }
            .toImmutableList()
}
