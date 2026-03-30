package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 検索履歴の永続化用データ。
 * DataStore に JSON で保存する。
 */
@Immutable
@Serializable
data class SearchHistory(
    val id: String,
    val mapboxId: String?,
    val name: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val searchedAtEpochMillis: Long,
)
