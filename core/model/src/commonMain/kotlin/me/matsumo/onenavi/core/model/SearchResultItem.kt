package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 検索結果の詳細データ。
 * 候補選択後に Mapbox SearchResult から変換される。
 */
@Immutable
data class SearchResultItem(
    val id: String,
    val name: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val categories: List<String>,
)
