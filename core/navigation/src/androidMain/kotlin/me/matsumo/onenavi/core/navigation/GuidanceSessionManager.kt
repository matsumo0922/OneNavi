package me.matsumo.onenavi.core.navigation

import android.content.Context
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.BannerInstructionsObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.GuidanceUiState
import me.matsumo.onenavi.core.model.HighwayInfo
import me.matsumo.onenavi.core.model.HighwayPointType
import me.matsumo.onenavi.core.model.LaneInfo
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.RouteStepInfo
import me.matsumo.onenavi.core.model.TripProgressInfo
import me.matsumo.onenavi.core.navigation.guidance.GuidanceAnnouncementManager
import me.matsumo.onenavi.core.navigation.guidance.GuidanceEvent

/**
 * ナビゲーションセッション全体のライフサイクルを管理するクラス。
 * TripSession の開始/停止、Observer 群の登録/解除、TTS の初期化/再生/停止を担当する。
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class GuidanceSessionManager(
    private val context: Context,
    private val cameraManager: CameraManager,
) {

    private var mapboxNavigation: MapboxNavigation? = null

    private val _navigationState = MutableStateFlow<NavigationState>(NavigationState.Browsing)

    /** 現在のナビゲーション状態。 */
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _guidanceUiState = MutableStateFlow(GuidanceUiState.Initial)

    /** ナビ中の UI 表示データ。 */
    val guidanceUiState: StateFlow<GuidanceUiState> = _guidanceUiState.asStateFlow()

    private val _arrivalInfo = MutableStateFlow<ArrivalInfo?>(null)

    /** 到着時の集計情報。 */
    val arrivalInfo: StateFlow<ArrivalInfo?> = _arrivalInfo.asStateFlow()

    private val _guidanceEvents = MutableSharedFlow<GuidanceEvent>(extraBufferCapacity = 32)

    /** UI / ログが購読できる構造化案内イベント。 */
    val guidanceEvents: SharedFlow<GuidanceEvent> = _guidanceEvents.asSharedFlow()

    // --- TTS / Guidance events ---

    private var guidanceAnnouncementManager: GuidanceAnnouncementManager? = null
    private var lastPrimaryRouteId: String? = null

    // --- 走行記録 ---

    private var sessionStartTimeMillis: Long = 0L
    private var lastRouteProgress: RouteProgress? = null

    /** 前回ログ出力した stepIndex（同じステップで繰り返しログを出さないための制御用）。 */
    private var lastLoggedStepIndex: Int = -1

    /** 前回レーン情報を保持していた stepIndex。ステップ遷移時にレーンをクリアするために使用。 */
    private var lastManeuverStepIndex: Int = -1

    // --- Observers ---

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        lastRouteProgress = routeProgress
        updateGuidanceUiState(routeProgress)
        cameraManager.onRouteProgressChanged(routeProgress)
        guidanceAnnouncementManager?.onRouteProgress(routeProgress)
        onRouteProgressForRouteLine?.invoke(routeProgress)
    }

    private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
        guidanceAnnouncementManager?.onVoiceInstructions(voiceInstructions)
    }

    private val bannerInstructionsObserver = BannerInstructionsObserver { bannerInstructions ->
        guidanceAnnouncementManager?.onBannerInstructions(bannerInstructions)
        updateManeuverFromBanner(bannerInstructions)
    }

    private val offRouteObserver = OffRouteObserver { isOffRoute ->
        _guidanceUiState.value = _guidanceUiState.value.copy(isOffRoute = isOffRoute)
        guidanceAnnouncementManager?.onOffRoute(isOffRoute)
        if (isOffRoute) {
            Napier.d(tag = TAG) { "Off route detected, waiting for reroute..." }
        }
    }

    private val routesObserver = RoutesObserver { result ->
        val primaryRouteId = result.navigationRoutes.firstOrNull()?.id ?: return@RoutesObserver
        val previousRouteId = lastPrimaryRouteId
        lastPrimaryRouteId = primaryRouteId

        if (previousRouteId != null && previousRouteId != primaryRouteId) {
            guidanceAnnouncementManager?.onRouteChanged(primaryRouteId)
        }
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onWaypointArrival(routeProgress: RouteProgress) {
            guidanceAnnouncementManager?.onWaypointArrival(
                routeProgress = routeProgress,
                finalDestination = false,
            )
            mapboxNavigation?.navigateNextRouteLeg {
                Napier.d(tag = TAG) { "Navigating to next route leg: index=$it" }
            }
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {
            Napier.d(tag = TAG) { "Next route leg started" }
        }

        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            val elapsedSeconds = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000.0
            val distanceTraveled = routeProgress.distanceTraveled.toDouble()

            val destinationName = routeProgress.navigationRoute.waypoints
                ?.lastOrNull()
                ?.name()
                .orEmpty()

            _arrivalInfo.value = ArrivalInfo(
                destinationName = destinationName,
                totalDistanceMeters = distanceTraveled,
                totalDurationSeconds = elapsedSeconds,
            )

            guidanceAnnouncementManager?.onWaypointArrival(
                routeProgress = routeProgress,
                finalDestination = true,
            )
            _navigationState.value = NavigationState.Arrival
        }
    }

    /** UI 層から設定される RouteProgress コールバック（ルートライン消失更新用）。 */
    var onRouteProgressForRouteLine: ((RouteProgress) -> Unit)? = null

    private val navigationObserver = object : MapboxNavigationObserver {
        override fun onAttached(mapboxNavigation: MapboxNavigation) {
            this@GuidanceSessionManager.mapboxNavigation = mapboxNavigation
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            this@GuidanceSessionManager.mapboxNavigation = null
        }
    }

    fun register() {
        MapboxNavigationApp.registerObserver(navigationObserver)
    }

    fun unregister() {
        MapboxNavigationApp.unregisterObserver(navigationObserver)
    }

    /**
     * ナビゲーション状態を直接設定する（Search / RoutePreview 等、ナビ外の遷移用）。
     */
    fun setNavigationState(state: NavigationState) {
        _navigationState.value = state
    }

    /**
     * ナビゲーションセッションを開始する。
     * TripSession を開始し、全 Observer を登録し、TTS を初期化する。
     */
    fun startSession() {
        val navigation = mapboxNavigation ?: return

        sessionStartTimeMillis = System.currentTimeMillis()
        lastRouteProgress = null
        lastPrimaryRouteId = navigation.getNavigationRoutes().firstOrNull()?.id

        navigation.startTripSession(withForegroundService = true)

        guidanceAnnouncementManager = GuidanceAnnouncementManager(context).also { manager ->
            manager.onEvent = _guidanceEvents::tryEmit
            manager.start(lastPrimaryRouteId.orEmpty())
        }

        navigation.registerRouteProgressObserver(routeProgressObserver)
        navigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
        navigation.registerBannerInstructionsObserver(bannerInstructionsObserver)
        navigation.registerOffRouteObserver(offRouteObserver)
        navigation.registerArrivalObserver(arrivalObserver)
        navigation.registerRoutesObserver(routesObserver)

        dumpAllStepsForDebug(navigation)

        _navigationState.value = NavigationState.ActiveGuidance
        _guidanceUiState.value = GuidanceUiState.Initial.copy(isTtsAvailable = false)
    }

    /**
     * ナビゲーションセッションを停止する。
     * 全 Observer を解除し、TTS を破棄し、TripSession を停止する。
     */
    fun stopSession() {
        val navigation = mapboxNavigation ?: return

        navigation.unregisterRouteProgressObserver(routeProgressObserver)
        navigation.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
        navigation.unregisterBannerInstructionsObserver(bannerInstructionsObserver)
        navigation.unregisterOffRouteObserver(offRouteObserver)
        navigation.unregisterArrivalObserver(arrivalObserver)
        navigation.unregisterRoutesObserver(routesObserver)

        navigation.stopTripSession()

        guidanceAnnouncementManager?.stop(lastPrimaryRouteId.orEmpty())
        guidanceAnnouncementManager = null

        _guidanceUiState.value = GuidanceUiState.Initial
        _arrivalInfo.value = null
        lastRouteProgress = null
        lastLoggedStepIndex = -1
        lastManeuverStepIndex = -1
        lastPrimaryRouteId = null
        onRouteProgressForRouteLine = null
    }

    /**
     * ナビゲーション状態を Browsing に戻す（到着後 or 手動終了）。
     */
    fun returnToBrowsing() {
        _navigationState.value = NavigationState.Browsing
    }

    fun setTtsMuted(muted: Boolean) {
        guidanceAnnouncementManager?.setMuted(muted)
    }

    // --- RouteProgress → GuidanceUiState ---

    private fun updateGuidanceUiState(routeProgress: RouteProgress) {
        val currentLegProgress = routeProgress.currentLegProgress
        val currentStepProgress = currentLegProgress?.currentStepProgress
        val currentStep = currentStepProgress?.step

        val currentStepIndex = currentStepProgress?.stepIndex ?: -1
        val existingLanes = if (currentStepIndex != lastManeuverStepIndex) {
            lastManeuverStepIndex = currentStepIndex
            persistentListOf()
        } else {
            _guidanceUiState.value.currentManeuver?.lanes ?: persistentListOf()
        }

        val currentManeuver = currentStep?.maneuver()?.let { maneuver ->
            ManeuverInfo(
                type = maneuver.type().orEmpty(),
                modifier = maneuver.modifier(),
                drivingSide = currentStep.drivingSide(),
                distanceMeters = currentStepProgress.distanceRemaining.toDouble(),
                instruction = currentStep.name().orEmpty(),
                roadName = currentStep.name()?.takeIf { it.isNotBlank() },
                destinations = currentStep.destinations()?.takeIf { it.isNotBlank() },
                lanes = existingLanes,
            )
        }

        val nextStep = currentLegProgress?.upcomingStep
        val nextManeuver = nextStep?.maneuver()?.let { maneuver ->
            ManeuverInfo(
                type = maneuver.type().orEmpty(),
                modifier = maneuver.modifier(),
                drivingSide = nextStep.drivingSide(),
                distanceMeters = nextStep.distance(),
                instruction = nextStep.name().orEmpty(),
                roadName = nextStep.name()?.takeIf { it.isNotBlank() },
                destinations = nextStep.destinations()?.takeIf { it.isNotBlank() },
            )
        }

        val upcomingSteps = extractUpcomingSteps(routeProgress)

        val tripProgress = TripProgressInfo(
            distanceRemainingMeters = routeProgress.distanceRemaining.toDouble(),
            durationRemainingSeconds = routeProgress.durationRemaining,
            estimatedArrivalTimeMillis = System.currentTimeMillis() + (routeProgress.durationRemaining * 1000).toLong(),
        )

        val currentRoadName = currentStep?.name()?.takeIf { it.isNotBlank() }

        // ステップが進んだ時だけログ出力（毎秒出すと多すぎるので）
        if (currentStepIndex != lastLoggedStepIndex) {
            lastLoggedStepIndex = currentStepIndex
            logUpcomingSteps(upcomingSteps)
        }

        _guidanceUiState.value = _guidanceUiState.value.copy(
            currentManeuver = currentManeuver,
            nextManeuver = nextManeuver,
            upcomingSteps = upcomingSteps.toImmutableList(),
            tripProgress = tripProgress,
            currentRoadName = currentRoadName,
            isLocationStale = routeProgress.stale,
            isTtsAvailable = guidanceAnnouncementManager?.isReady?.value == true,
        )
    }

    /**
     * RouteProgress から現在位置以降の全ステップを抽出する。
     * 現在の leg の残りステップ ＋ 以降の leg の全ステップを連結し、
     * 現在位置からの累積距離を計算する。
     */
    private fun extractUpcomingSteps(routeProgress: RouteProgress): List<RouteStepInfo> {
        val legs = routeProgress.navigationRoute.directionsRoute.legs().orEmpty()
        val currentLegIndex = routeProgress.currentLegProgress?.legIndex ?: 0
        val currentStepIndex = routeProgress.currentLegProgress?.currentStepProgress?.stepIndex ?: 0
        val currentStepDistanceRemaining = routeProgress.currentLegProgress
            ?.currentStepProgress?.distanceRemaining?.toDouble() ?: 0.0

        val result = mutableListOf<RouteStepInfo>()
        var cumulativeDistance = 0.0

        for (legIndex in currentLegIndex until legs.size) {
            val leg = legs[legIndex]
            val steps = leg.steps().orEmpty()
            val startStepIndex = if (legIndex == currentLegIndex) currentStepIndex + 1 else 0

            // 現在ステップの残り距離を累積距離の初期値にする（最初の leg のみ）
            if (legIndex == currentLegIndex) {
                cumulativeDistance = currentStepDistanceRemaining
            }

            for (stepIndex in startStepIndex until steps.size) {
                val step = steps[stepIndex]
                val maneuver = step.maneuver()
                val maneuverType = maneuver.type().orEmpty()

                // depart / arrive(最終) 以外の意味のあるステップのみ抽出
                if (maneuverType == "depart") continue

                val stepDistance = step.distance()
                if (stepIndex > startStepIndex || legIndex > currentLegIndex) {
                    cumulativeDistance += stepDistance
                }

                val highwayInfo = detectHighwayInfo(
                    maneuverType = maneuverType,
                    instruction = maneuver.instruction().orEmpty(),
                    step = step,
                )

                result.add(
                    RouteStepInfo(
                        maneuverType = maneuverType,
                        modifier = maneuver.modifier(),
                        distanceFromPreviousMeters = stepDistance,
                        cumulativeDistanceMeters = cumulativeDistance,
                        instruction = maneuver.instruction().orEmpty(),
                        roadName = step.name().orEmpty(),
                        roadRef = step.ref(),
                        highwayInfo = highwayInfo,
                    ),
                )
            }
        }

        return result
    }

    /**
     * ステップの情報から高速道路関連ポイント（IC / JCT / 料金所）を検出する。
     */
    private fun detectHighwayInfo(
        maneuverType: String,
        instruction: String,
        step: com.mapbox.api.directions.v5.models.LegStep,
    ): HighwayInfo? {
        // 料金所の検出: intersection に tollCollection がある場合
        val hasTollGate = step.intersections().orEmpty().any { intersection ->
            intersection.tollCollection() != null
        }
        if (hasTollGate) {
            return HighwayInfo(
                type = HighwayPointType.TOLL_GATE,
                name = instruction,
            )
        }

        // IC / JCT / ランプの検出: maneuver type から判定
        return when (maneuverType) {
            "on ramp", "off ramp" -> {
                val type = classifyRampType(instruction)
                HighwayInfo(
                    type = type,
                    name = instruction,
                )
            }

            "fork" -> {
                // fork は JCT の可能性がある
                val isHighwayContext = step.intersections().orEmpty().any { intersection ->
                    intersection.classes().orEmpty().any { roadClass ->
                        roadClass in HIGHWAY_CLASSES
                    }
                }
                if (isHighwayContext) {
                    HighwayInfo(
                        type = HighwayPointType.JUNCTION,
                        name = instruction,
                    )
                } else {
                    null
                }
            }

            else -> null
        }
    }

    /**
     * ランプの案内テキストから IC か JCT かを推定する。
     * テキストに「JCT」「ジャンクション」が含まれれば JCT、
     * 「IC」「インター」が含まれれば IC、それ以外は RAMP とする。
     */
    private fun classifyRampType(instruction: String): HighwayPointType {
        val upper = instruction.uppercase()
        return when {
            "JCT" in upper || "ジャンクション" in instruction -> HighwayPointType.JUNCTION
            "IC" in upper || "インター" in instruction -> HighwayPointType.INTERCHANGE
            else -> HighwayPointType.RAMP
        }
    }

    private fun logUpcomingSteps(steps: List<RouteStepInfo>) {
        if (steps.isEmpty()) return

        val logLines = buildString {
            appendLine("=== Upcoming Route Steps (${steps.size} steps) ===")
            steps.forEachIndexed { index, step ->
                val distanceLabel = formatDistanceForLog(step.cumulativeDistanceMeters)
                val typeLabel = formatManeuverTypeForLog(step.maneuverType, step.modifier)
                val highwayLabel = step.highwayInfo?.let { info ->
                    val typeTag = when (info.type) {
                        HighwayPointType.INTERCHANGE -> "[IC]"
                        HighwayPointType.JUNCTION -> "[JCT]"
                        HighwayPointType.TOLL_GATE -> "[TOLL]"
                        HighwayPointType.RAMP -> "[RAMP]"
                    }
                    " $typeTag"
                }.orEmpty()

                val roadInfo = buildString {
                    if (step.roadName.isNotBlank()) append(step.roadName)
                    if (!step.roadRef.isNullOrBlank()) append(" (${step.roadRef})")
                }

                appendLine("  #${index + 1} | $distanceLabel | $typeLabel$highwayLabel | ${step.instruction} | $roadInfo")
            }
            append("=== End of Route Steps ===")
        }

        Napier.d(tag = TAG) { logLines }
    }

    private fun formatDistanceForLog(meters: Double): String {
        return if (meters >= 1000) {
            "%.1fkm".format(meters / 1000)
        } else {
            "%.0fm".format(meters)
        }
    }

    private fun formatManeuverTypeForLog(type: String, modifier: String?): String {
        val base = when (type) {
            "turn" -> "曲がる"
            "new name" -> "道なり"
            "merge" -> "合流"
            "on ramp" -> "ランプ進入"
            "off ramp" -> "ランプ退出"
            "fork" -> "分岐"
            "end of road" -> "突き当たり"
            "continue" -> "直進"
            "roundabout" -> "ロータリー"
            "arrive" -> "到着"
            "notification" -> "通知"
            else -> type
        }
        val direction = when (modifier) {
            "left" -> "左"
            "right" -> "右"
            "slight left" -> "やや左"
            "slight right" -> "やや右"
            "sharp left" -> "鋭角左"
            "sharp right" -> "鋭角右"
            "straight" -> "直進"
            "uturn" -> "Uターン"
            else -> ""
        }
        return if (direction.isNotEmpty()) "$base($direction)" else base
    }

    private fun updateManeuverFromBanner(bannerInstructions: BannerInstructions) {
        val primary = bannerInstructions.primary()
        val instruction = primary.text()

        val lanes = bannerInstructions.sub()
            ?.components()
            .orEmpty()
            .filter { it.type() == "lane" }
            .map { component ->
                LaneInfo(
                    directions = component.directions().orEmpty().toImmutableList(),
                    activeDirection = component.activeDirection(),
                    isRecommended = component.active() == true,
                )
            }
            .toImmutableList()

        val currentManeuver = _guidanceUiState.value.currentManeuver ?: return
        val nextInstruction = if (instruction.isNotEmpty()) instruction else currentManeuver.instruction

        _guidanceUiState.value = _guidanceUiState.value.copy(
            currentManeuver = currentManeuver.copy(
                instruction = nextInstruction,
                lanes = lanes,
            ),
        )
    }

    private fun dumpAllStepsForDebug(navigation: MapboxNavigation) {
        val route = navigation.getNavigationRoutes().firstOrNull() ?: return
        val directions = route.directionsRoute
        val legs = directions.legs().orEmpty()

        Napier.d(tag = TAG) {
            "=== Route: ${directions.distance().toInt()}m / ${directions.duration().toInt()}s / legs=${legs.size} ==="
        }

        legs.forEachIndexed { legIndex, leg ->
            val steps = leg.steps().orEmpty()
            Napier.d(tag = TAG) { "--- Leg $legIndex (steps=${steps.size}): ${leg.summary().orEmpty()} ---" }

            steps.forEachIndexed { stepIndex, step ->
                val maneuver = step.maneuver()
                val fields = buildList {
                    add("${maneuver.type()}${maneuver.modifier()?.let { "/$it" }.orEmpty()}")
                    add("${step.distance().toInt()}m")
                    maneuver.instruction()?.takeIf { it.isNotEmpty() }?.let { add("\"$it\"") }
                    step.name()?.takeIf { it.isNotEmpty() }?.let { add("name=$it") }
                    step.ref()?.takeIf { it.isNotEmpty() }?.let { add("ref=$it") }
                    step.destinations()?.takeIf { it.isNotEmpty() }?.let { add("dest=$it") }
                    step.exits()?.takeIf { it.isNotEmpty() }?.let { add("exitSign=$it") }
                    maneuver.exit()?.let { add("exitNo=$it") }
                }
                Napier.d(tag = TAG) { "[L$legIndex S$stepIndex] ${fields.joinToString(" | ")}" }

                step.intersections().orEmpty().forEach { intersection ->
                    val parts = buildList {
                        intersection.classes()?.takeIf { it.isNotEmpty() }?.let { add("classes=$it") }
                        intersection.tollCollection()?.let {
                            add("toll=${it.type().orEmpty()}${it.name()?.let { name -> "($name)" }.orEmpty()}")
                        }
                        intersection.restStop()?.let {
                            add("rest=${it.type().orEmpty()}${it.name()?.let { name -> "($name)" }.orEmpty()}")
                        }
                        intersection.tunnelName()?.takeIf { it.isNotEmpty() }?.let { add("tunnel=$it") }
                        if (intersection.railwayCrossing() == true) add("railwayCrossing")
                        intersection.lanes()?.takeIf { it.isNotEmpty() }?.let { lanes ->
                            val summary = lanes.joinToString(",") { lane ->
                                val indications = lane.indications().orEmpty().joinToString("+")
                                if (lane.active() == true) "[$indications]" else indications
                            }
                            add("lanes=$summary")
                        }
                    }
                    if (parts.isNotEmpty()) {
                        Napier.d(tag = TAG) { "    int: ${parts.joinToString(" | ")}" }
                    }
                }

                step.bannerInstructions().orEmpty().forEach { banner ->
                    val parts = buildList {
                        add("@${banner.distanceAlongGeometry().toInt()}m")
                        add("\"${banner.primary().text()}\"")
                        banner.secondary()?.text()?.takeIf { it.isNotEmpty() }?.let { add("sub1=\"$it\"") }
                        banner.sub()?.let { sub ->
                            sub.text().takeIf { it.isNotEmpty() }?.let { add("sub2=\"$it\"") }
                            val laneComponents = sub.components().orEmpty().filter { it.type() == "lane" }
                            if (laneComponents.isNotEmpty()) {
                                val lanes = laneComponents.joinToString(",") { component ->
                                    val directions = component.directions().orEmpty().joinToString("+")
                                    if (component.active() == true) "[$directions]" else directions
                                }
                                add("lanes=$lanes")
                            }
                        }
                    }
                    Napier.d(tag = TAG) { "    banner: ${parts.joinToString(" | ")}" }
                }

                step.voiceInstructions().orEmpty().forEach { voice ->
                    Napier.d(tag = TAG) {
                        "    voice: @${voice.distanceAlongGeometry()?.toInt()}m \"${voice.announcement().orEmpty()}\""
                    }
                }
            }
        }

        Napier.d(tag = TAG) { "=== Route dump end ===" }
    }

    companion object {
        private const val TAG = "GuidanceSessionManager"

        /** 高速道路・有料道路を示す road class 一覧。 */
        private val HIGHWAY_CLASSES = setOf("motorway", "trunk")
    }
}
