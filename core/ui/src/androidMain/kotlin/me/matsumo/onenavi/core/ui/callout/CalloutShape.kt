package me.matsumo.onenavi.core.ui.callout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Callout 用の吹き出し形状。角丸矩形の 4 コーナーのうち [tailDirection] で指定した
 * 1 コーナーを、斜め 45° に伸びる細い三角形に置き換えた単一 Path を構築する。
 *
 * [Size] には四方に [tailLength] 分の余白が含まれている前提。内側の角丸矩形は
 * 全方向 [tailLength] インセットした領域に描かれ、[tailDirection] のコーナーから
 * 三角形が外側に伸びる。こうすることで Callout 全体の bounds は tail 方向が変わっても
 * 同じサイズを保ち、SubcomposeLayout のサイズ不変性を成立させる。
 *
 * @param tailDirection 吹き出し口が伸びるコーナー
 * @param cornerRadius 本体矩形の角丸半径
 * @param tailLength 本体矩形の各辺に確保する tail 余白（= 先端までの距離）
 * @param tailBaseInset tail 根元が辺の端（コーナー）から離れる距離。小さいほど tail は細くなる
 */
@Immutable
internal class CalloutShape(
    private val tailDirection: CalloutTailDirection,
    private val cornerRadius: Dp = DEFAULT_CORNER_RADIUS,
    private val tailLength: Dp = DEFAULT_TAIL_LENGTH,
    private val tailBaseInset: Dp = DEFAULT_TAIL_BASE_INSET,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = with(density) { cornerRadius.toPx() }
        val tail = with(density) { tailLength.toPx() }
        val baseInset = with(density) { tailBaseInset.toPx() }

        val body = Rect(
            left = tail,
            top = tail,
            right = size.width - tail,
            bottom = size.height - tail,
        )

        val path = buildPath(body, radius, tail, baseInset)
        return Outline.Generic(path)
    }

    private fun buildPath(body: Rect, radius: Float, tail: Float, baseInset: Float): Path {
        return Path().apply {
            val topLeftStart = if (tailDirection == CalloutTailDirection.TopLeft) baseInset else radius
            moveTo(body.left + topLeftStart, body.top)

            drawTopRight(body, radius, tail, baseInset)
            drawBottomRight(body, radius, tail, baseInset)
            drawBottomLeft(body, radius, tail, baseInset)
            drawTopLeft(body, radius, tail, baseInset)

            close()
        }
    }

    private fun Path.drawTopRight(body: Rect, radius: Float, tail: Float, baseInset: Float) {
        if (tailDirection == CalloutTailDirection.TopRight) {
            lineTo(body.right - baseInset, body.top)
            lineTo(body.right + tail, body.top - tail)
            lineTo(body.right, body.top + baseInset)
        } else {
            lineTo(body.right - radius, body.top)
            arcTo(
                rect = cornerRect(Offset(body.right - 2 * radius, body.top), radius),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
        }
    }

    private fun Path.drawBottomRight(body: Rect, radius: Float, tail: Float, baseInset: Float) {
        if (tailDirection == CalloutTailDirection.BottomRight) {
            lineTo(body.right, body.bottom - baseInset)
            lineTo(body.right + tail, body.bottom + tail)
            lineTo(body.right - baseInset, body.bottom)
        } else {
            lineTo(body.right, body.bottom - radius)
            arcTo(
                rect = cornerRect(Offset(body.right - 2 * radius, body.bottom - 2 * radius), radius),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
        }
    }

    private fun Path.drawBottomLeft(body: Rect, radius: Float, tail: Float, baseInset: Float) {
        if (tailDirection == CalloutTailDirection.BottomLeft) {
            lineTo(body.left + baseInset, body.bottom)
            lineTo(body.left - tail, body.bottom + tail)
            lineTo(body.left, body.bottom - baseInset)
        } else {
            lineTo(body.left + radius, body.bottom)
            arcTo(
                rect = cornerRect(Offset(body.left, body.bottom - 2 * radius), radius),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
        }
    }

    private fun Path.drawTopLeft(body: Rect, radius: Float, tail: Float, baseInset: Float) {
        if (tailDirection == CalloutTailDirection.TopLeft) {
            lineTo(body.left, body.top + baseInset)
            lineTo(body.left - tail, body.top - tail)
            lineTo(body.left + baseInset, body.top)
        } else {
            lineTo(body.left, body.top + radius)
            arcTo(
                rect = cornerRect(Offset(body.left, body.top), radius),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
        }
    }

    private fun cornerRect(topLeft: Offset, radius: Float): Rect {
        return Rect(
            left = topLeft.x,
            top = topLeft.y,
            right = topLeft.x + 2 * radius,
            bottom = topLeft.y + 2 * radius,
        )
    }

    companion object {
        val DEFAULT_CORNER_RADIUS: Dp = 10.dp
        val DEFAULT_TAIL_LENGTH: Dp = 9.dp
        val DEFAULT_TAIL_BASE_INSET: Dp = 6.dp
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
