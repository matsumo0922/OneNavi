package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapCallOutPlacementEngineTest {

    @Test
    fun pointFixedKeepsTipAtFixedPoint() {
        val fixedPoint = Offset(200f, 220f)
        val routePoint = fixedPoint.toRoutePoint()
        val placements = MapCallOutPlacementEngine.place(
            requests = listOf(
                MapCallOutRequest(
                    id = "fixed",
                    target = MapCallOutTarget.PointFixed(routePoint),
                ),
            ),
            sizes = listOf(IntSize(96, 48)),
            viewportSize = ViewportSize,
            viewport = Viewport,
            tailLengthPx = TailLengthPx,
            project = ::projectForTest,
        )

        assertEquals(1, placements.size)
        assertEquals(fixedPoint, placements.single().tip)
        assertEquals(routePoint, placements.single().position)
    }

    @Test
    fun polylineMovableChoosesTipOnPolyline() {
        val placements = MapCallOutPlacementEngine.place(
            requests = listOf(
                MapCallOutRequest(
                    id = "route",
                    target = MapCallOutTarget.PolylineMovable(
                        points = persistentListOf(
                            Offset(40f, 260f).toRoutePoint(),
                            Offset(360f, 260f).toRoutePoint(),
                        ),
                    ),
                ),
            ),
            sizes = listOf(IntSize(96, 48)),
            viewportSize = ViewportSize,
            viewport = Viewport,
            tailLengthPx = TailLengthPx,
            project = ::projectForTest,
        )

        val tip = placements.single().tip
        assertEquals(260f, tip.y)
        assertTrue(tip.x in 40f..360f)
    }

    @Test
    fun polylineMovableUsesVisibleRouteSectionWhenWholeRouteIsLong() {
        val placements = MapCallOutPlacementEngine.place(
            requests = listOf(
                MapCallOutRequest(
                    id = "route",
                    target = MapCallOutTarget.PolylineMovable(
                        points = persistentListOf(
                            Offset(-5_000f, 260f).toRoutePoint(),
                            Offset(5_000f, 260f).toRoutePoint(),
                        ),
                    ),
                    previousPlacement = MapCallOutPreviousPlacement(
                        position = Offset(-2_000f, 260f).toRoutePoint(),
                        tailSide = MapCallOutTailSide.BottomRight,
                    ),
                ),
            ),
            sizes = listOf(IntSize(96, 48)),
            viewportSize = ViewportSize,
            viewport = Viewport,
            tailLengthPx = TailLengthPx,
            project = ::projectForTest,
        )

        assertEquals(1, placements.size)
        assertTrue(placements.single().tip.x in Viewport.left..Viewport.right)
    }

    @Test
    fun polylineMovableDispersesMultipleCallOutsOnVisibleRoute() {
        val route = persistentListOf(
            Offset(20f, 260f).toRoutePoint(),
            Offset(380f, 260f).toRoutePoint(),
        )
        val placements = MapCallOutPlacementEngine.place(
            requests = listOf(
                MapCallOutRequest(
                    id = "route-0",
                    target = MapCallOutTarget.PolylineMovable(route),
                    priority = 100,
                ),
                MapCallOutRequest(
                    id = "route-1",
                    target = MapCallOutTarget.PolylineMovable(route),
                    priority = 10,
                ),
                MapCallOutRequest(
                    id = "route-2",
                    target = MapCallOutTarget.PolylineMovable(route),
                ),
            ),
            sizes = listOf(
                IntSize(96, 48),
                IntSize(96, 48),
                IntSize(96, 48),
            ),
            viewportSize = ViewportSize,
            viewport = Viewport,
            tailLengthPx = TailLengthPx,
            project = ::projectForTest,
        )

        val tipXs = placements.map { it.tip.x }.sorted()

        assertEquals(3, placements.size)
        assertTrue(
            actual = tipXs.last() - tipXs.first() >= 200f,
            message = "tipXs=$tipXs",
        )
    }

    @Test
    fun polylineMovableDispersesMultipleCallOutsOnVisibleMiddleRouteSection() {
        val route = persistentListOf(
            Offset(-5_000f, 260f).toRoutePoint(),
            Offset(5_000f, 260f).toRoutePoint(),
        )
        val placements = MapCallOutPlacementEngine.place(
            requests = listOf(
                MapCallOutRequest(
                    id = "route-0",
                    target = MapCallOutTarget.PolylineMovable(route),
                    priority = 100,
                ),
                MapCallOutRequest(
                    id = "route-1",
                    target = MapCallOutTarget.PolylineMovable(route),
                    priority = 10,
                ),
                MapCallOutRequest(
                    id = "route-2",
                    target = MapCallOutTarget.PolylineMovable(route),
                ),
            ),
            sizes = listOf(
                IntSize(96, 48),
                IntSize(96, 48),
                IntSize(96, 48),
            ),
            viewportSize = ViewportSize,
            viewport = Viewport,
            tailLengthPx = TailLengthPx,
            project = ::projectForTest,
        )

        val tipXs = placements.map { it.tip.x }.sorted()

        assertEquals(3, placements.size)
        assertTrue(placements.all { it.tip.x in Viewport.left..Viewport.right })
        assertTrue(tipXs.last() - tipXs.first() >= 220f)
    }

    @Test
    fun lowerPriorityCallOutUsesOtherSideToAvoidOverlap() {
        val placements = MapCallOutPlacementEngine.place(
            requests = listOf(
                MapCallOutRequest(
                    id = "primary",
                    target = MapCallOutTarget.PointFixed(Offset(200f, 220f).toRoutePoint()),
                    priority = 100,
                ),
                MapCallOutRequest(
                    id = "secondary",
                    target = MapCallOutTarget.PointFixed(Offset(210f, 220f).toRoutePoint()),
                ),
            ),
            sizes = listOf(
                IntSize(96, 48),
                IntSize(96, 48),
            ),
            viewportSize = ViewportSize,
            viewport = Viewport,
            tailLengthPx = TailLengthPx,
            project = ::projectForTest,
        )

        assertEquals(2, placements.size)
        assertEquals(MapCallOutTailSide.BottomRight, placements[0].tailSide)
        assertEquals(MapCallOutTailSide.BottomLeft, placements[1].tailSide)
    }

    private companion object {
        val ViewportSize = IntSize(400, 400)
        val Viewport = Rect(
            left = 0f,
            top = 0f,
            right = 400f,
            bottom = 400f,
        )
        const val TailLengthPx = 9f

        fun Offset.toRoutePoint(): RoutePoint {
            return RoutePoint(
                latitude = y.toDouble(),
                longitude = x.toDouble(),
            )
        }

        fun projectForTest(point: RoutePoint): Offset {
            return Offset(
                x = point.longitude.toFloat(),
                y = point.latitude.toFloat(),
            )
        }
    }
}
