package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート検索結果。UI 用の [RouteItem] とプラットフォーム固有のルートオブジェクトを保持する。
 * Android では [platformRoute] に Mapbox の NavigationRoute が入る。
 *
 * @param item UI 表示用のルート情報
 * @param platformRoute プラットフォーム固有のルートオブジェクト（Android: NavigationRoute）
 */
@Immutable
data class RouteResult(
    val item: RouteItem,
    val platformRoute: Any? = null,
)
