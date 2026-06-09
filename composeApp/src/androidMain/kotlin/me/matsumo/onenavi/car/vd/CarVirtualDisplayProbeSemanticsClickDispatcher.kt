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
    ): Boolean {
        val rootForTest = composeView.getChildAt(0) as? ViewRootForTest ?: return false
        val touchPoint = Offset(
            x = surfaceX,
            y = surfaceY,
        )
        val targetNode = rootForTest.semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = true)
            .asSequence()
            .filter { semanticsNode ->
                semanticsNode.isClickableTarget(touchPoint = touchPoint)
            }
            .minWithOrNull(
                comparator = compareBy { semanticsNode ->
                    semanticsNode.touchArea
                },
            )
            ?: return false

        val clickAction = targetNode.config.getOrNull(SemanticsActions.OnClick)
        return clickAction?.action?.let { action ->
            runCatching { action() }.getOrDefault(false)
        } == true
    }

    private fun SemanticsNode.isClickableTarget(touchPoint: Offset): Boolean {
        val hasClickAction = config.getOrNull(SemanticsActions.OnClick)?.action != null
        val isEnabled = !config.contains(SemanticsProperties.Disabled)
        val isInsideBounds = touchBoundsInRoot.contains(touchPoint)

        return !isRoot && hasClickAction && isEnabled && isInsideBounds
    }
}
