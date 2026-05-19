package me.matsumo.onenavi.feature.map.components.content

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.matsumo.onenavi.feature.map.state.MapUiEvent

/**
 * ナビゲーション中の UI レイヤー。
 *
 * 戻る操作は画面スタックの単純な pop ではなく、ナビゲーション停止イベントとして扱う。
 * これにより UI の戻る操作と案内停止の副作用を [MapUiEvent.OnNavigationStop] に集約する。
 */
@Composable
internal fun MapNavigationContent(
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationEventHandler(
        state = navigationState,
    ) {
        onUiEvent(MapUiEvent.OnNavigationStop)
    }

    Box(
        modifier = modifier,
    )
}
