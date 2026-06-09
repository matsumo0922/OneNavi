package me.matsumo.onenavi.car.vd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull

/** split 表示とみなして visibleArea 横スケール候補を追加する最小 inset。 */
private const val SPLIT_VISIBLE_AREA_MIN_INSET_PX = 120

/** VD のクリックを Compose の clickable semantics へ直接渡す dispatcher。 */
class CarVirtualDisplayProbeSemanticsClickDispatcher {

    private val SemanticsNode.touchArea: Float
        get() {
            val bounds = touchBoundsInRoot
            return bounds.area
        }

    private val Rect.area: Float
        get() = width * height

    fun dispatchClick(
        composeView: ComposeView,
        viewport: CarVirtualDisplayProbeViewport,
        surfaceX: Float,
        surfaceY: Float,
        observedFrameX: Float?,
        observedFrameY: Float?,
        hostVisibleX: Float?,
        hostVisibleY: Float?,
    ): String? {
        val rootForTest = composeView.getChildAt(0) as? ViewRootForTest ?: return null
        val touchPoints = createTouchPointCandidates(
            viewport = viewport,
            surfaceX = surfaceX,
            surfaceY = surfaceY,
            observedFrameX = observedFrameX,
            observedFrameY = observedFrameY,
            hostVisibleX = hostVisibleX,
            hostVisibleY = hostVisibleY,
        )

        return touchPoints.firstNotNullOfOrNull { touchPoint ->
            dispatchClick(
                rootForTest = rootForTest,
                touchPoint = touchPoint,
            )
        }
    }

    private fun dispatchClick(
        rootForTest: ViewRootForTest,
        touchPoint: Pair<String, Offset>,
    ): String? {
        val targetNode = findClickableTargetNode(
            rootForTest = rootForTest,
            touchPoint = touchPoint.second,
        ) ?: return null

        val clickAction = targetNode.config.getOrNull(SemanticsActions.OnClick)
        val didHandleClick = clickAction?.action?.let { action ->
            runCatching { action() }.getOrDefault(false)
        } == true

        return if (didHandleClick) touchPoint.first else null
    }

    private fun findClickableTargetNode(
        rootForTest: ViewRootForTest,
        touchPoint: Offset,
    ): SemanticsNode? {
        return findClickableTargetNode(
            rootForTest = rootForTest,
            touchPoint = touchPoint,
            mergingEnabled = true,
        ) ?: findClickableTargetNode(
            rootForTest = rootForTest,
            touchPoint = touchPoint,
            mergingEnabled = false,
        )
    }

