package me.matsumo.onenavi.core.common.car

/**
 * スマホ側の検索 UI を起動する入口。
 */
interface PhoneDestinationSearchLauncher {

    /**
     * スマホ側の目的地検索 UI を起動する。
     *
     * @return 起動結果
     */
    fun launchDestinationSearch(): Result<Unit>

    /**
     * スマホ側の経由地追加 UI を起動する。
     *
     * @return 起動結果
     */
    fun launchAddWaypointSearch(): Result<Unit>
}
