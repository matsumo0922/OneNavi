package me.matsumo.onenavi.core.ui.callout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 自動追従中（自車移動による地図移動）に Callout を再配置する間隔。
 *
 * 3 秒の間は採用済み配置を保持し、インターバル経過ごとに最新の anchor を読み直して
 * 再計算する。ジェスチャー中は再計算せず Callout 自体をフェードアウトする。
 */
const val CALLOUT_RELAYOUT_INTERVAL_MS: Long = 3_000L

/**
 * スナップショット切り替え（配置再計算）時のクロスフェード所要時間。
 */
private const val CALLOUT_CROSSFADE_DURATION_MS: Int = 220

/**
 * 複数 Callout を画面上に絶対配置するレイヤー。
 *
 * 内部では [SubcomposeLayout] による 2 パス測定を行う:
 * - Pass 1: 仮の tail 方向で [content] を subcompose してサイズを測定
 * - Pass 2: [CalloutPlacement.compute] で配置を決定
 * - Pass 3: 確定 tail 方向で再 subcompose し、計算位置に place
 *
 * [CalloutShape] の「全方向に tail 分の padding を確保」設計により、tail 方向が
 * 変わっても Callout のサイズは変わらない前提。Pass 1 のサイズがそのまま Pass 3 でも有効になる。
 *
 * @param anchors 配置対象。カメラ静止後と、静止中は [CALLOUT_RELAYOUT_INTERVAL_MS] ごとに読み直される
 * @param placementStrategy 配置戦略
 * @param isCameraMoving 地図が動いている間は true。ユーザージェスチャー/プログラム由来問わず、
 *   true の間は Callout をフェードアウトし再計算も停止する
 * @param cameraSettleEpoch カメラが静止した通算回数。マウント時点の値から変化するまでは
 *   レイヤーごと非表示にし、変化したタイミングでフェードインする。以降は値の変化ごとに
 *   内部状態をリセットして再計算後の配置でフレッシュに描画する
 * @param modifier 外部から渡される Modifier。`fillMaxSize` 相当を期待する
 * @param content (index, tailDirection) を受け取って [Callout] を返すスロット
 */
@Composable
fun CalloutLayer(
    anchors: ImmutableList<CalloutAnchor>,
    placementStrategy: CalloutPlacementStrategy,
    isCameraMoving: Boolean,
    cameraSettleEpoch: Int,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, tailDirection: CalloutTailDirection) -> Unit,
) {
    val currentAnchors by rememberUpdatedState(anchors)
    var lockedAnchors by remember { mutableStateOf(anchors) }

    val anchorIds = anchors.map { it.id }
    LaunchedEffect(anchorIds) {
        lockedAnchors = currentAnchors
    }

    LaunchedEffect(isCameraMoving, cameraSettleEpoch) {
        if (!isCameraMoving) {
            lockedAnchors = currentAnchors
            while (true) {
                delay(CALLOUT_RELAYOUT_INTERVAL_MS)
                lockedAnchors = currentAnchors
            }
        }
    }

    val initialSettleEpoch = remember { cameraSettleEpoch }
    val hasSettledSinceMount = cameraSettleEpoch != initialSettleEpoch

    AnimatedVisibility(
        visible = !isCameraMoving && hasSettledSinceMount,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        key(cameraSettleEpoch) {
            Crossfade(
                targetState = lockedAnchors,
                animationSpec = tween(durationMillis = CALLOUT_CROSSFADE_DURATION_MS),
                label = "callout-layer-snapshot",
            ) { snapshot ->
                SubcomposeCalloutLayout(
                    anchors = snapshot.toImmutableList(),
                    placementStrategy = placementStrategy,
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SubcomposeCalloutLayout(
    anchors: ImmutableList<CalloutAnchor>,
    placementStrategy: CalloutPlacementStrategy,
    content: @Composable (index: Int, tailDirection: CalloutTailDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier) { constraints ->
        val layerSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val measureConstraints = Constraints(
            minWidth = 0,
            minHeight = 0,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity,
        )

        val sizes = anchors.indices.map { anchorIndex ->
            val measurables = subcompose(SlotKey.Measure(anchorIndex)) {
                content(anchorIndex, TENTATIVE_TAIL_DIRECTION)
            }
            val firstMeasurable = measurables.firstOrNull()
            if (firstMeasurable == null) {
                IntSize.Zero
            } else {
                val placeable = firstMeasurable.measure(measureConstraints)
                IntSize(placeable.width, placeable.height)
            }
        }

        val placements = CalloutPlacement.compute(
            anchors = anchors,
            sizes = sizes,
            screenSize = layerSize,
            strategy = placementStrategy,
        )

        val finalPlaceables = placements.mapIndexed { anchorIndex, placement ->
            val measurable = subcompose(SlotKey.Place(anchorIndex)) {
                content(anchorIndex, placement.tailDirection)
            }.firstOrNull()
            measurable?.measure(
                Constraints.fixed(
                    width = placement.size.width.coerceAtLeast(0),
                    height = placement.size.height.coerceAtLeast(0),
                ),
            )
        }

        layout(layerSize.width, layerSize.height) {
            finalPlaceables.forEachIndexed { anchorIndex, placeable ->
                if (placeable != null) {
                    val placement = placements[anchorIndex]
                    placeable.place(
                        x = placement.topLeft.x.roundToInt(),
                        y = placement.topLeft.y.roundToInt(),
                    )
                }
            }
        }
    }
}

private val TENTATIVE_TAIL_DIRECTION = CalloutTailDirection.TopLeft

/**
 * [SubcomposeLayout] スロット識別子。同一インデックスで measure 用と place 用を分離する。
 */
private sealed interface SlotKey {
    data class Measure(val index: Int) : SlotKey
    data class Place(val index: Int) : SlotKey
}
