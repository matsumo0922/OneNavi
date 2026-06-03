package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable

/**
 * Guidance 期の一度きりのイベント。
 *
 * [GuidanceState] は現在状態を表し、この event は画面遷移など消費型の副作用を表す。
 */
@Immutable
sealed interface GuidanceEvent {

    /** 目的地に到達し、案内 session が完了した。 */
    data object DestinationReached : GuidanceEvent
}
