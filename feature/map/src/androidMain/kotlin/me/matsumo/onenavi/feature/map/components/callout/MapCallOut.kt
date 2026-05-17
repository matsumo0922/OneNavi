package me.matsumo.onenavi.feature.map.components.callout

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Google Maps 風の CallOut 本体。
 *
 * 中身は仮 UI なので、呼び出し側が [content] で差し替える。
 */
@Composable
internal fun MapCallOut(
    tailSide: MapCallOutTailSide,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = MapCallOutDefaults.ContentPadding,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember(tailSide) { MapCallOutShape(tailSide) }
    val bodyShape = remember { RoundedCornerShape(MapCallOutDefaults.CornerRadius) }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .mapCallOutShadow(
                    shape = shape,
                )
                .background(
                    color = backgroundColor,
                    shape = shape,
                )
                .padding(MapCallOutDefaults.ShapeInset),
        ) {
            Column(
                modifier = Modifier
                    .clip(bodyShape)
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(contentPadding),
                content = content,
            )
        }
    }
}

/**
 * CallOut の描画寸法。
 */
internal object MapCallOutDefaults {
    val CornerRadius: Dp = 8.dp
    val TailLength: Dp = 9.dp
    val TailBaseInset: Dp = 6.dp
    val ShadowPadding: Dp = 10.dp
    val ShadowBlurRadius: Dp = 8.dp
    val ShadowOffsetY: Dp = 2.dp
    val ShapeInset: Dp = TailLength + ShadowPadding
    val ContentPadding: PaddingValues = PaddingValues(
        horizontal = 12.dp,
        vertical = 8.dp,
    )
}

private fun Modifier.mapCallOutShadow(
    shape: Shape,
    color: Color = Color(0x33000000),
    blurRadius: Dp = MapCallOutDefaults.ShadowBlurRadius,
    offsetY: Dp = MapCallOutDefaults.ShadowOffsetY,
): Modifier = drawBehind {
    val path = when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Generic -> outline.path
        else -> return@drawBehind
    }
    val blurRadiusPx = blurRadius.toPx()
    val offsetYPx = offsetY.toPx()

    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
        }

        canvas.nativeCanvas.save()
        canvas.nativeCanvas.translate(0f, offsetYPx)
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
        canvas.nativeCanvas.restore()
    }
}

/**
 * 角丸矩形の左下または右下から tail が出る Shape。
 */
@Immutable
private class MapCallOutShape(
    private val tailSide: MapCallOutTailSide,
    private val cornerRadius: Dp = MapCallOutDefaults.CornerRadius,
    private val tailLength: Dp = MapCallOutDefaults.TailLength,
    private val tailBaseInset: Dp = MapCallOutDefaults.TailBaseInset,
    private val shadowPadding: Dp = MapCallOutDefaults.ShadowPadding,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = with(density) { cornerRadius.toPx() }
        val tail = with(density) { tailLength.toPx() }
        val baseInset = with(density) { tailBaseInset.toPx() }
        val shadow = with(density) { shadowPadding.toPx() }
        val body = Rect(
            left = shadow + tail,
            top = shadow + tail,
            right = size.width - shadow - tail,
            bottom = size.height - shadow - tail,
        )

        return Outline.Generic(
            Path().apply {
                moveTo(body.left + radius, body.top)
                drawTopRight(body, radius)
                drawBottomRight(body, radius, tail, baseInset)
                drawBottomLeft(body, radius, tail, baseInset)
                drawTopLeft(body, radius)
                close()
            },
        )
    }

    private fun Path.drawTopRight(body: Rect, radius: Float) {
        lineTo(body.right - radius, body.top)
        arcTo(
            rect = cornerRect(Offset(body.right - 2 * radius, body.top), radius),
            startAngleDegrees = 270f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
    }

    private fun Path.drawBottomRight(
        body: Rect,
        radius: Float,
        tail: Float,
        baseInset: Float,
    ) {
        if (tailSide == MapCallOutTailSide.BottomRight) {
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

    private fun Path.drawBottomLeft(
        body: Rect,
        radius: Float,
        tail: Float,
        baseInset: Float,
    ) {
        if (tailSide == MapCallOutTailSide.BottomLeft) {
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

    private fun Path.drawTopLeft(body: Rect, radius: Float) {
        lineTo(body.left, body.top + radius)
        arcTo(
            rect = cornerRect(Offset(body.left, body.top), radius),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
    }

    private fun cornerRect(topLeft: Offset, radius: Float): Rect {
        return Rect(
            left = topLeft.x,
            top = topLeft.y,
            right = topLeft.x + 2 * radius,
            bottom = topLeft.y + 2 * radius,
        )
    }
}
