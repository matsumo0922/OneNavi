package me.matsumo.onenavi.car.navigation

import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout
import me.matsumo.onenavi.guidance.GuidanceInstructionFormatter
import java.util.TimeZone
import kotlin.math.round

/** OneNavi の案内状態を Android Auto host に渡す Trip metadata へ変換する mapper。 */
internal class GuidanceCarTripMapper(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 通常案内状態を Trip に変換する。 */
    fun toTrip(state: GuidanceState.Guiding): Trip {
        val progress = state.progress
        val destination = state.route.toDestination()
        val destinationEstimate = progress.toDestinationTravelEstimate()
        val tripBuilder = Trip.Builder()
            .addDestination(destination, destinationEstimate)

        val currentRoadName = progress.currentRoadName?.takeIf { roadName -> roadName.isNotBlank() }
        if (currentRoadName != null) {
            tripBuilder.setCurrentRoad(currentRoadName)
        }

        val nextManeuver = state.presentation.nextManeuver
        if (nextManeuver != null) {
            tripBuilder.addStep(
                nextManeuver.toStep(state),
                nextManeuver.toStepTravelEstimate(progress),
            )
        }

        return tripBuilder.build()
    }

    /** 再探索中の Trip を loading state として生成する。 */
    fun toLoadingTrip(state: GuidanceState.Rerouting): Trip {
        val currentRoadName = state.previousProgress.currentRoadName
            ?.takeIf { roadName -> roadName.isNotBlank() }

        return Trip.Builder()
            .setLoading(true)
            .also { tripBuilder ->
                if (currentRoadName != null) {
                    tripBuilder.setCurrentRoad(currentRoadName)
                }
            }
            .build()
    }

    private fun RouteDetail.toDestination(): Destination {
        val destinationName = routeWaypoints
            .lastOrNull()
            .let { waypoint -> waypoint as? RouteWaypoint.Place }
            ?.name
            ?.takeIf { name -> name.isNotBlank() }
            ?: DEFAULT_DESTINATION_NAME

        return Destination.Builder()
            .setName(destinationName)
            .build()
    }

    private fun ManeuverCallout.toStep(state: GuidanceState.Guiding): Step {
        val stepBuilder = Step.Builder(GuidanceInstructionFormatter.format(this))
            .setManeuver(toCarManeuver())

        val roadName = state.presentation.banner?.secondaryLabel
            ?: state.progress.currentRoadName
        val safeRoadName = roadName?.takeIf { value -> value.isNotBlank() }
        if (safeRoadName != null) {
            stepBuilder.setRoad(safeRoadName)
        }

        return stepBuilder.build()
    }

    private fun ManeuverCallout.toCarManeuver(): Maneuver {
        return Maneuver.Builder(toCarManeuverType()).build()
    }

    private fun ManeuverCallout.toCarManeuverType(): Int {
        return when (type) {
            ManeuverType.ARRIVE -> modifier.toDestinationType()
            ManeuverType.CONTINUE -> Maneuver.TYPE_STRAIGHT
            ManeuverType.DEPART -> Maneuver.TYPE_DEPART
            ManeuverType.END_OF_ROAD -> modifier.toTurnType()
            ManeuverType.FORK -> modifier.toForkType()
            ManeuverType.MERGE -> modifier.toMergeType()
            ManeuverType.NAME_CHANGE -> Maneuver.TYPE_NAME_CHANGE
            ManeuverType.OFF_RAMP -> modifier.toOffRampType()
            ManeuverType.ON_RAMP -> modifier.toOnRampType()
            ManeuverType.ROTARY,
            ManeuverType.ROUNDABOUT,
            ManeuverType.TRAFFIC_CIRCLE,
            -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            ManeuverType.TURN -> modifier.toTurnType()
            ManeuverType.UTURN -> modifier.toUTurnType()
        }
    }

    private fun ManeuverModifier.toTurnType(): Int {
        return when (this) {
            ManeuverModifier.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            ManeuverModifier.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            ManeuverModifier.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            ManeuverModifier.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            ManeuverModifier.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            ManeuverModifier.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            ManeuverModifier.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ManeuverModifier.UTURN -> Maneuver.TYPE_U_TURN_LEFT
        }
    }

    private fun ManeuverModifier.toOnRampType(): Int {
        return when (this) {
            ManeuverModifier.LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
            ManeuverModifier.RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            ManeuverModifier.SHARP_LEFT -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
            ManeuverModifier.SHARP_RIGHT -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
            ManeuverModifier.SLIGHT_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
            ManeuverModifier.SLIGHT_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
            ManeuverModifier.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ManeuverModifier.UTURN -> Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
        }
    }

    private fun ManeuverModifier.toOffRampType(): Int {
        return when (this) {
            ManeuverModifier.LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            ManeuverModifier.RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            ManeuverModifier.SHARP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            ManeuverModifier.SHARP_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            ManeuverModifier.SLIGHT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
            ManeuverModifier.SLIGHT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
            ManeuverModifier.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ManeuverModifier.UTURN -> Maneuver.TYPE_U_TURN_LEFT
        }
    }

    private fun ManeuverModifier.toForkType(): Int {
        return when (this) {
            ManeuverModifier.LEFT,
            ManeuverModifier.SHARP_LEFT,
            ManeuverModifier.SLIGHT_LEFT,
            -> Maneuver.TYPE_FORK_LEFT
            ManeuverModifier.RIGHT,
            ManeuverModifier.SHARP_RIGHT,
            ManeuverModifier.SLIGHT_RIGHT,
            -> Maneuver.TYPE_FORK_RIGHT
            ManeuverModifier.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ManeuverModifier.UTURN -> Maneuver.TYPE_U_TURN_LEFT
        }
    }

    private fun ManeuverModifier.toMergeType(): Int {
        return when (this) {
            ManeuverModifier.LEFT,
            ManeuverModifier.SHARP_LEFT,
            ManeuverModifier.SLIGHT_LEFT,
            -> Maneuver.TYPE_MERGE_LEFT
            ManeuverModifier.RIGHT,
            ManeuverModifier.SHARP_RIGHT,
            ManeuverModifier.SLIGHT_RIGHT,
            -> Maneuver.TYPE_MERGE_RIGHT
            ManeuverModifier.STRAIGHT,
            ManeuverModifier.UTURN,
            -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        }
    }

    private fun ManeuverModifier.toUTurnType(): Int {
        return when (this) {
            ManeuverModifier.RIGHT,
            ManeuverModifier.SHARP_RIGHT,
            ManeuverModifier.SLIGHT_RIGHT,
            -> Maneuver.TYPE_U_TURN_RIGHT
            ManeuverModifier.LEFT,
            ManeuverModifier.SHARP_LEFT,
            ManeuverModifier.SLIGHT_LEFT,
            ManeuverModifier.STRAIGHT,
            ManeuverModifier.UTURN,
            -> Maneuver.TYPE_U_TURN_LEFT
        }
    }

    private fun ManeuverModifier.toDestinationType(): Int {
        return when (this) {
            ManeuverModifier.LEFT,
            ManeuverModifier.SHARP_LEFT,
            ManeuverModifier.SLIGHT_LEFT,
            -> Maneuver.TYPE_DESTINATION_LEFT
            ManeuverModifier.RIGHT,
            ManeuverModifier.SHARP_RIGHT,
            ManeuverModifier.SLIGHT_RIGHT,
            -> Maneuver.TYPE_DESTINATION_RIGHT
            ManeuverModifier.STRAIGHT,
            ManeuverModifier.UTURN,
            -> Maneuver.TYPE_DESTINATION_STRAIGHT
        }
    }

    private fun GuidanceProgress.toDestinationTravelEstimate(): TravelEstimate {
        return toTravelEstimate(
            distanceMeters = distanceRemainingMeters,
            durationSeconds = durationRemainingSeconds.toLong(),
            etaEpochMillis = etaEpochMillis,
        )
    }

    private fun ManeuverCallout.toStepTravelEstimate(progress: GuidanceProgress): TravelEstimate {
        val stepDurationSeconds = progress.estimateDurationTo(distanceToManeuverMeters)
        val etaEpochMillis = nowMillis() + stepDurationSeconds * MILLIS_PER_SECOND

        return toTravelEstimate(
            distanceMeters = distanceToManeuverMeters,
            durationSeconds = stepDurationSeconds,
            etaEpochMillis = etaEpochMillis,
        )
    }

    private fun GuidanceProgress.estimateDurationTo(distanceMeters: Int): Long {
        if (distanceRemainingMeters <= 0) {
            return 0L
        }

        val progressRatio = distanceMeters.coerceAtLeast(0).toDouble() / distanceRemainingMeters
        val durationSeconds = durationRemainingSeconds * progressRatio

        return durationSeconds.toLong().coerceAtLeast(0L)
    }

    private fun toTravelEstimate(
        distanceMeters: Int,
        durationSeconds: Long,
        etaEpochMillis: Long,
    ): TravelEstimate {
        return TravelEstimate.Builder(
            distanceMeters.toCarDistance(),
            DateTimeWithZone.create(etaEpochMillis.coerceAtLeast(0L), TimeZone.getDefault()),
        )
            .setRemainingTimeSeconds(durationSeconds.coerceAtLeast(0L))
            .build()
    }

    private fun Int.toCarDistance(): Distance {
        val safeDistanceMeters = coerceAtLeast(0)
        if (safeDistanceMeters < KILOMETER_IN_METERS) {
            return Distance.create(safeDistanceMeters.toDouble(), Distance.UNIT_METERS)
        }

        val roundedKilometers = round(safeDistanceMeters / KILOMETER_ROUNDING_METERS) / KILOMETER_DECIMAL_DIVISOR

        return Distance.create(roundedKilometers, Distance.UNIT_KILOMETERS_P1)
    }

    /** Trip mapper で使う固定値。 */
    private companion object {
        /** 目的地名がルートに無い場合の表示名。 */
        const val DEFAULT_DESTINATION_NAME = "目的地"

        /** km 表示に切り替える距離。 */
        const val KILOMETER_IN_METERS = 1000

        /** km 表示を小数 1 桁へ丸めるためのメートル単位。 */
        const val KILOMETER_ROUNDING_METERS = 100.0

        /** km 表示の小数桁を戻す除数。 */
        const val KILOMETER_DECIMAL_DIVISOR = 10.0

        /** 秒からミリ秒に変換する係数。 */
        const val MILLIS_PER_SECOND = 1000L
    }
}
