package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint

/**
 * ルートプレビュー画面のトップバー表示モード。
 * Viewing: 確定済みルートの閲覧
 * Editing: waypoint のドラッグ並べ替え・追加・削除中
 */
@Immutable
sealed interface RoutePreviewTopBarMode {

    /** 確定済みルートの閲覧モード */
    @Stable
    data object Viewing : RoutePreviewTopBarMode

    /** waypoint 編集中モード */
    @Immutable
    data class Editing(
        val draftWaypoints: ImmutableList<RouteWaypoint?>,
    ) : RoutePreviewTopBarMode
}
