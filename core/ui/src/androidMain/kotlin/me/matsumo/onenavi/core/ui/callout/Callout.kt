package me.matsumo.onenavi.core.ui.callout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * スロットパターンの Callout。背景と吹き出し形状、角丸本体のクリック領域のみを担当し、
 * 中身のレイアウト（Text / Icon 等）は呼び出し側が [content] で与える。
 *
 * 配置（画面上のどこに置くか）は [CalloutLayer] が担当するため、このコンポーザブル自体は
 * 位置情報を知らない。
 *
 * @param tailDirection 吹き出し口が伸びるコーナー
 * @param modifier 外部から渡される Modifier
 * @param backgroundColor 背景色。デフォルトは [MaterialTheme.colorScheme.surface]
 * @param contentColor 中身の文字色。デフォルトは [MaterialTheme.colorScheme.onSurface]
 * @param contentPadding 本体矩形内の内部余白
 * @param onClick 非 null の場合、本体矩形部分のみクリック可能になる。tail はタップ透過
 * @param content スロット
 */
@Composable
fun Callout(
    tailDirection: CalloutTailDirection,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember(tailDirection) { CalloutShape(tailDirection) }
    val bodyShape = remember { RoundedCornerShape(CalloutShape.DEFAULT_CORNER_RADIUS) }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                .background(color = backgroundColor, shape = shape)
                .padding(CalloutShape.DEFAULT_TAIL_LENGTH)
                .clip(bodyShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}
