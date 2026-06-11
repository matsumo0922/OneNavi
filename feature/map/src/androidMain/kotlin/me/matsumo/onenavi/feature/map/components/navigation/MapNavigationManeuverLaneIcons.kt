package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Horizontal
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.navigation.newguidance.presentation.LaneCell
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 車線図 1 本分のレーン群を左→右に並べて描く。各セルは [MapNavigationManeuverLaneIcon] で 1 レーンを表す。
 */
@Composable
internal fun MapNavigationManeuverLaneIcons(
    lanes: ImmutableList<LaneCell>,
    iconSize: Dp,
    spacing: Dp,
    activeTint: Color,
    inactiveTint: Color,
    modifier: Modifier = Modifier,
    horizontalAlignment: Horizontal = Alignment.Start,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing, horizontalAlignment),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lanes.forEach { lane ->
            MapNavigationManeuverLaneIcon(
                modifier = Modifier.size(iconSize),
                lane = lane,
                activeTint = activeTint,
                inactiveTint = inactiveTint,
            )
        }
    }
}

/**
 * 1 レーンを「下端から伸びる軸 + 各進行方向への枝矢印」のレーングリフとして [Canvas] で描く。
 *
 * 単一矢印アイコンの貼り替えではないため、1 レーンが複数方向 (例: 直進 + 左折) を持つ場合も全方向を
 * 1 つのグリフにまとめて表現できる。推奨車線 (`isActive`) ではレーンの推奨方向だけを [activeTint] で
 * 強調し、同レーンの他方向は中間色、非推奨車線は [inactiveTint] で塗る。
 *
 * @param lane 描画対象のレーン
 * @param activeTint 推奨方向の色
 * @param inactiveTint 非推奨車線の色
 */
@Composable
private fun MapNavigationManeuverLaneIcon(
    lane: LaneCell,
    activeTint: Color,
    inactiveTint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawLaneGlyph(
            directions = lane.allowedDirections,
            recommendedDirection = lane.recommendedDirection,
            isActive = lane.isActive,
            activeTint = activeTint,
            inactiveTint = inactiveTint,
        )
    }
}

/**
 * 1 グリフの描画に使う寸法 (px)。セルサイズと方向数から [drawLaneGlyph] が算出する。
 *
 * @property centerX 軸の水平位置 (グリフ全体がセル中央に収まるよう左右補正済み)
 * @property baseY 軸の下端 (レーンの入口)
 * @property branchY 曲がりの枝が軸から分かれる高さ
 * @property branchLength 枝の長さ
 * @property headLength 矢じりの長さ
 * @property headHalfWidth 矢じり底辺の半幅
 * @property strokeWidth 線幅
 */
@Immutable
private data class LaneGlyphMetrics(
    val centerX: Float,
    val baseY: Float,
    val branchY: Float,
    val branchLength: Float,
    val headLength: Float,
    val headHalfWidth: Float,
    val strokeWidth: Float,
)

/**
 * レーングリフの配色。推奨車線 (`isActive`) では推奨方向だけ [active]、同レーンの他方向は [muted]、
 * 非推奨車線は [inactive] で塗る。
 *
 * @property active 推奨方向の色
 * @property muted 推奨車線内の非推奨方向の色
 * @property inactive 非推奨車線の色
 * @property isActive 推奨車線か
 * @property recommended 推奨方向。null ならレーン全体を [active] とみなす
 */
@Immutable
private data class LaneGlyphPaint(
    val active: Color,
    val muted: Color,
    val inactive: Color,
    val isActive: Boolean,
    val recommended: ManeuverModifier?,
)

/**
 * 指定方向の枝に使う色を返す。
 *
 * @param direction 対象の進行方向
 * @return 描画色
 */
private fun LaneGlyphPaint.colorFor(direction: ManeuverModifier): Color {
    if (!isActive) return inactive
    if (recommended == null) return active
    return if (direction == recommended) active else muted
}

