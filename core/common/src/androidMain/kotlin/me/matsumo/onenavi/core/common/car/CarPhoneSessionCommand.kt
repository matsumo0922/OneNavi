package me.matsumo.onenavi.core.common.car

import androidx.compose.runtime.Immutable

/**
 * 車載表示とスマホ表示の間で一度だけ処理する操作要求。
 */
sealed interface CarPhoneSessionCommand {

    /** スマホ側で目的地検索 UI を開く。 */
    data object OpenDestinationSearch : CarPhoneSessionCommand

    /** スマホ側で案内中の経由地追加 UI を開く。 */
    data object OpenAddWaypointSearch : CarPhoneSessionCommand

    /**
     * 目的地を設定し、案内を自動開始する。
     *
     * @property query 目的地名。座標のみの要求では null
     * @property coordinate 目的地座標。検索語のみの要求では null
     */
    @Immutable
    data class NavigateTo(
        val query: String?,
        val coordinate: AssistantNavCoordinate?,
    ) : CarPhoneSessionCommand

    /**
     * 目的地のルートプレビューを表示する。
     *
     * @property query 目的地名。座標のみの要求では null
     * @property coordinate 目的地座標。検索語のみの要求では null
     */
    @Immutable
    data class PreviewRoute(
        val query: String?,
        val coordinate: AssistantNavCoordinate?,
    ) : CarPhoneSessionCommand

    /**
     * 場所検索結果を表示する。
     *
     * @property query 検索語
     */
    @Immutable
    data class SearchPlaces(
        val query: String,
    ) : CarPhoneSessionCommand

    /**
     * 経由地を追加する。
     *
     * @property query 経由地名。座標のみの要求では null
     * @property coordinate 経由地座標。検索語のみの要求では null
     */
    @Immutable
    data class AddStop(
        val query: String?,
        val coordinate: AssistantNavCoordinate?,
    ) : CarPhoneSessionCommand
}

/**
 * 一度だけ処理する command と識別子の組。
 *
 * @property id command を消費済みにするための識別子
 * @property command 処理対象の操作要求
 */
@Immutable
data class CarPhoneSessionCommandEnvelope(
    val id: Long,
    val command: CarPhoneSessionCommand,
)
