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
        surfaceX: Float,
        surfaceY: Float,
        observedFrameX: Float?,
        observedFrameY: Float?,
    ): String? {
        val rootForTest = composeView.getChildAt(0) as? ViewRootForTest ?: return null
        val touchPoints = createTouchPointCandidates(
            surfaceX = surfaceX,
            surfaceY = surfaceY,
            observedFrameX = observedFrameX,
            observedFrameY = observedFrameY,
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
        surfaceX: Float,
        surfaceY: Float,
        observedFrameX: Float?,
        observedFrameY: Float?,
    ): List<Pair<String, Offset>> {
        val surfacePoint = Offset(
            x = surfaceX,
            y = surfaceY,
        )
        val observedFramePoint = createObservedFrameTouchPoint(
            observedFrameX = observedFrameX,
            observedFrameY = observedFrameY,
        )
        val surfaceCandidate = "surface" to surfacePoint

        if (observedFramePoint == null || observedFramePoint == surfacePoint) {
            return listOf(surfaceCandidate)
        }

        return listOf(
            "observed" to observedFramePoint,
            surfaceCandidate,
        )
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

    private fun SemanticsNode.isClickableTarget(touchPoint: Offset): Boolean {
        val hasClickAction = config.getOrNull(SemanticsActions.OnClick)?.action != null
        val isEnabled = !config.contains(SemanticsProperties.Disabled)
        val isInsideBounds = touchBoundsInRoot.contains(touchPoint)

        return !isRoot && hasClickAction && isEnabled && isInsideBounds
    }
}
