package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * Google Places API (New) の検索結果詳細データ。
 * Place Details / Text Search の結果から変換される。
 *
 * @param placeId Google Place ID。再取得・履歴復元に使用する
 * @param name ローカライズされた地点名称
 * @param formattedAddress フォーマット済みの完全な住所文字列
 * @param shortFormattedAddress 省略形式の住所文字列
 * @param latitude 緯度
 * @param longitude 経度
 * @param viewportSouth ビューポート南端緯度（存在しない場合は null）
 * @param viewportWest ビューポート西端経度
 * @param viewportNorth ビューポート北端緯度
 * @param viewportEast ビューポート東端経度
 * @param primaryType 地点の主要タイプ ID（例: "restaurant"）
 * @param primaryTypeDisplayName ローカライズされた主要タイプ表示名（例: "レストラン"）
 * @param types 地点タイプの ID リスト
 * @param googleMapsUri Google Maps へのディープリンク URI
 * @param websiteUri 地点の公式ウェブサイト URI
 * @param internationalPhoneNumber 国際形式の電話番号
 * @param nationalPhoneNumber 国内形式の電話番号
 * @param rating ユーザー評価（0.0〜5.0）
 * @param userRatingCount 評価件数
 * @param priceLevel 価格レベル（0〜4）
 * @param businessStatus 営業状態（"OPERATIONAL", "CLOSED_TEMPORARILY" 等）
 * @param iconBackgroundColor アイコン背景色の HEX 値
 * @param iconMaskUrl アイコンマスク画像 URL
 * @param editorialSummary 編集者によるサマリーテキスト
 * @param currentOpeningHours 現在の営業時間テキスト（例: "9:00～21:00"）
 * @param isOpenNow 現在営業中かどうか
 */
@Immutable
data class SearchResultItem(
    val placeId: String,
    val name: String,
    val formattedAddress: String?,
    val shortFormattedAddress: String?,
    val latitude: Double,
    val longitude: Double,
    val viewportSouth: Double?,
    val viewportWest: Double?,
    val viewportNorth: Double?,
    val viewportEast: Double?,
    val primaryType: String?,
    val primaryTypeDisplayName: String?,
    val types: List<String>,
    val googleMapsUri: String?,
    val websiteUri: String?,
    val internationalPhoneNumber: String?,
    val nationalPhoneNumber: String?,
    val rating: Double?,
    val userRatingCount: Int?,
    val priceLevel: Int?,
    val businessStatus: String?,
    val iconBackgroundColor: String?,
    val iconMaskUrl: String?,
    val editorialSummary: String?,
    val currentOpeningHours: String?,
    val isOpenNow: Boolean?,
)