/**
 * レーングリフを描画する。直進があれば全高の主矢印を描き、曲がりは低い位置から枝分かれさせる。
 * 直進が無い場合は軸を曲がりの分岐点まで伸ばす。U ターンは半円状の専用形状。
 *
 * @param directions このレーンの進行方向
 * @param color 線の色
 */
private fun DrawScope.drawLaneGlyph(
    directions: ImmutableList<ManeuverModifier>,
    recommendedDirection: ManeuverModifier?,
    isActive: Boolean,
    activeTint: Color,
    inactiveTint: Color,
) {
    if (!isActive && inactiveTint.alpha < 1f) {
        withLayerAlpha(inactiveTint.alpha) {
            drawLaneGlyphContent(
                directions = directions,
                recommendedDirection = recommendedDirection,
                isActive = false,
                activeTint = activeTint,
                inactiveTint = inactiveTint.copy(alpha = 1f),
            )
        }
        return
    }

    drawLaneGlyphContent(
        directions = directions,
        recommendedDirection = recommendedDirection,
        isActive = isActive,
        activeTint = activeTint,
        inactiveTint = inactiveTint,
    )
}

/**
 * レーングリフの実描画を行う。非アクティブ時の alpha 合成は呼び出し側でまとめて処理する。
 *
 * @param directions このレーンの進行方向
 * @param recommendedDirection 推奨方向。無ければ null
 * @param isActive 推奨車線か
 * @param activeTint 推奨方向の色
 * @param inactiveTint 非推奨車線の色
 */
private fun DrawScope.drawLaneGlyphContent(
    directions: ImmutableList<ManeuverModifier>,
    recommendedDirection: ManeuverModifier?,
    isActive: Boolean,
    activeTint: Color,
    inactiveTint: Color,
) {
    val hasStraight = directions.contains(ManeuverModifier.STRAIGHT)
    val hasUTurn = directions.contains(ManeuverModifier.UTURN)

    val branchLength = size.height * LaneBranchRatio
    val headHalfWidth = branchLength * LaneHeadHalfWidthRatio
    val branchYRatio = if (hasStraight || hasUTurn) LaneBranchYWithStraight else LaneBranchYTurnOnly
    val metrics = LaneGlyphMetrics(
        centerX = laneCenterX(directions, branchLength, headHalfWidth, hasStraight, hasUTurn),
        baseY = size.height * LaneBaseYRatio,
        branchY = size.height * branchYRatio,
        branchLength = branchLength,
        headLength = branchLength * LaneHeadLengthRatio,
        headHalfWidth = headHalfWidth,
        strokeWidth = laneStrokeWidth(directions.size),
    )

    val paint = LaneGlyphPaint(
        active = activeTint,
        muted = lerp(inactiveTint, activeTint, MutedTintFraction),
        inactive = inactiveTint,
        isActive = isActive,
        recommended = recommendedDirection,
    )
    val stemColor = if (isActive) activeTint else inactiveTint
    val topDirection = if (isActive) recommendedDirection else null

    directions.forEach { direction ->
        if (direction != topDirection) {
            drawLaneElement(direction = direction, metrics = metrics, color = paint.colorFor(direction))
        }
    }

    if (!hasUTurn) {
        drawLaneStem(metrics = metrics, color = stemColor)
    }

    if (topDirection != null && directions.contains(topDirection)) {
        drawLaneElement(direction = topDirection, metrics = metrics, color = paint.colorFor(topDirection))
    }
}

/**
 * ブロック全体に 1 回だけ alpha を掛けて描画する。
 *
 * 半透明色で複数パーツを個別に描くと、重なった部分だけ濃く見える。いったん不透明色でレイヤーへ描き、
 * レイヤー合成時に alpha を掛けることで、グリフ全体の透明度を一定に保つ。
 *
 * @param alpha レイヤーに掛ける透明度
 * @param block レイヤー内で実行する描画処理
 */
