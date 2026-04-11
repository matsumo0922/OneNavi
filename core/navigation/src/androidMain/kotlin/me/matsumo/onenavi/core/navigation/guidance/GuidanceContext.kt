package me.matsumo.onenavi.core.navigation.guidance

import androidx.compose.runtime.Stable
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.navigation.base.trip.model.RouteProgress

/**
 * 1 回の RouteProgress 更新から案内生成に必要な情報を集約した文脈。
 */
@Stable
data class GuidanceContext(
    val routeId: String,
    val routeProgress: RouteProgress,
    val currentLegIndex: Int,
    val currentStepIndex: Int,
    val currentStep: LegStep?,
    val nextStep: LegStep?,
    val upcomingSteps: List<UpcomingStepContext>,
    val upcomingIntersections: List<UpcomingIntersectionContext>,
    val currentRoadKind: RoadKind,
    val previousRoadKind: RoadKind?,
    val lastVoiceInstruction: VoiceInstructions?,
    val lastBannerInstruction: BannerInstructions?,
    val sessionState: GuidanceSessionState,
    val waypointKinds: List<DestinationKind>,
    val speechHistory: GuidanceSpeechHistory,
)

/**
 * 現在位置から見た今後のステップと、その開始地点までの距離。
 */
@Stable
data class UpcomingStepContext(
    val legIndex: Int,
    val stepIndex: Int,
    val step: LegStep,
    val distanceFromCurrentMeters: Double,
)

/**
 * 現在位置から見た今後の交差点と、その交差点までの距離。
 */
@Stable
data class UpcomingIntersectionContext(
    val legIndex: Int,
    val stepIndex: Int,
    val intersectionIndex: Int,
    val geometryIndex: Int?,
    val distanceFromCurrentMeters: Double,
    val intersection: StepIntersection,
)

/**
 * 音声案内セッションの状態。
 */
@Stable
data class GuidanceSessionState(
    val startedAtMillis: Long,
    val muted: Boolean,
)
