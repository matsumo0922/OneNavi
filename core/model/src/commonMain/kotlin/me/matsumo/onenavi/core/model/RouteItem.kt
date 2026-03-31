package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート検索結果の1経路分のデータ。
 * Mapbox Directions API のレスポンスから変換される。
 *
 * @param durationSeconds 所要時間（秒）
 * @param distanceMeters 距離（メートル）
 * @param geometry 経路の座標リスト
 * @param summary 経路の概要テキスト（主要道路名等）
 */
@Immutable
data class RouteItem(
    val durationSeconds: Double,
    val distanceMeters: Double,
    val geometry: List<RoutePoint>,
    val summary: String,
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
