package me.matsumo.onenavi.feature.map.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_control_recenter
import org.jetbrains.compose.resources.stringResource

/**
 * 自車追従が外れている間だけ地図領域の下部に出す「現在地に戻る」ボタン。
 *
 * 追従中は非表示にし、追従が外れたときのみフェードで現れる。タップで自車追従（案内中は案内カメラ）へ復帰する。
 *
 * @param isVisible 表示するか。自車追従中は false
 * @param bottomPadding 下部の障害物（ボトムシート / 案内カード / ナビバー）を避ける下 padding
 * @param onClicked ボタン押下時の処理
 */
@Composable
internal fun MapRecenterButton(
    isVisible: Boolean,
    bottomPadding: Dp,
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Button(onClicked) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                    )

                    Text(
                        text = stringResource(Res.string.home_map_control_recenter),
                    )
                }
            }
        }
    }
}