private inline fun DrawScope.withLayerAlpha(
    alpha: Float,
    block: DrawScope.() -> Unit,
) {
    val paint = Paint().apply {
        this.alpha = alpha
    }
    drawContext.canvas.saveLayer(Rect(offset = Offset.Zero, size = size), paint)
    try {
        block()
    } finally {
        drawContext.canvas.restore()
    }
}

/**
 * 1 方向の要素を種別に応じて描く。直進は上半分、U ターンは専用形状、それ以外は枝矢印。
 * 推奨方向を最後に描いて重なりの最前面に出すため、描画を 1 経路に集約する。
 *
 * @param direction 進行方向
 * @param metrics 描画寸法
 * @param color 線の色
 */
private fun DrawScope.drawLaneElement(
    direction: ManeuverModifier,
    metrics: LaneGlyphMetrics,
    color: Color,
) {
    when (direction) {
        ManeuverModifier.STRAIGHT -> drawStraightUpper(metrics = metrics, color = color)
        ManeuverModifier.UTURN -> drawUTurnBranch(metrics = metrics, color = color)
        else -> drawLaneTurnBranch(direction = direction, metrics = metrics, color = color)
    }
}

/**
 * グリフ全体がセル中央に収まる軸の水平位置を返す。
 *
 * 直進あり: 直進の矢じり幅と曲がりの先端を含む左右の包絡 (bbox) の中心をセル中央へ合わせる。
 * 曲がりのみ: bbox 補正を半分だけ掛ける (寄りすぎ防止)。U ターン: 左へ張り出す分だけ右へ寄せる。
 *
 * @param directions このレーンの進行方向
 * @param branchLength 枝の長さ
 * @param headHalfWidth 矢じり底辺の半幅
 * @param hasStraight 直進を含むか
 * @param hasUTurn U ターンを含むか
 * @return 軸の水平位置 (px)
 */
private fun DrawScope.laneCenterX(
    directions: ImmutableList<ManeuverModifier>,
    branchLength: Float,
    headHalfWidth: Float,
    hasStraight: Boolean,
    hasUTurn: Boolean,
): Float {
    val cellCenter = size.width / 2f
    if (hasUTurn) return cellCenter + branchLength * UTurnCenterShiftRatio

    val turnOffsets = directions.mapNotNull { direction -> direction.laneTipXOffset(branchLength) }
    val offsets = if (hasStraight) turnOffsets + listOf(-headHalfWidth, headHalfWidth) else turnOffsets + 0f
    val minOffset = offsets.minOrNull() ?: 0f
    val maxOffset = offsets.maxOrNull() ?: 0f
    val bboxCenter = (minOffset + maxOffset) / 2f
    val shift = if (hasStraight) bboxCenter else bboxCenter * TurnPartialShift
    return cellCenter - shift
}

/**
 * レーンの軸 (下端から分岐点まで) を描く。直進がある場合はこの上に [drawStraightUpper] が続き、
 * 曲がりはこの分岐点から枝分かれする。
 *
 * @param metrics 描画寸法
 * @param color 線の色
 */
private fun DrawScope.drawLaneStem(
    metrics: LaneGlyphMetrics,
    color: Color,
) {
    drawLine(
        color = color,
        start = Offset(metrics.centerX, metrics.baseY),
        end = Offset(metrics.centerX, metrics.branchY),
        strokeWidth = metrics.strokeWidth,
        cap = StrokeCap.Round,
    )
}

/**
 * 直進の上半分 (分岐点から上端の矢じりまで) を描く。下半分の軸は [drawLaneStem] が描く。
 *
 * @param metrics 描画寸法
 * @param color 線の色
 */
