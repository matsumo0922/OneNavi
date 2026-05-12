package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.GoogleRoute

/**
 * Preview 期 (案内開始前) のルート選択画面の状態。
 *
 * NewRouteManager がこの値を [kotlinx.coroutines.flow.StateFlow] として公開し、UI 側は [Ready] の
 * ときに地図描画 ([GoogleRoute.geometry] を自前 polyline で描画) と選択 UI を出す。
 */
@Immutable
sealed interface RoutePreviewState {

    /** 初期状態 / リセット直後。UI は何も出さない。 */
    data object Idle : RoutePreviewState

    /** 外部ナビ API ライブラリにルート探索を問い合わせ中。 */
    data object Searching : RoutePreviewState

    /**
     * ルート候補が揃いユーザに提示できる状態。
     *
     * @param routes Preview に並べる候補ルート一覧 (1 本以上)
     * @param selectedIndex 現在ユーザが選んでいるルートの index
     */
    @Immutable
    data class Ready(
        val routes: ImmutableList<GoogleRoute>,
        val selectedIndex: Int,
    ) : RoutePreviewState {
        val selectedRoute: GoogleRoute
            get() = routes[selectedIndex]
    }

    /** 探索失敗。`error` には原因の Throwable が入る。 */
    @Immutable
    data class Failed(
        val error: Throwable,
    ) : RoutePreviewState
}
