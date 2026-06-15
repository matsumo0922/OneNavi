package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable

/**
 * 案内中に使う測位信号の状態。
 */
@Immutable
sealed interface GpsSignalState {

    /** 使用可能な観測位置が直近で得られている状態。 */
    data object Available : GpsSignalState

    /**
     * 使用可能な観測位置が途絶している状態。
     *
     * @param elapsedSeconds 使用可能な観測位置が途絶してからの秒数
     */
    @Immutable
    data class Lost(
        val elapsedSeconds: Float,
    ) : GpsSignalState
}