private fun DrawScope.drawStraightUpper(
    metrics: LaneGlyphMetrics,
    color: Color,
) {
    val topTip = size.height * LaneTopYRatio
    drawLine(
        color = color,
        start = Offset(metrics.centerX, metrics.branchY),
        end = Offset(metrics.centerX, topTip + metrics.headLength),
        strokeWidth = metrics.strokeWidth,
        cap = StrokeCap.Round,
    )
    drawArrowHead(tip = Offset(metrics.centerX, topTip), directionX = 0f, directionY = -1f, metrics = metrics, color = color)
}

/**
 * 分岐点から 1 方向の枝矢印を描く。直進・U ターンは別処理のため無視する。
 *
 * @param direction 枝の進行方向
 * @param metrics 描画寸法
 * @param color 線の色
 */
private fun DrawScope.drawLaneTurnBranch(
    direction: ManeuverModifier,
    metrics: LaneGlyphMetrics,
    color: Color,
) {
    val degrees = direction.laneTurnAngleDegrees() ?: return
    val radians = degrees * PI.toFloat() / HALF_CIRCLE_DEGREES
    val unitX = sin(radians)
    val unitY = -cos(radians)

    val fork = Offset(metrics.centerX, metrics.branchY)
    val tip = Offset(fork.x + unitX * metrics.branchLength, fork.y + unitY * metrics.branchLength)
    val shaftEnd = Offset(tip.x - unitX * metrics.headLength, tip.y - unitY * metrics.headLength)
    drawLine(
        color = color,
        start = fork,
        end = shaftEnd,
        strokeWidth = metrics.strokeWidth,
        cap = StrokeCap.Round,
    )
    drawArrowHead(tip = tip, directionX = unitX, directionY = unitY, metrics = metrics, color = color)
}

/**
 * U ターンの枝を描く。分岐点から上へ伸び、頂部で半円を描いて左へ折り返し、下向きの矢じりで終える。
 *
 * @param metrics 描画寸法
 * @param color 線の色
 */
private fun DrawScope.drawUTurnBranch(
    metrics: LaneGlyphMetrics,
    color: Color,
) {
    val rightX = metrics.centerX
    val leftX = metrics.centerX - metrics.branchLength * UTurnWidthRatio
    val topY = size.height * UTurnTopYRatio
    val archY = topY - size.height * UTurnArchRatio
    val tipY = metrics.branchY + size.height * UTurnTailRatio
    val shaftEndY = tipY - metrics.headLength

    val path = Path()
    path.moveTo(rightX, metrics.baseY)
    path.lineTo(rightX, topY)
    path.cubicTo(rightX, archY, leftX, archY, leftX, topY)
    path.lineTo(leftX, shaftEndY)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = metrics.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawArrowHead(tip = Offset(leftX, tipY), directionX = 0f, directionY = 1f, metrics = metrics, color = color)
}

/**
 * 塗りつぶしの三角矢じりを [tip] に描く。`direction*` は先端へ向かう単位ベクトル。
 *
 * 先端から根元方向へ [LaneGlyphMetrics.headLength] 戻った点を底辺の中心とし、左右へ
 * [LaneGlyphMetrics.headHalfWidth] 開いた 2 点を底辺とする三角形を塗る。
 *
 * @param tip 矢印の先端
 * @param directionX 進行方向の単位ベクトル X
 * @param directionY 進行方向の単位ベクトル Y
 * @param metrics 描画寸法
 * @param color 塗り色
 */
private fun DrawScope.drawArrowHead(
    tip: Offset,
    directionX: Float,
    directionY: Float,
    metrics: LaneGlyphMetrics,
    color: Color,
) {
    val baseCenterX = tip.x - directionX * metrics.headLength
    val baseCenterY = tip.y - directionY * metrics.headLength
    val perpX = -directionY
    val perpY = directionX

    val leftPoint = Offset(baseCenterX + perpX * metrics.headHalfWidth, baseCenterY + perpY * metrics.headHalfWidth)
    val rightPoint = Offset(baseCenterX - perpX * metrics.headHalfWidth, baseCenterY - perpY * metrics.headHalfWidth)

    val path = Path()
    path.moveTo(tip.x, tip.y)
    path.lineTo(leftPoint.x, leftPoint.y)
    path.lineTo(rightPoint.x, rightPoint.y)
    path.close()
    drawPath(path = path, color = color)
}

