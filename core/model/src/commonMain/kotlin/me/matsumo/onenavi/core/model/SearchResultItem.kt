package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 検索結果の詳細データ。
 * 候補選択後に Mapbox SearchResult から変換される。
 *
 * @param id Mapbox が返す一意な検索結果 ID
 * @param mapboxId Mapbox POI の永続 ID（存在しない場合は null）
 * @param name 地点名称（施設名・住所ラベル等）
 * @param fullAddress フォーマット済みの完全な住所文字列
 * @param descriptionText 検索結果の補足説明テキスト
 * @param matchingName クエリにマッチした名称（name と異なる場合のみ非 null）
 * @param accuracy 座標の精度レベル（"rooftop", "interpolated" 等）
 * @param makiIcon Maki アイコン名（UI でのアイコン表示用）
 * @param latitude 緯度
 * @param longitude 緯度
 * @param boundingBoxSouth バウンディングボックス南端緯度（存在しない場合は null）
 * @param boundingBoxWest バウンディングボックス西端経度
 * @param boundingBoxNorth バウンディングボックス北端緯度
 * @param boundingBoxEast バウンディングボックス東端経度
 * @param routableLatitude ルーティング用入口座標の緯度（建物入口等、存在しない場合は null）
 * @param routableLongitude ルーティング用入口座標の経度
 * @param categories POI カテゴリ名のリスト
 * @param categoryIds POI カテゴリの正規 ID リスト
 * @param distanceMeters 検索元地点からの距離（メートル）
 * @param etaMinutes 検索元地点からの推定到着時間（分）
 * @param externalIds 外部サービスの ID マップ（例: "foursquare" → "xxx"）
 * @param resultTypes 検索結果の種別リスト（"POI", "ADDRESS" 等）
 */
@Immutable
data class SearchResultItem(
    val id: String,
    val mapboxId: String?,
    val name: String,
    val fullAddress: String?,
    val descriptionText: String?,
    val matchingName: String?,
    val accuracy: String?,
    val makiIcon: String?,
    val latitude: Double,
    val longitude: Double,
    val boundingBoxSouth: Double?,
    val boundingBoxWest: Double?,
    val boundingBoxNorth: Double?,
    val boundingBoxEast: Double?,
    val routableLatitude: Double?,
    val routableLongitude: Double?,
    val categories: List<String>,
    val categoryIds: List<String>,
    val distanceMeters: Double?,
    val etaMinutes: Double?,
    val externalIds: Map<String, String>,
    val resultTypes: List<String>,
)
