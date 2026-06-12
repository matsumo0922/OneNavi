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
internal class CarVirtualDisplayProbeSemanticsClickDispatcher {

    private val SemanticsNode.touchArea: Float
        get() {
            val bounds = touchBoundsInRoot
            return bounds.area
        }

    private val Rect.area: Float
        get() = width * height

    fun dispatchClick(
        composeView: ComposeView,
        touchPoint: CarVirtualDisplayProbeClickCoordinateCandidate,
        inputState: CarVirtualDisplayProbeInputState,
        inputLatencyLogger: CarVirtualDisplayProbeInputLatencyLogger,
    ): CarVirtualDisplayProbeClickCoordinateResult? {
        val rootForTest = composeView.getChildAt(0) as? ViewRootForTest ?: return null

        return dispatchClick(
            rootForTest = rootForTest,
            touchPoint = touchPoint,
            inputState = inputState,
            inputLatencyLogger = inputLatencyLogger,
        )
    }

    private fun dispatchClick(
        rootForTest: ViewRootForTest,
        touchPoint: CarVirtualDisplayProbeClickCoordinateCandidate,
        inputState: CarVirtualDisplayProbeInputState,
        inputLatencyLogger: CarVirtualDisplayProbeInputLatencyLogger,
    ): CarVirtualDisplayProbeClickCoordinateResult? {
        val targetNode = findClickableTargetNode(
            rootForTest = rootForTest,
            touchPoint = touchPoint.point,
            inputState = inputState,
            inputLatencyLogger = inputLatencyLogger,
        ) ?: return null

        val clickAction = targetNode.config.getOrNull(SemanticsActions.OnClick)
        val actionStartedAtMillis = inputLatencyLogger.now()
        val didHandleClick = clickAction?.action?.let { action ->
            runCatching { action() }.getOrDefault(false)
        } == true
        inputLatencyLogger.logSemanticsAction(
            inputState = inputState,
            elapsedMillis = inputLatencyLogger.elapsedSince(actionStartedAtMillis),
            didHandleClick = didHandleClick,
        )

        if (!didHandleClick) {
            return null
        }

        return CarVirtualDisplayProbeClickCoordinateResult(touchPoint.label, touchPoint.point)
    }

    private fun findClickableTargetNode(
        rootForTest: ViewRootForTest,
        touchPoint: Offset,
        inputState: CarVirtualDisplayProbeInputState,
        inputLatencyLogger: CarVirtualDisplayProbeInputLatencyLogger,
    ): SemanticsNode? {
        return findClickableTargetNode(
            rootForTest = rootForTest,
            touchPoint = touchPoint,
            mergingEnabled = true,
            inputState = inputState,
            inputLatencyLogger = inputLatencyLogger,
        ) ?: findClickableTargetNode(
            rootForTest = rootForTest,
            touchPoint = touchPoint,
            mergingEnabled = false,
            inputState = inputState,
            inputLatencyLogger = inputLatencyLogger,
        )
    }

    private fun findClickableTargetNode(
        rootForTest: ViewRootForTest,
        touchPoint: Offset,
        mergingEnabled: Boolean,
        inputState: CarVirtualDisplayProbeInputState,
        inputLatencyLogger: CarVirtualDisplayProbeInputLatencyLogger,
    ): SemanticsNode? {
        val scanStartedAtMillis = inputLatencyLogger.now()
        val semanticsNodes = rootForTest.semanticsOwner.getAllSemanticsNodes(mergingEnabled = mergingEnabled)
        val targetNode = semanticsNodes
            .asSequence()
            .filter { semanticsNode ->
                semanticsNode.isClickableTarget(touchPoint)
            }
            .minWithOrNull(
                comparator = compareBy { semanticsNode ->
                    semanticsNode.touchArea
                },
            )
        inputLatencyLogger.logSemanticsScan(
            inputState = inputState,
            mergingEnabled = mergingEnabled,
            nodeCount = semanticsNodes.size,
            elapsedMillis = inputLatencyLogger.elapsedSince(scanStartedAtMillis),
            didFindTarget = targetNode != null,
        )

        return targetNode
    }

    private fun SemanticsNode.isClickableTarget(touchPoint: Offset): Boolean {
        val hasClickAction = config.getOrNull(SemanticsActions.OnClick)?.action != null
        val isEnabled = !config.contains(SemanticsProperties.Disabled)
        val isInsideBounds = touchBoundsInRoot.contains(touchPoint)

        return !isRoot && hasClickAction && isEnabled && isInsideBounds
    }
}
