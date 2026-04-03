package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート検索結果。shared 層では UI 用の [RouteItem] と routeId だけを保持する。
 *
 * @param id プラットフォーム側ルート実体を解決するための ID
 * @param item UI 表示用のルート情報
 */
@Immutable
data class RouteResult(
    val id: String,
    val item: RouteItem,
)
