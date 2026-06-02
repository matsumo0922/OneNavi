package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.ui.theme.RouteColors

/**
 * 選択中 route CallOut と同じ青い内側 frame を描画する。
 *
 * @param tailSide CallOut の tail を出す側
 * @param isSelected 選択状態として強調表示するか
 * @param modifier 修飾子
 * @param content 内側 Box に載せる content
 */
@Composable
internal fun MapSelectedCallOutContentFrame(
    tailSide: MapCallOutTailSide,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val callOutColors = RouteColors.callOut
    val contentColor = if (isSelected) callOutColors.onSelectedContainer else callOutColors.unselectedContent

    MapCallOut(
        modifier = modifier,
        tailSide = tailSide,
        backgroundColor = Color.White,
        contentColor = contentColor,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) callOutColors.selectedContainer else Color.Transparent)
                .padding(8.dp, 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            content(contentColor)
        }
    }
}
