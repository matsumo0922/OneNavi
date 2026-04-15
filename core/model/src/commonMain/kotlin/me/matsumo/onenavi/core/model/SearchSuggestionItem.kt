package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 検索候補の表示用データ。
 * Google Places の候補から変換して UI に渡す。
 */
@Immutable
data class SearchSuggestionItem(
    val id: String,
    val name: String,
    val address: String?,
    val distanceMeters: Double?,
    val categories: List<String>,
)
