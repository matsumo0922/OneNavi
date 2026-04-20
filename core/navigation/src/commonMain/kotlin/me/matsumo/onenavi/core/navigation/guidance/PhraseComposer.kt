package me.matsumo.onenavi.core.navigation.guidance

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import me.matsumo.onenavi.core.model.CompassDirection
import me.matsumo.onenavi.core.model.DrivingSide
import me.matsumo.onenavi.core.model.FollowupManeuver
import me.matsumo.onenavi.core.model.GuidanceEvent
import me.matsumo.onenavi.core.model.GuidancePhrase
import me.matsumo.onenavi.core.model.LanePosition
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.PhraseSegment
import me.matsumo.onenavi.core.model.StraightforwardLevel
import me.matsumo.onenavi.core.model.TtsPhraseId
import org.jetbrains.compose.resources.getString

/**
 * [GuidanceEvent] から [GuidancePhrase] への組み立てと、発話文字列への解決を担う。
 *
 * [compose] はフレーズ段組みのみを行う純関数相当の suspend 関数（現状 suspend 不要だが、将来の動的フレーズに備えて suspend を保持）。
 * [resolve] は strings.xml を参照して最終的な発話文字列を生成する。
 */
class PhraseComposer {

    suspend fun compose(event: GuidanceEvent): GuidancePhrase = when (event) {
        is GuidanceEvent.SessionStarted -> phrase(
            PhraseSegment.Fixed(TtsPhraseId.NAVIGATION_STARTED),
            PhraseSegment.Fixed(TtsPhraseId.FOLLOW_TRAFFIC_RULES),
        )
        is GuidanceEvent.Depart -> phrase(
            PhraseSegment.Fixed(departPhraseId(event.direction)),
        )
        is GuidanceEvent.SessionFinished -> phrase(
            PhraseSegment.Fixed(TtsPhraseId.NAVIGATION_FINISHED),
        )
        is GuidanceEvent.ViaWaypointApproach -> phrase(
            PhraseSegment.Fixed(TtsPhraseId.WAYPOINT_APPROACH),
        )
        is GuidanceEvent.DestinationApproach -> phrase(
            PhraseSegment.Fixed(TtsPhraseId.DESTINATION_APPROACH),
            PhraseSegment.Fixed(TtsPhraseId.NAVIGATION_FINISHED),
        )
        is GuidanceEvent.OffRoute -> phrase(
            PhraseSegment.Fixed(TtsPhraseId.OFF_ROUTE),
        )
        is GuidanceEvent.OnRouteRecovered -> phrase(
            PhraseSegment.Fixed(TtsPhraseId.ON_ROUTE_RECOVERED),
        )
        is GuidanceEvent.Rerouted -> phrase(
            PhraseSegment.Fixed(TtsPhraseId.REROUTED_FOUND),
            PhraseSegment.Fixed(TtsPhraseId.REROUTED_START),
        )
        is GuidanceEvent.Maneuver -> composeManeuver(event)
        is GuidanceEvent.Lane -> composeLane(event)
        is GuidanceEvent.Straightforward -> composeStraightforward(event)
    }

    suspend fun resolve(phrase: GuidancePhrase): String = buildString {
        phrase.segments.forEach { segment ->
            when (segment) {
                is PhraseSegment.Fixed -> append(getString(segment.phraseId.resource))
                is PhraseSegment.Distance -> {
                    val phraseId = if (segment.isStandalone) {
                        segment.bucket.standalonePhraseId
                    } else {
                        segment.bucket.phraseId
                    }
                    append(getString(phraseId.resource))
                }
                is PhraseSegment.FollowupDistance -> {
                    append(getString(segment.bucket.phraseId.resource))
                }
            }
        }
    }

    private fun composeManeuver(event: GuidanceEvent.Maneuver): GuidancePhrase {
        val head = PhraseSegment.Distance(
            bucket = event.bucket,
            isStandalone = event.isStandaloneAt50m,
        )
        val tail = PhraseSegment.Fixed(resolveManeuverTail(event))
        val followupSegments = event.followup?.let { followup -> composeFollowupSegments(followup) }
        val laneSegments = event.lanePosition?.let { position -> composeLaneSegments(position) }
        val segments = buildList {
            add(head)
            add(tail)
            if (followupSegments != null) addAll(followupSegments)
            if (laneSegments != null) addAll(laneSegments)
        }
        return GuidancePhrase(segments = segments.toPersistentList())
    }

    private fun composeFollowupSegments(followup: FollowupManeuver): List<PhraseSegment> {
        val directionEnd = when (followup.maneuverType) {
            ManeuverType.OFF_RAMP,
            ManeuverType.ON_RAMP,
            -> rampModifierToDirectionEnd(followup.modifier)
            else -> modifierToDirectionEnd(followup.modifier)
        }
        return listOf(
            PhraseSegment.Fixed(TtsPhraseId.CONJUNCTION_BEYOND),
            PhraseSegment.FollowupDistance(bucket = followup.distanceBucket),
            PhraseSegment.Fixed(directionEnd),
        )
    }

