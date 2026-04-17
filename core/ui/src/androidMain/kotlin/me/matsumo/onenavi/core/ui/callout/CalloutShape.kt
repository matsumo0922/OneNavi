package me.matsumo.onenavi.core.ui.callout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Callout 用の吹き出し形状。角丸矩形と「指定コーナーから外へ 45° に突き出す三角」を
 * [PathOperation.Union] でマージして 1 つのアウトラインにする。
 *
 * [Size] には四方に [tailLength] 分の余白が含まれている前提。内側の角丸矩形は
 * 全方向 [tailLength] インセットした領域に描かれ、[tailDirection] のコーナーから
 * 三角形が外側に伸びる。こうすることで Callout 全体の bounds は tail 方向が変わっても
 * 同じサイズを保ち、SubcomposeLayout のサイズ不変性を成立させる。
 *
 * @param tailDirection 吹き出し口が伸びるコーナー
 * @param cornerRadius 本体矩形の角丸半径
 * @param tailLength 本体矩形の各辺に確保する tail 余白（= 先端までの距離）
 */
@Immutable
internal class CalloutShape(
    private val tailDirection: CalloutTailDirection,
    private val cornerRadius: Dp = DEFAULT_CORNER_RADIUS,
    private val tailLength: Dp = DEFAULT_TAIL_LENGTH,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radiusPx = with(density) { cornerRadius.toPx() }
        val tailPx = with(density) { tailLength.toPx() }

        val body = Rect(
            left = tailPx,
            top = tailPx,
            right = size.width - tailPx,
            bottom = size.height - tailPx,
        )

        val bodyPath = Path().apply {
            addRoundRect(RoundRect(body, CornerRadius(radiusPx)))
        }

        val (baseA, baseB, tip) = tailVertices(
            body = body,
            direction = tailDirection,
            tailLength = tailPx,
            cornerRadius = radiusPx,
        )
        val tailPath = Path().apply {
            moveTo(baseA.x, baseA.y)
            lineTo(tip.x, tip.y)
            lineTo(baseB.x, baseB.y)
            close()
        }

        val merged = Path().apply {
            op(bodyPath, tailPath, PathOperation.Union)
        }
        return Outline.Generic(merged)
    }

    private fun tailVertices(
        body: Rect,
        direction: CalloutTailDirection,
        tailLength: Float,
        cornerRadius: Float,
    ): Triple<Offset, Offset, Offset> = when (direction) {
        CalloutTailDirection.BottomLeft -> Triple(
            Offset(body.left + cornerRadius, body.bottom),
            Offset(body.left, body.bottom - cornerRadius),
            Offset(body.left - tailLength, body.bottom + tailLength),
        )
        CalloutTailDirection.BottomRight -> Triple(
            Offset(body.right - cornerRadius, body.bottom),
            Offset(body.right, body.bottom - cornerRadius),
            Offset(body.right + tailLength, body.bottom + tailLength),
        )
        CalloutTailDirection.TopLeft -> Triple(
            Offset(body.left + cornerRadius, body.top),
            Offset(body.left, body.top + cornerRadius),
            Offset(body.left - tailLength, body.top - tailLength),
        )
        CalloutTailDirection.TopRight -> Triple(
            Offset(body.right - cornerRadius, body.top),
            Offset(body.right, body.top + cornerRadius),
            Offset(body.right + tailLength, body.top - tailLength),
        )
    }

    companion object {
        val DEFAULT_CORNER_RADIUS: Dp = 16.dp
        val DEFAULT_TAIL_LENGTH: Dp = 10.dp
    }
}

/**
 * Callout bounds における tail 先端のローカル座標を返す。
 *
 * [CalloutShape] の設計により、tail 先端は bounds の 4 つの隅いずれかに一致する。
 * 呼び出し側はこの値を用いて「tail 先端を画面上の特定ポイントに合わせる」配置計算を行える。
 */
internal fun tailTipLocalOffset(
    direction: CalloutTailDirection,
    size: Size,
): Offset = when (direction) {
    CalloutTailDirection.TopLeft -> Offset.Zero
    CalloutTailDirection.TopRight -> Offset(size.width, 0f)
    CalloutTailDirection.BottomLeft -> Offset(0f, size.height)
    CalloutTailDirection.BottomRight -> Offset(size.width, size.height)
}
