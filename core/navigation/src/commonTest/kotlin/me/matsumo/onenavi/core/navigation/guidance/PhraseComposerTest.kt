package me.matsumo.onenavi.core.navigation.guidance

import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.model.CompassDirection
import me.matsumo.onenavi.core.model.DistanceBucket
import me.matsumo.onenavi.core.model.DrivingSide
import me.matsumo.onenavi.core.model.FollowupDistanceBucket
import me.matsumo.onenavi.core.model.FollowupManeuver
import me.matsumo.onenavi.core.model.GuidanceEvent
import me.matsumo.onenavi.core.model.GuidancePriority
import me.matsumo.onenavi.core.model.LanePosition
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.PhraseSegment
import me.matsumo.onenavi.core.model.StraightforwardLevel
import me.matsumo.onenavi.core.model.TtsPhraseId
import kotlin.test.Test
import kotlin.test.assertEquals

class PhraseComposerTest {

    private val composer = PhraseComposer()

    @Test
    fun `SessionStarted emits navigation_started + follow_traffic_rules`() = runTest {
        val phrase = composer.compose(GuidanceEvent.SessionStarted(GuidancePriority.CRITICAL))
        assertEquals(
            listOf(
                PhraseSegment.Fixed(TtsPhraseId.NAVIGATION_STARTED),
                PhraseSegment.Fixed(TtsPhraseId.FOLLOW_TRAFFIC_RULES),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `SessionFinished emits navigation_finished`() = runTest {
        val phrase = composer.compose(GuidanceEvent.SessionFinished(GuidancePriority.CRITICAL))
        assertEquals(
            listOf(PhraseSegment.Fixed(TtsPhraseId.NAVIGATION_FINISHED)),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `ViaWaypointApproach emits waypoint_approach`() = runTest {
        val phrase = composer.compose(GuidanceEvent.ViaWaypointApproach(GuidancePriority.CRITICAL))
        assertEquals(
            listOf(PhraseSegment.Fixed(TtsPhraseId.WAYPOINT_APPROACH)),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `DestinationApproach emits destination_approach + navigation_finished`() = runTest {
        val phrase = composer.compose(GuidanceEvent.DestinationApproach(GuidancePriority.CRITICAL))
        assertEquals(
            listOf(
                PhraseSegment.Fixed(TtsPhraseId.DESTINATION_APPROACH),
                PhraseSegment.Fixed(TtsPhraseId.NAVIGATION_FINISHED),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `OffRoute emits off_route`() = runTest {
        val phrase = composer.compose(GuidanceEvent.OffRoute(GuidancePriority.CRITICAL))
        assertEquals(
            listOf(PhraseSegment.Fixed(TtsPhraseId.OFF_ROUTE)),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `OnRouteRecovered emits on_route_recovered`() = runTest {
        val phrase = composer.compose(GuidanceEvent.OnRouteRecovered(GuidancePriority.CRITICAL))
        assertEquals(
            listOf(PhraseSegment.Fixed(TtsPhraseId.ON_ROUTE_RECOVERED)),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Rerouted emits rerouted_found + rerouted_start`() = runTest {
        val phrase = composer.compose(GuidanceEvent.Rerouted(GuidancePriority.CRITICAL))
        assertEquals(
            listOf(
                PhraseSegment.Fixed(TtsPhraseId.REROUTED_FOUND),
                PhraseSegment.Fixed(TtsPhraseId.REROUTED_START),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Maneuver TURN_RIGHT at AT_500M emits distance_500m + dir_right_end`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_500M,
                maneuverType = ManeuverType.TURN,
                modifier = ManeuverModifier.RIGHT,
                drivingSide = DrivingSide.LEFT,
                isStandaloneAt50m = false,
                followup = null,
                lanePosition = null,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(
            listOf(
                PhraseSegment.Distance(bucket = DistanceBucket.AT_500M, isStandalone = false),
                PhraseSegment.Fixed(TtsPhraseId.DIR_RIGHT_END),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Maneuver TURN_LEFT at AT_100M emits timing_imminent + dir_left_end`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_100M,
                maneuverType = ManeuverType.TURN,
                modifier = ManeuverModifier.LEFT,
                drivingSide = null,
                isStandaloneAt50m = false,
                followup = null,
                lanePosition = null,
                priority = GuidancePriority.HIGH,
            ),
        )
        assertEquals(
            listOf(
                PhraseSegment.Distance(bucket = DistanceBucket.AT_100M, isStandalone = false),
                PhraseSegment.Fixed(TtsPhraseId.DIR_LEFT_END),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Maneuver AT_50M standalone sets isStandalone=true for Distance segment`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_50M,
                maneuverType = ManeuverType.TURN,
                modifier = ManeuverModifier.RIGHT,
                drivingSide = null,
                isStandaloneAt50m = true,
                followup = null,
                lanePosition = null,
                priority = GuidancePriority.HIGH,
            ),
        )
        assertEquals(
            listOf(
                PhraseSegment.Distance(bucket = DistanceBucket.AT_50M, isStandalone = true),
                PhraseSegment.Fixed(TtsPhraseId.DIR_RIGHT_END),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Maneuver UTURN emits dir_uturn_end`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_100M,
                maneuverType = ManeuverType.UTURN,
                modifier = ManeuverModifier.UTURN,
                drivingSide = null,
                isStandaloneAt50m = false,
                followup = null,
                lanePosition = null,
                priority = GuidancePriority.HIGH,
            ),
        )
        assertEquals(TtsPhraseId.DIR_UTURN_END, (phrase.segments.last() as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Maneuver FORK emits fork_end regardless of modifier`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_500M,
                maneuverType = ManeuverType.FORK,
                modifier = ManeuverModifier.RIGHT,
                drivingSide = DrivingSide.LEFT,
                isStandaloneAt50m = false,
                followup = null,
                lanePosition = null,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(TtsPhraseId.FORK_END, (phrase.segments.last() as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Maneuver MERGE with drivingSide=RIGHT emits merge_right`() = runTest {
        val phrase = composer.compose(mergeEvent(DrivingSide.RIGHT))
        assertEquals(TtsPhraseId.MERGE_RIGHT, (phrase.segments.last() as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Maneuver MERGE with drivingSide=LEFT emits merge_left`() = runTest {
        val phrase = composer.compose(mergeEvent(DrivingSide.LEFT))
        assertEquals(TtsPhraseId.MERGE_LEFT, (phrase.segments.last() as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Maneuver MERGE with drivingSide=null emits merge`() = runTest {
        val phrase = composer.compose(mergeEvent(drivingSide = null))
        assertEquals(TtsPhraseId.MERGE, (phrase.segments.last() as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Maneuver with null modifier falls back to dir_straight_end`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_500M,
                maneuverType = ManeuverType.CONTINUE,
                modifier = null,
                drivingSide = null,
                isStandaloneAt50m = false,
                followup = null,
                lanePosition = null,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(TtsPhraseId.DIR_STRAIGHT_END, (phrase.segments.last() as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Maneuver with followup appends conjunction + followup distance + followup direction`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_100M,
                maneuverType = ManeuverType.TURN,
                modifier = ManeuverModifier.RIGHT,
                drivingSide = DrivingSide.LEFT,
                isStandaloneAt50m = false,
                followup = FollowupManeuver(
                    distanceBucket = FollowupDistanceBucket.AT_300M,
                    maneuverType = ManeuverType.TURN,
                    modifier = ManeuverModifier.LEFT,
                ),
                lanePosition = null,
                priority = GuidancePriority.HIGH,
            ),
        )
        assertEquals(
            listOf(
                PhraseSegment.Distance(bucket = DistanceBucket.AT_100M, isStandalone = false),
                PhraseSegment.Fixed(TtsPhraseId.DIR_RIGHT_END),
                PhraseSegment.Fixed(TtsPhraseId.CONJUNCTION_BEYOND),
                PhraseSegment.FollowupDistance(bucket = FollowupDistanceBucket.AT_300M),
                PhraseSegment.Fixed(TtsPhraseId.DIR_LEFT_END),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Lane LEFT emits distance + lane_left_side + lane_proceed`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Lane(
                stepCounter = 1,
                bucket = DistanceBucket.AT_500M,
                lanePosition = LanePosition.LEFT,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(
            listOf(
                PhraseSegment.Distance(bucket = DistanceBucket.AT_500M, isStandalone = false),
                PhraseSegment.Fixed(TtsPhraseId.LANE_LEFT_SIDE),
                PhraseSegment.Fixed(TtsPhraseId.LANE_PROCEED),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Lane CENTER emits lane_center`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Lane(
                stepCounter = 1,
                bucket = DistanceBucket.AT_500M,
                lanePosition = LanePosition.CENTER,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(TtsPhraseId.LANE_CENTER, (phrase.segments[1] as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Straightforward SHORT emits straight_short`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Straightforward(
                stepCounter = 1,
                level = StraightforwardLevel.SHORT,
                priority = GuidancePriority.LOW,
            ),
        )
        assertEquals(
            listOf(PhraseSegment.Fixed(TtsPhraseId.STRAIGHT_SHORT)),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Straightforward LONG emits straight_long`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Straightforward(
                stepCounter = 1,
                level = StraightforwardLevel.LONG,
                priority = GuidancePriority.LOW,
            ),
        )
        assertEquals(
            listOf(PhraseSegment.Fixed(TtsPhraseId.STRAIGHT_LONG)),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Depart WEST emits depart_west fixed phrase`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Depart(
                direction = CompassDirection.WEST,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(
            listOf(PhraseSegment.Fixed(TtsPhraseId.DEPART_WEST)),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Depart NORTHEAST emits depart_northeast fixed phrase`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Depart(
                direction = CompassDirection.NORTHEAST,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(
            listOf(PhraseSegment.Fixed(TtsPhraseId.DEPART_NORTHEAST)),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Maneuver with ON_RAMP followup + RIGHT maps to slight_right_end in followup`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_100M,
                maneuverType = ManeuverType.TURN,
                modifier = ManeuverModifier.LEFT,
                drivingSide = DrivingSide.LEFT,
                isStandaloneAt50m = false,
                followup = FollowupManeuver(
                    distanceBucket = FollowupDistanceBucket.AT_200M,
                    maneuverType = ManeuverType.ON_RAMP,
                    modifier = ManeuverModifier.RIGHT,
                ),
                lanePosition = null,
                priority = GuidancePriority.HIGH,
            ),
        )
        assertEquals(
            listOf(
                PhraseSegment.Distance(bucket = DistanceBucket.AT_100M, isStandalone = false),
                PhraseSegment.Fixed(TtsPhraseId.DIR_LEFT_END),
                PhraseSegment.Fixed(TtsPhraseId.CONJUNCTION_BEYOND),
                PhraseSegment.FollowupDistance(bucket = FollowupDistanceBucket.AT_200M),
                PhraseSegment.Fixed(TtsPhraseId.DIR_SLIGHT_RIGHT_END),
            ),
            phrase.segments.toList(),
        )
    }

    @Test
    fun `Maneuver OFF_RAMP + LEFT maps to slight_left_end`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_500M,
                maneuverType = ManeuverType.OFF_RAMP,
                modifier = ManeuverModifier.LEFT,
                drivingSide = DrivingSide.LEFT,
                isStandaloneAt50m = false,
                followup = null,
                lanePosition = null,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(TtsPhraseId.DIR_SLIGHT_LEFT_END, (phrase.segments.last() as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Maneuver ON_RAMP + RIGHT maps to slight_right_end`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_500M,
                maneuverType = ManeuverType.ON_RAMP,
                modifier = ManeuverModifier.RIGHT,
                drivingSide = DrivingSide.LEFT,
                isStandaloneAt50m = false,
                followup = null,
                lanePosition = null,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(TtsPhraseId.DIR_SLIGHT_RIGHT_END, (phrase.segments.last() as PhraseSegment.Fixed).phraseId)
    }

    @Test
    fun `Maneuver with lanePosition appends lane segments after direction`() = runTest {
        val phrase = composer.compose(
            GuidanceEvent.Maneuver(
                stepCounter = 1,
                bucket = DistanceBucket.AT_500M,
                maneuverType = ManeuverType.OFF_RAMP,
                modifier = ManeuverModifier.LEFT,
                drivingSide = DrivingSide.LEFT,
                isStandaloneAt50m = false,
                followup = null,
                lanePosition = LanePosition.LEFT,
                priority = GuidancePriority.NORMAL,
            ),
        )
        assertEquals(
            listOf(
                PhraseSegment.Distance(bucket = DistanceBucket.AT_500M, isStandalone = false),
                PhraseSegment.Fixed(TtsPhraseId.DIR_SLIGHT_LEFT_END),
                PhraseSegment.Fixed(TtsPhraseId.LANE_LEFT_SIDE),
                PhraseSegment.Fixed(TtsPhraseId.LANE_PROCEED),
            ),
            phrase.segments.toList(),
        )
    }

    private fun mergeEvent(drivingSide: DrivingSide?): GuidanceEvent.Maneuver {
        return GuidanceEvent.Maneuver(
            stepCounter = 1,
            bucket = DistanceBucket.AT_500M,
            maneuverType = ManeuverType.MERGE,
            modifier = null,
            drivingSide = drivingSide,
            isStandaloneAt50m = false,
            followup = null,
            lanePosition = null,
            priority = GuidancePriority.NORMAL,
        )
    }
}
