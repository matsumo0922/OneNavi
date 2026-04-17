package me.matsumo.onenavi.core.ui.callout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

/**
 * 配置計算ユーティリティ。外部依存を持たない純粋関数として構成する。
 */
internal object CalloutPlacement {

    /**
     * 1 つの Callout に対する配置結果。
     *
     * @param anchorId 元の [CalloutAnchor.id]
     * @param topLeft CalloutLayer ローカル座標系での左上位置（px）
     * @param tailDirection 採用された tail 方向
     * @param size Pass 1 で測定した Callout のサイズ
     */
    @Immutable
    data class Result(
        val anchorId: Any,
        val topLeft: Offset,
        val tailDirection: CalloutTailDirection,
        val size: IntSize,
    )

    /**
     * 複数 anchor に対する配置を決定する。
     *
     * @param anchors 配置対象の anchor 群
     * @param sizes anchor と同じ順序の Callout サイズ
     * @param screenSize CalloutLayer 自身のサイズ（画面内判定に使う）
     * @param strategy 配置戦略
     */
    fun compute(
        anchors: List<CalloutAnchor>,
        sizes: List<IntSize>,
        screenSize: IntSize,
        strategy: CalloutPlacementStrategy,
    ): List<Result> {
        require(anchors.size == sizes.size) {
            "anchors (${anchors.size}) and sizes (${sizes.size}) must have the same length"
        }

        val placedRects = mutableListOf<Rect>()
        return anchors.mapIndexed { anchorIndex, anchor ->
            val size = sizes[anchorIndex]
            val candidates = buildCandidates(anchor, strategy)

            val chosen = selectBestCandidate(
                candidates = candidates,
                size = size,
                screenSize = screenSize,
                placedRects = placedRects,
                strategy = strategy,
            )

            val topLeft = tipToTopLeft(chosen.tip, chosen.direction, size)
            placedRects += calloutRect(topLeft, size)

            Result(
                anchorId = anchor.id,
                topLeft = topLeft,
                tailDirection = chosen.direction,
                size = size,
            )
        }
    }

    private fun buildCandidates(
        anchor: CalloutAnchor,
        strategy: CalloutPlacementStrategy,
    ): List<Candidate> {
        val tips = when (anchor) {
            is CalloutAnchor.Fixed -> listOf(anchor.primaryPoint)
            is CalloutAnchor.Flexible -> when (strategy) {
                CalloutPlacementStrategy.AnchorFirst -> listOf(anchor.primaryPoint)
                CalloutPlacementStrategy.AvoidOverlap ->
                    (anchor.candidates + anchor.primaryPoint).distinct()
            }
        }
        return tips.flatMap { tip ->
            CalloutTailDirection.entries.map { direction ->
                Candidate(tip = tip, direction = direction)
            }
        }
    }

    private fun selectBestCandidate(
        candidates: List<Candidate>,
        size: IntSize,
        screenSize: IntSize,
        placedRects: List<Rect>,
        strategy: CalloutPlacementStrategy,
    ): Candidate {
        if (candidates.isEmpty()) {
            error("buildCandidates must return at least one candidate")
        }

        return when (strategy) {
            CalloutPlacementStrategy.AnchorFirst -> {
                candidates.firstOrNull { candidate ->
                    val rect = calloutRect(
                        topLeft = tipToTopLeft(candidate.tip, candidate.direction, size),
                        size = size,
                    )
                    fitsInScreen(rect, screenSize)
                } ?: candidates.first()
            }
            CalloutPlacementStrategy.AvoidOverlap -> {
                val inScreenAndNoOverlap = candidates.firstOrNull { candidate ->
                    val rect = calloutRect(
                        topLeft = tipToTopLeft(candidate.tip, candidate.direction, size),
                        size = size,
                    )
                    fitsInScreen(rect, screenSize) && !overlapsAny(rect, placedRects)
                }
                if (inScreenAndNoOverlap != null) return inScreenAndNoOverlap

                val noOverlap = candidates.firstOrNull { candidate ->
                    val rect = calloutRect(
                        topLeft = tipToTopLeft(candidate.tip, candidate.direction, size),
                        size = size,
                    )
                    !overlapsAny(rect, placedRects)
                }
                if (noOverlap != null) return noOverlap

                val inScreen = candidates.firstOrNull { candidate ->
                    val rect = calloutRect(
                        topLeft = tipToTopLeft(candidate.tip, candidate.direction, size),
                        size = size,
                    )
                    fitsInScreen(rect, screenSize)
                }
                inScreen ?: candidates.first()
            }
        }
    }

    private fun tipToTopLeft(
        tip: Offset,
        direction: CalloutTailDirection,
        size: IntSize,
    ): Offset = when (direction) {
        CalloutTailDirection.TopLeft -> tip
        CalloutTailDirection.TopRight -> Offset(tip.x - size.width, tip.y)
        CalloutTailDirection.BottomLeft -> Offset(tip.x, tip.y - size.height)
        CalloutTailDirection.BottomRight -> Offset(tip.x - size.width, tip.y - size.height)
    }

    private fun calloutRect(topLeft: Offset, size: IntSize): Rect {
        return Rect(topLeft, Size(size.width.toFloat(), size.height.toFloat()))
    }

    private fun fitsInScreen(rect: Rect, screenSize: IntSize): Boolean {
        if (screenSize.width <= 0 || screenSize.height <= 0) return true
        return rect.left >= 0f &&
            rect.top >= 0f &&
            rect.right <= screenSize.width.toFloat() &&
            rect.bottom <= screenSize.height.toFloat()
    }

    private fun overlapsAny(rect: Rect, others: List<Rect>): Boolean {
        return others.any { it.overlaps(rect) }
    }

    @Immutable
    private data class Candidate(
        val tip: Offset,
        val direction: CalloutTailDirection,
    )
}
