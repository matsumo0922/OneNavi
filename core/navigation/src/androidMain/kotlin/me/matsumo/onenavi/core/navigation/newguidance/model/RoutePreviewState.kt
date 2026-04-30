package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * Preview 期 (案内開始前) のルート選択画面の状態。
 *
 * spec/24 §3.3 / §4 に対応。NewRouteManager がこの値を [kotlinx.coroutines.flow.StateFlow]
 * として公開し、UI 側は [Ready] のときに描画と選択 UI を出す。
 */
@Immutable
sealed interface RoutePreviewState {

    /** 初期状態 / リセット直後。UI は何も出さない。 */
    data object Idle : RoutePreviewState

    /** 外部ナビ API ライブラリ + Routes API 呼び出し中。 */
    data object Searching : RoutePreviewState

    /**
     * 全候補の refine が完了しユーザに提示できる状態。
     *
     * @param routes Preview に並べる候補ルート一覧 (1 本以上)。各候補は spec/23 で refine 済み
     * @param selectedIndex 現在ユーザが選んでいるルートの index。Navigator.setDestinations は
     *                      この index の chunk0.routeToken を使う
     */
    @Immutable
    data class Ready(
        val routes: ImmutableList<RefinedRoute>,
        val selectedIndex: Int,
    ) : RoutePreviewState {
        val selectedRoute: RefinedRoute
            get() = routes[selectedIndex]
    }

    /** 探索失敗。`error` には原因の Throwable が入る。 */
    @Immutable
    data class Failed(
        val error: Throwable,
    ) : RoutePreviewState
}
