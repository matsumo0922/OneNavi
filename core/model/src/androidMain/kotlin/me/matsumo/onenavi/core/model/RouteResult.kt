package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート検索結果。UI 表示用の [RouteItem] と案内用の [RouteDetail] を保持する。
 *
 * @param item UI 表示用のルート情報
 * @param detail 案内に使うルート詳細
 */
@Immutable
data class RouteResult(
    val item: RouteItem,
    val detail: RouteDetail,
)
