package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/** 地点詳細や検索結果で選択地点に対して実行する主要アクション。 */
@Immutable
sealed interface MapPlaceAction {

    /** 選択地点を目的地にして新規ルート検索を行う。 */
    @Stable
    data object SearchRoute : MapPlaceAction

    /**
     * 選択地点をルートプレビューの経由地として追加する。
     *
     * @property canAddWaypoint true の場合は現在の waypoint 数に追加余地がある。
     */
    @Immutable
    data class AddWaypointToRoutePreview(
        val canAddWaypoint: Boolean,
    ) : MapPlaceAction

    /** 選択地点を案内中ルートの経由地候補として追加する。 */
    @Stable
    data object AddWaypointToNavigation : MapPlaceAction
}
