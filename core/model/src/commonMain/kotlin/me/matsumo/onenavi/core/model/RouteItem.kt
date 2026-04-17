package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

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
 * @param congestionSegments geometry 上の渋滞区間。Google Routes API の speedReadingIntervals に対応。
 */
@Immutable
data class RouteItem(
    val durationSeconds: Double,
    val distanceMeters: Double,
    val geometry: ImmutableList<RoutePoint>,
    val viaRoadNames: ImmutableList<String>,
    val hasTolls: Boolean,
    val tollFee: Int? = null,
    val congestionSegments: ImmutableList<CongestionSegment> = kotlinx.collections.immutable.persistentListOf(),
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
 * Google 側のルート情報。
 *
 * @param id アプリ内で使うルート ID
 * @param routeToken Google Navigation SDK へ渡す route token。取得できない場合は座標指定で案内する。
 * @param origin 出発地
 * @param destination 目的地
 * @param intermediateWaypoints 経由地
 * @param geometry ルート形状
 * @param distanceMeters ルート全体の距離
 * @param durationSeconds ルート全体の所要時間
 * @param steps Google Routes API から取得した案内ステップ
 */
@Immutable
data class GoogleRoute(
    val id: String,
    val routeToken: String?,
    val origin: RoutePoint,
    val destination: RoutePoint,
    val intermediateWaypoints: ImmutableList<RoutePoint>,
    val geometry: ImmutableList<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: ImmutableList<RouteStepInfo>,
)
