package me.matsumo.onenavi.core.common.car

/**
 * スマホ側の目的地検索 UI を起動する入口。
 */
interface PhoneDestinationSearchLauncher {

    /**
     * スマホ側の目的地検索 UI を起動する。
     *
     * @return 起動結果
     */
    fun launchDestinationSearch(): Result<Unit>

    companion object {

        /** スマホ側で目的地検索を開く explicit intent action。 */
        const val ACTION_OPEN_DESTINATION_SEARCH = "me.matsumo.onenavi.action.OPEN_DESTINATION_SEARCH"
    }
}
