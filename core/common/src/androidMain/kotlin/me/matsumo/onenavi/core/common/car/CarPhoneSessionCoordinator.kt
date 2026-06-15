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
    private val _phoneCommands = MutableStateFlow<Map<OneNaviDisplaySurface, CarPhoneSessionCommandEnvelope>>(
        emptyMap(),
    )
    private var nextCommandId = INITIAL_COMMAND_ID

    /** 現在プロセス内で有効な表示面の状態。 */
    val state: StateFlow<CarPhoneSessionState> = _state.asStateFlow()

    /** 表示面ごとに一度だけ処理する操作要求。 */
    val phoneCommands: StateFlow<Map<OneNaviDisplaySurface, CarPhoneSessionCommandEnvelope>> = _phoneCommands.asStateFlow()

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
    fun requestPhoneDestinationSearch(): Long {
        return publishCommand(
            command = CarPhoneSessionCommand.OpenDestinationSearch,
            targetSurface = OneNaviDisplaySurface.Phone,
        )
    }

    /** スマホ側で案内中の経由地追加 UI を開く command を発行する。 */
    fun requestPhoneAddWaypointSearch(): Long {
        return publishCommand(
            command = CarPhoneSessionCommand.OpenAddWaypointSearch,
            targetSurface = OneNaviDisplaySurface.Phone,
        )
    }

    /**
     * アシスタントから受け取った案内要求を指定表示面へ発行する。
     *
     * @param request アシスタントから抽出した要求
     * @param targetSurface 実行する表示面
     * @return 発行した command id
     */
    fun requestAssistantNavigation(request: AssistantNavRequest, targetSurface: OneNaviDisplaySurface): Long {
        return publishCommand(
            command = request.toCommand(),
            targetSurface = targetSurface,
        )
    }

    /** 指定 command を処理済みにする。 */
    fun consumePhoneCommand(surface: OneNaviDisplaySurface, commandId: Long) {
        _phoneCommands.update { currentCommands ->
            if (currentCommands[surface]?.id == commandId) {
                currentCommands - surface
            } else {
                currentCommands
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

    private fun publishCommand(command: CarPhoneSessionCommand, targetSurface: OneNaviDisplaySurface): Long {
        return synchronized(lock) {
            val commandId = nextCommandId
            nextCommandId += COMMAND_ID_INCREMENT
            val envelope = CarPhoneSessionCommandEnvelope(
                id = commandId,
                command = command,
            )
            _phoneCommands.value = _phoneCommands.value + (targetSurface to envelope)

            commandId
        }
    }

    private fun AssistantNavRequest.toCommand(): CarPhoneSessionCommand {
        return when (this) {
            is AssistantNavRequest.AddStop -> CarPhoneSessionCommand.AddStop(
                query = query,
                coordinate = coordinate,
            )

            is AssistantNavRequest.Navigate -> CarPhoneSessionCommand.NavigateTo(
                query = query,
                coordinate = coordinate,
            )

            is AssistantNavRequest.Preview -> CarPhoneSessionCommand.PreviewRoute(
                query = query,
                coordinate = coordinate,
            )

            is AssistantNavRequest.Search -> CarPhoneSessionCommand.SearchPlaces(
                query = query,
            )
        }
    }

    private companion object {

        /** 最初の command id。 */
        const val INITIAL_COMMAND_ID = 1L

        /** command id の加算値。 */
        const val COMMAND_ID_INCREMENT = 1L
    }
}
