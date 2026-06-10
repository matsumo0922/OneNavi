package me.matsumo.onenavi.core.common.car

/**
 * 車載表示とスマホ表示の間で一度だけ処理する操作要求。
 */
sealed interface CarPhoneSessionCommand {

    /** スマホ側で目的地検索 UI を開く。 */
    data object OpenDestinationSearch : CarPhoneSessionCommand
}

/**
 * 一度だけ処理する command と識別子の組。
 *
 * @property id command を消費済みにするための識別子
 * @property command 処理対象の操作要求
 */
data class CarPhoneSessionCommandEnvelope(
    val id: Long,
    val command: CarPhoneSessionCommand,
)
