package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * CallOut の tail を出す側。
 *
 * Google Maps アプリの観察に合わせ、初期実装では下側左右だけを正式対応にする。
 */
internal enum class MapCallOutTailSide {
    BottomLeft,
    BottomRight,
}

/**
 * CallOut が指し示す対象。
 */
@Immutable
internal sealed interface MapCallOutTarget {

    /**
     * tail 先端を polyline 上で移動できるタイプ。
     *
     * @param points route polyline を構成する地理座標
     */
    @Immutable
    data class PolylineMovable(
        val points: ImmutableList<RoutePoint>,
    ) : MapCallOutTarget

    /**
     * tail 先端を指定地点に固定するタイプ。
     *
     * @param point tail 先端が必ず一致する地理座標
     */
    @Immutable
    data class PointFixed(
        val point: RoutePoint,
    ) : MapCallOutTarget
}

/**
 * 前回採用された CallOut 配置。
 *
 * @param position tail 先端の地理座標
 * @param tailSide 前回採用された tail の左右
 */
@Immutable
internal data class MapCallOutPreviousPlacement(
    val position: RoutePoint,
    val tailSide: MapCallOutTailSide,
)

/**
 * 1 つの CallOut 配置要求。
 *
 * @param id 安定した識別子
 * @param target tail 先端の自由度を表す対象
 * @param priority 配置優先度。大きいほど先に配置される
 * @param zIndexPriority 描画優先度。null の場合は [priority] を使う
 * @param contentKey slot content を bitmap 化し直すための描画キー
 * @param allowsOffscreenPlacement 画面外に projected された候補も marker として配置する場合 true
 * @param supportedTailSides 許可する tail 側。空の場合は下側左右を使う
 * @param previousPlacement 前回配置。ちらつき抑制の reward に使う
 */
@Immutable
internal data class MapCallOutRequest(
    val id: String,
    val target: MapCallOutTarget,
    val priority: Int = 0,
    val zIndexPriority: Int? = null,
    val contentKey: String? = null,
    val allowsOffscreenPlacement: Boolean = false,
    val supportedTailSides: ImmutableList<MapCallOutTailSide> = DEFAULT_MAP_CALLOUT_TAIL_SIDES,
    val previousPlacement: MapCallOutPreviousPlacement? = null,
)

/**
 * 配置 engine が採用した CallOut の結果。
 *
 * @param requestIndex 入力 requests における index
 * @param requestId 入力 request の id
 * @param position tail 先端の地理座標
 * @param tip tail 先端の screen 座標
 * @param tailSide 採用された tail 側
 * @param topLeft CallOut 全体 bounds の左上
 * @param size CallOut 全体 bounds のサイズ
 * @param bodyBounds 角丸矩形 body の bounds
 * @param score 採用候補の score
 */
@Immutable
internal data class MapCallOutPlacement(
    val requestIndex: Int,
    val requestId: String,
    val position: RoutePoint,
    val tip: Offset,
    val tailSide: MapCallOutTailSide,
    val topLeft: Offset,
    val size: IntSize,
    val bodyBounds: Rect,
    val score: Float,
)

internal val DEFAULT_MAP_CALLOUT_TAIL_SIDES: ImmutableList<MapCallOutTailSide> = persistentListOf(
    MapCallOutTailSide.BottomRight,
    MapCallOutTailSide.BottomLeft,
)
