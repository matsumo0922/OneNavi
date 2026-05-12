package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
)