    private fun findClickableTargetNode(
        rootForTest: ViewRootForTest,
        touchPoint: Offset,
        mergingEnabled: Boolean,
    ): SemanticsNode? {
        return rootForTest.semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = mergingEnabled)
            .asSequence()
            .filter { semanticsNode ->
                semanticsNode.isClickableTarget(touchPoint = touchPoint)
            }
            .minWithOrNull(
                comparator = compareBy { semanticsNode ->
                    semanticsNode.touchArea
                },
            )
    }

    private fun createTouchPointCandidates(
        viewport: CarVirtualDisplayProbeViewport,
        surfaceX: Float,
        surfaceY: Float,
        observedFrameX: Float?,
        observedFrameY: Float?,
        hostVisibleX: Float?,
        hostVisibleY: Float?,
    ): List<Pair<String, Offset>> {
        val surfacePoint = Offset(
            x = surfaceX,
            y = surfaceY,
        )
        val observedFramePoint = createObservedFrameTouchPoint(
            observedFrameX = observedFrameX,
            observedFrameY = observedFrameY,
        )
        val hostVisiblePoint = createHostVisibleTouchPoint(
            hostVisibleX = hostVisibleX,
            hostVisibleY = hostVisibleY,
        )
        val visibleOffsetPoint = createVisibleOffsetTouchPoint(
            viewport = viewport,
            surfaceX = surfaceX,
            surfaceY = surfaceY,
        )
        val visibleScaledPoint = createVisibleScaledTouchPoint(
            viewport = viewport,
            surfaceX = surfaceX,
            surfaceY = surfaceY,
        )
        val candidatePoints = mutableListOf<Pair<String, Offset>>()

        candidatePoints.addUniqueTouchPointCandidate(
            label = "visibleOffset",
            touchPoint = visibleOffsetPoint,
        )
        candidatePoints.addUniqueTouchPointCandidate(
            label = "surface",
            touchPoint = surfacePoint,
        )
        candidatePoints.addUniqueTouchPointCandidate(
            label = "observed",
            touchPoint = observedFramePoint,
        )
        candidatePoints.addUniqueTouchPointCandidate(
            label = "hostVisible",
            touchPoint = hostVisiblePoint,
        )
        candidatePoints.addUniqueTouchPointCandidate(
            label = "visibleScaled",
            touchPoint = visibleScaledPoint,
        )

        return candidatePoints
    }

    private fun createObservedFrameTouchPoint(
        observedFrameX: Float?,
        observedFrameY: Float?,
    ): Offset? {
        if (observedFrameX == null || observedFrameY == null) {
            return null
        }

        return Offset(
            x = observedFrameX,
            y = observedFrameY,
        )
    }

    private fun createHostVisibleTouchPoint(
        hostVisibleX: Float?,
        hostVisibleY: Float?,
    ): Offset? {
        if (hostVisibleX == null || hostVisibleY == null) {
            return null
        }

        return Offset(
            x = hostVisibleX,
            y = hostVisibleY,
        )
    }

    private fun createVisibleOffsetTouchPoint(
        viewport: CarVirtualDisplayProbeViewport,
        surfaceX: Float,
        surfaceY: Float,
    ): Offset? {
        if (!viewport.hasHorizontalSplitVisibleArea()) {
            return null
        }

        val offsetSurfaceX = (viewport.visibleLeft + surfaceX).coerceIn(
            minimumValue = 0f,
            maximumValue = viewport.surfaceWidth.toFloat(),
        )

        return Offset(
            x = offsetSurfaceX,
            y = surfaceY,
        )
    }

    private fun createVisibleScaledTouchPoint(
        viewport: CarVirtualDisplayProbeViewport,
        surfaceX: Float,
        surfaceY: Float,
    ): Offset? {
        if (!viewport.hasHorizontalSplitVisibleArea()) {
            return null
        }

        val scaledSurfaceX = viewport.visibleLeft + surfaceX * viewport.visibleWidth / viewport.surfaceWidth

        return Offset(
            x = scaledSurfaceX,
            y = surfaceY,
        )
    }

    private fun MutableList<Pair<String, Offset>>.addUniqueTouchPointCandidate(
        label: String,
        touchPoint: Offset?,
    ) {
        if (touchPoint == null) {
            return
        }

        val isDuplicate = any { candidate ->
            candidate.second == touchPoint
        }

        if (isDuplicate) {
            return
        }

        add(label to touchPoint)
    }

    private fun SemanticsNode.isClickableTarget(touchPoint: Offset): Boolean {
        val hasClickAction = config.getOrNull(SemanticsActions.OnClick)?.action != null
        val isEnabled = !config.contains(SemanticsProperties.Disabled)
        val isInsideBounds = touchBoundsInRoot.contains(touchPoint)

        return !isRoot && hasClickAction && isEnabled && isInsideBounds
    }
}

private fun CarVirtualDisplayProbeViewport.hasHorizontalSplitVisibleArea(): Boolean {
    val leftInset = visibleLeft
    val rightInset = surfaceWidth - visibleRight
    val maxHorizontalInset = maxOf(leftInset, rightInset)

    return surfaceWidth > 0 && visibleWidth > 0 && maxHorizontalInset >= SPLIT_VISIBLE_AREA_MIN_INSET_PX
}
