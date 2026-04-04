package me.matsumo.onenavi.core.model

/**
 * ナビゲーション画面全体の状態を表す sealed interface。
 * 各状態はマーカー（data object）とし、状態固有のデータは別の StateFlow で管理する。
 * これにより、RouteProgress の毎秒更新で NavigationState が変わらず、不要な再コンポーズを防ぐ。
 */
sealed interface NavigationState {

    /** 地図ブラウジング中。検索バーとコントロール列が表示される。 */
    data object Browsing : NavigationState

    /** 検索結果表示中。検索バーと BottomSheet が表示される。 */
    data object Search : NavigationState

    /** ルートプレビュー中。ルート一覧と概要が表示される。 */
    data object RoutePreview : NavigationState

    /** ターンバイターンナビゲーション中。マニューバパネルとトリップカードが表示される。 */
    data object ActiveGuidance : NavigationState

    /** 目的地到着。到着画面が表示される。 */
    data object Arrival : NavigationState

    /** フリードライブモード。Phase 1 では未使用、定義のみ。 */
    data object FreeDrive : NavigationState
}
