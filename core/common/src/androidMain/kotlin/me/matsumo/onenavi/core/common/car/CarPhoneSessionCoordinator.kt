package me.matsumo.onenavi.core.common.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 車載表示とスマホ表示の同期に使うプロセス内 coordinator。
 *
 * ルート探索や案内の正本は navigation manager に残し、この coordinator は表示面の在席状態と
 * 一度だけ処理する操作要求だけを扱う。
 */
class CarPhoneSessionCoordinator {

    private val lock = Any()
    private val surfaceCounts = mutableMapOf<OneNaviDisplaySurface, Int>()
    private val _state = MutableStateFlow(CarPhoneSessionState())
    private val _phoneCommand = MutableStateFlow<CarPhoneSessionCommandEnvelope?>(null)
    private var nextCommandId = INITIAL_COMMAND_ID

    /** 現在プロセス内で有効な表示面の状態。 */
    val state: StateFlow<CarPhoneSessionState> = _state.asStateFlow()

    /** スマホ表示が一度だけ処理する操作要求。 */
    val phoneCommand: StateFlow<CarPhoneSessionCommandEnvelope?> = _phoneCommand.asStateFlow()

    /** 表示面が開始したことを記録する。 */
    fun registerSurface(surface: OneNaviDisplaySurface) {
        synchronized(lock) {
            val currentCount = surfaceCounts[surface] ?: 0
            surfaceCounts[surface] = currentCount + 1
            publishStateLocked()
        }
    }

    /** 表示面が終了したことを記録する。 */
    fun unregisterSurface(surface: OneNaviDisplaySurface) {
        synchronized(lock) {
            val currentCount = surfaceCounts[surface] ?: 0
            val nextCount = currentCount - 1

            if (nextCount > 0) {
                surfaceCounts[surface] = nextCount
            } else {
                surfaceCounts.remove(surface)
            }

            publishStateLocked()
        }
    }

    /** スマホ側で目的地検索 UI を開く command を発行する。 */
    fun requestPhoneDestinationSearch() {
        synchronized(lock) {
            val commandId = nextCommandId
            nextCommandId += COMMAND_ID_INCREMENT
            _phoneCommand.value = CarPhoneSessionCommandEnvelope(
                id = commandId,
                command = CarPhoneSessionCommand.OpenDestinationSearch,
            )
        }
    }

    /** 指定 command を処理済みにする。 */
    fun consumePhoneCommand(commandId: Long) {
        _phoneCommand.update { currentCommand ->
            if (currentCommand?.id == commandId) {
                null
            } else {
                currentCommand
            }
        }
    }

    private fun publishStateLocked() {
        _state.value = CarPhoneSessionState(
            activeSurfaces = surfaceCounts
                .filterValues { count -> count > 0 }
                .keys
                .toSet(),
        )
    }

    private companion object {

        /** 最初の command id。 */
        const val INITIAL_COMMAND_ID = 1L

        /** command id の加算値。 */
        const val COMMAND_ID_INCREMENT = 1L
    }
}