    private fun composeLane(event: GuidanceEvent.Lane): GuidancePhrase {
        return phrase(
            PhraseSegment.Distance(bucket = event.bucket),
            *composeLaneSegments(event.lanePosition).toTypedArray(),
        )
    }

    private fun composeLaneSegments(position: LanePosition): List<PhraseSegment> {
        val positionPhraseId = when (position) {
            LanePosition.LEFT -> TtsPhraseId.LANE_LEFT_SIDE
            LanePosition.RIGHT -> TtsPhraseId.LANE_RIGHT_SIDE
            LanePosition.CENTER -> TtsPhraseId.LANE_CENTER
        }
        return listOf(
            PhraseSegment.Fixed(positionPhraseId),
            PhraseSegment.Fixed(TtsPhraseId.LANE_PROCEED),
        )
    }

    private fun composeStraightforward(event: GuidanceEvent.Straightforward): GuidancePhrase {
        val phraseId = when (event.level) {
            StraightforwardLevel.SHORT -> TtsPhraseId.STRAIGHT_SHORT
            StraightforwardLevel.LONG -> TtsPhraseId.STRAIGHT_LONG
        }
        return phrase(PhraseSegment.Fixed(phraseId))
    }

    private fun resolveManeuverTail(event: GuidanceEvent.Maneuver): TtsPhraseId {
        return when (event.maneuverType) {
            ManeuverType.FORK -> TtsPhraseId.FORK_END
            ManeuverType.MERGE -> when (event.drivingSide) {
                DrivingSide.RIGHT -> TtsPhraseId.MERGE_RIGHT
                DrivingSide.LEFT -> TtsPhraseId.MERGE_LEFT
                null -> TtsPhraseId.MERGE
            }
            ManeuverType.OFF_RAMP,
            ManeuverType.ON_RAMP,
            -> rampModifierToDirectionEnd(event.modifier)
            else -> modifierToDirectionEnd(event.modifier)
        }
    }

    /**
     * ランプ（OFF_RAMP / ON_RAMP）の方向修飾子をフレーズへ射影する。
     *
     * ランプは本線から分岐・合流する斜め方向の案内が自然なため、LEFT/RIGHT は斜め系に寄せる。
     * それ以外の修飾子（斜め系・直進・U ターン）は通常マッピングをそのまま使う。
     */
    private fun rampModifierToDirectionEnd(modifier: ManeuverModifier?): TtsPhraseId = when (modifier) {
        ManeuverModifier.LEFT -> TtsPhraseId.DIR_SLIGHT_LEFT_END
        ManeuverModifier.RIGHT -> TtsPhraseId.DIR_SLIGHT_RIGHT_END
        else -> modifierToDirectionEnd(modifier)
    }

    private fun modifierToDirectionEnd(modifier: ManeuverModifier?): TtsPhraseId = when (modifier) {
        ManeuverModifier.STRAIGHT -> TtsPhraseId.DIR_STRAIGHT_END
        ManeuverModifier.SLIGHT_RIGHT -> TtsPhraseId.DIR_SLIGHT_RIGHT_END
        ManeuverModifier.RIGHT -> TtsPhraseId.DIR_RIGHT_END
        ManeuverModifier.SHARP_RIGHT -> TtsPhraseId.DIR_SHARP_RIGHT_END
        ManeuverModifier.UTURN -> TtsPhraseId.DIR_UTURN_END
        ManeuverModifier.SHARP_LEFT -> TtsPhraseId.DIR_SHARP_LEFT_END
        ManeuverModifier.LEFT -> TtsPhraseId.DIR_LEFT_END
        ManeuverModifier.SLIGHT_LEFT -> TtsPhraseId.DIR_SLIGHT_LEFT_END
        null -> TtsPhraseId.DIR_STRAIGHT_END
    }

    private fun phrase(vararg segments: PhraseSegment): GuidancePhrase {
        return GuidancePhrase(segments = persistentListOf(*segments))
    }

    private fun departPhraseId(direction: CompassDirection): TtsPhraseId = when (direction) {
        CompassDirection.NORTH -> TtsPhraseId.DEPART_NORTH
        CompassDirection.NORTHEAST -> TtsPhraseId.DEPART_NORTHEAST
        CompassDirection.EAST -> TtsPhraseId.DEPART_EAST
        CompassDirection.SOUTHEAST -> TtsPhraseId.DEPART_SOUTHEAST
        CompassDirection.SOUTH -> TtsPhraseId.DEPART_SOUTH
        CompassDirection.SOUTHWEST -> TtsPhraseId.DEPART_SOUTHWEST
        CompassDirection.WEST -> TtsPhraseId.DEPART_WEST
        CompassDirection.NORTHWEST -> TtsPhraseId.DEPART_NORTHWEST
    }
}