/**
 * 枝矢印の本数に応じた線幅を返す。本数が増えるほど細くして輻輳を避ける (下限あり)。
 *
 * @param branchCount このレーンの方向数
 * @return 描画に使う線幅 (px)
 */
private fun DrawScope.laneStrokeWidth(branchCount: Int): Float {
    val effectiveCount = branchCount.coerceAtLeast(1)
    val rawFactor = LaneStrokeBaseRatio - LaneStrokeStepRatio * (effectiveCount - 1)
    val factor = rawFactor.coerceAtLeast(LaneStrokeMinRatio)
    return size.minDimension * factor
}

/**
 * 枝の先端の軸からの水平オフセットを返す。直進・U ターンは軸上 / 別処理のため null。
 *
 * @param branchLength 枝の長さ
 * @return 水平オフセット (px)。直進・U ターンは null
 */
private fun ManeuverModifier.laneTipXOffset(branchLength: Float): Float? {
    val degrees = laneTurnAngleDegrees() ?: return null
    val radians = degrees * PI.toFloat() / HALF_CIRCLE_DEGREES
    return sin(radians) * branchLength
}

/**
 * 進行方向を「真上を 0 度・左を負・右を正」とした角度 (度) に変換する。直進・U ターンは枝を持たないため null。
 *
 * @return 真上基準の角度 (度)。直進・U ターンは null
 */
private fun ManeuverModifier.laneTurnAngleDegrees(): Float? = when (this) {
    ManeuverModifier.SLIGHT_LEFT -> -SlightAngleDegrees
    ManeuverModifier.SLIGHT_RIGHT -> SlightAngleDegrees
    ManeuverModifier.LEFT -> -TurnAngleDegrees
    ManeuverModifier.RIGHT -> TurnAngleDegrees
    ManeuverModifier.SHARP_LEFT -> -ThisSideAngleDegrees
    ManeuverModifier.SHARP_RIGHT -> ThisSideAngleDegrees
    ManeuverModifier.STRAIGHT -> null
    ManeuverModifier.UTURN -> null
}

private const val HALF_CIRCLE_DEGREES = 180f
private const val SlightAngleDegrees = 60f
private const val TurnAngleDegrees = 90f
private const val ThisSideAngleDegrees = 120f

private const val LaneBaseYRatio = 0.84f
private const val LaneTopYRatio = 0.16f
private const val LaneBranchYWithStraight = 0.60f
private const val LaneBranchYTurnOnly = 0.44f
private const val LaneBranchRatio = 0.44f
private const val LaneHeadLengthRatio = 0.5f
private const val LaneHeadHalfWidthRatio = 0.42f

private const val LaneStrokeBaseRatio = 0.12f
private const val LaneStrokeStepRatio = 0.018f
private const val LaneStrokeMinRatio = 0.085f

private const val TurnPartialShift = 0.5f
private const val MutedTintFraction = 0.5f

private const val UTurnCenterShiftRatio = 0.33f
private const val UTurnWidthRatio = 0.8f
private const val UTurnTopYRatio = 0.26f
private const val UTurnArchRatio = 0.16f
private const val UTurnTailRatio = 0.02f

/**
 * Preview 用に [LaneCell] を組み立てる。`isActive` の場合は先頭方向を推奨方向に充てる。
 *
 * @param isActive 推奨車線として強調するか
 * @param directions レーンの進行方向
 * @return プレビュー用レーンセル
 */
