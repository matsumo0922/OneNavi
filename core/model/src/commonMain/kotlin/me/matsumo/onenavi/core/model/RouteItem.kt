package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * ルート検索結果の1経路分のデータ。
 * Mapbox Navigation SDK のレスポンスから変換される。
 *
 * @param durationSeconds 所要時間（秒）。交通状況を加味した値。
 * @param distanceMeters 距離（メートル）
 * @param geometry 経路の座標リスト（地図上のポリライン描画用）
 * @param viaRoadNames 経由する主要道路名（距離が長い順に最大3件）
 * @param hasTolls 有料道路区間を含むかどうか
 */
@Immutable
data class RouteItem(
    val durationSeconds: Double,
    val distanceMeters: Double,
    val geometry: ImmutableList<RoutePoint>,
    val viaRoadNames: ImmutableList<String>,
    val hasTolls: Boolean,
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
