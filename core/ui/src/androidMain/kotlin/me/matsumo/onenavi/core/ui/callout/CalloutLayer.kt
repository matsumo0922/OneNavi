package me.matsumo.onenavi.core.ui.callout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

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
 * 動作モードは [isContinuousTracking] により 2 通りある:
 * - false（既定・ルートプレビュー向け）: カメラ静止時点の [anchors] をスナップショット化し、
 *   次の静止まで前回配置を保持する。カメラ移動中はフェードアウトする。
 * - true（ナビゲーション自動追従向け）: [anchors] の変化を毎フレーム取り込んで配置を
 *   追従させる。自動追従で [isCameraMoving] が常時 true になる状況でも表示を維持する。
 *
 * @param anchors 配置対象。スナップショットモードではカメラ静止時点の値だけが採用される
 * @param placementStrategy 配置戦略
 * @param isCameraMoving 地図が動いている間は true。スナップショットモードでは true の間
 *   Callout をフェードアウトし再計算も停止する。ライブ追従モードでは、ユーザージェスチャー等で
 *   真に隠したいタイミングだけ true を渡すことで同等のフェード挙動になる
 * @param cameraSettleEpoch カメラが静止した通算回数。スナップショットモードでのみ使用し、
 *   マウント時点の値から変化するまではレイヤーごと非表示にする。ライブ追従モードでは無視される
 * @param modifier 外部から渡される Modifier。`fillMaxSize` 相当を期待する
 * @param isContinuousTracking ライブ追従モードを有効にするかどうか
 * @param content (index, tailDirection) を受け取って [Callout] を返すスロット
 */
@Composable
fun CalloutLayer(
    anchors: ImmutableList<CalloutAnchor>,
    placementStrategy: CalloutPlacementStrategy,
    isCameraMoving: Boolean,
    cameraSettleEpoch: Int,
    modifier: Modifier = Modifier,
    isContinuousTracking: Boolean = false,
    content: @Composable (index: Int, tailDirection: CalloutTailDirection) -> Unit,
) {
    if (isContinuousTracking) {
        ContinuousCalloutLayer(
            anchors = anchors,
            placementStrategy = placementStrategy,
            isCameraMoving = isCameraMoving,
            modifier = modifier,
            content = content,
        )
    } else {
        SnapshotCalloutLayer(
            anchors = anchors,
            placementStrategy = placementStrategy,
            isCameraMoving = isCameraMoving,
            cameraSettleEpoch = cameraSettleEpoch,
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
private fun ContinuousCalloutLayer(
    anchors: ImmutableList<CalloutAnchor>,
    placementStrategy: CalloutPlacementStrategy,
    isCameraMoving: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, tailDirection: CalloutTailDirection) -> Unit,
) {
    AnimatedVisibility(
        visible = !isCameraMoving,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        SubcomposeCalloutLayout(
            anchors = anchors,
            placementStrategy = placementStrategy,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

@Composable
private fun SnapshotCalloutLayer(
    anchors: ImmutableList<CalloutAnchor>,
    placementStrategy: CalloutPlacementStrategy,
    isCameraMoving: Boolean,
    cameraSettleEpoch: Int,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, tailDirection: CalloutTailDirection) -> Unit,
) {
    val initialSettleEpoch = remember { cameraSettleEpoch }
    val hasSettledSinceMount = cameraSettleEpoch != initialSettleEpoch
    val snapshotAnchors = remember(cameraSettleEpoch) { anchors }

    AnimatedVisibility(
        visible = !isCameraMoving && hasSettledSinceMount,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        key(cameraSettleEpoch) {
            Crossfade(
                targetState = snapshotAnchors,
                animationSpec = tween(durationMillis = CALLOUT_CROSSFADE_DURATION_MS),
                label = "callout-layer-snapshot",
            ) { snapshot ->
                SubcomposeCalloutLayout(
                    anchors = snapshot,
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