private fun previewLaneCell(
    isActive: Boolean,
    vararg directions: ManeuverModifier,
): LaneCell = LaneCell(
    allowedDirections = persistentListOf(*directions),
    recommendedDirection = if (isActive) directions.firstOrNull() else null,
    isActive = isActive,
)

/**
 * レーングリフの描画確認用 Preview。複数方向コンボ・単独方向・3 車線交差点シナリオを暗背景で並べる。
 */
@Suppress("ModifierMissing")
@Preview(name = "Lane glyphs", showBackground = true, widthDp = 420)
@Composable
private fun MapNavigationManeuverLaneIconsPreview() {
    val activeTint = Color.White
    val inactiveTint = Color(0xFF6F7280)
    val labelColor = Color(0xFFC7C9D2)

    MaterialTheme {
        Column(
            modifier = Modifier
                .background(Color(0xFF1D1E24))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "複数方向コンボ (全て推奨車線)",
                color = labelColor,
            )

            MapNavigationManeuverLaneIcons(
                lanes = persistentListOf(
                    previewLaneCell(true, ManeuverModifier.STRAIGHT, ManeuverModifier.LEFT),
                    previewLaneCell(true, ManeuverModifier.STRAIGHT, ManeuverModifier.RIGHT),
                    previewLaneCell(true, ManeuverModifier.LEFT, ManeuverModifier.STRAIGHT, ManeuverModifier.RIGHT),
                    previewLaneCell(true, ManeuverModifier.SLIGHT_LEFT, ManeuverModifier.STRAIGHT),
                    previewLaneCell(true, ManeuverModifier.UTURN),
                ),
                iconSize = 44.dp,
                spacing = 12.dp,
                activeTint = activeTint,
                inactiveTint = inactiveTint,
            )

            Text(
                text = "推奨方向の強調 (直進recommend / 左折recommend / 直進recommend / 右折recommend / 非推奨レーン)",
                color = labelColor,
            )

            MapNavigationManeuverLaneIcons(
                lanes = persistentListOf(
                    previewLaneCell(true, ManeuverModifier.STRAIGHT, ManeuverModifier.LEFT),
                    previewLaneCell(true, ManeuverModifier.LEFT, ManeuverModifier.STRAIGHT),
                    previewLaneCell(true, ManeuverModifier.STRAIGHT, ManeuverModifier.RIGHT),
                    previewLaneCell(true, ManeuverModifier.RIGHT, ManeuverModifier.STRAIGHT),
                    previewLaneCell(false, ManeuverModifier.STRAIGHT, ManeuverModifier.LEFT),
                ),
                iconSize = 44.dp,
                spacing = 12.dp,
                activeTint = activeTint,
                inactiveTint = inactiveTint,
            )

            Text(
                text = "単独方向 (左 / 右 / 手前左+直進)",
                color = labelColor,
            )

            MapNavigationManeuverLaneIcons(
                lanes = persistentListOf(
                    previewLaneCell(true, ManeuverModifier.LEFT),
                    previewLaneCell(true, ManeuverModifier.RIGHT),
                    previewLaneCell(true, ManeuverModifier.SHARP_LEFT, ManeuverModifier.STRAIGHT),
                ),
                iconSize = 44.dp,
                spacing = 12.dp,
                activeTint = activeTint,
                inactiveTint = inactiveTint,
            )

            Text(
                text = "3 車線交差点 (直進案内 / 左端は非推奨)",
                color = labelColor,
            )

            MapNavigationManeuverLaneIcons(
                lanes = persistentListOf(
                    previewLaneCell(false, ManeuverModifier.LEFT),
                    previewLaneCell(true, ManeuverModifier.STRAIGHT, ManeuverModifier.LEFT),
                    previewLaneCell(true, ManeuverModifier.STRAIGHT),
                ),
                iconSize = 44.dp,
                spacing = 12.dp,
                activeTint = activeTint,
                inactiveTint = inactiveTint,
            )
        }
    }
}
