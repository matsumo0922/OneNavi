package me.matsumo.onenavi.feature.home.map.state

import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.SearchResultItem

/**
 * HomeMap 画面の one-shot 副作用。
 * Channel 経由で送信され、Composable 側で collect して処理される。
 */
sealed interface HomeMapEffect {

    /** 検索結果全体が収まるようカメラを移動 */
    data class MoveCameraToSearchResults(
        val results: ImmutableList<SearchResultItem>,
    ) : HomeMapEffect

    /** 選択された地点へカメラを移動 */
    data class MoveCameraToPlace(
        val place: SearchResultItem,
    ) : HomeMapEffect

    /** ルート全体が収まるようカメラを Overview モードに移動 */
    data object MoveCameraToRouteOverview : HomeMapEffect

    /** ナビゲーション Following モードに切り替え */
    data object EnterGuidanceFollowing : HomeMapEffect

    /** トラッキングモードを復元 */
    data object RestoreTracking : HomeMapEffect

    /** 画面常時点灯の ON/OFF */
    data class SetKeepScreenOn(
        val enabled: Boolean,
    ) : HomeMapEffect

    /** ナビゲーション LocationProvider の切り替え */
    data class UseNavigationLocationProvider(
        val enabled: Boolean,
    ) : HomeMapEffect
}
