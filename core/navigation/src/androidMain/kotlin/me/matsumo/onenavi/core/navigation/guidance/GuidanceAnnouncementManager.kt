package me.matsumo.onenavi.core.navigation.guidance

import android.content.Context
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.navigation.base.trip.model.RouteProgress
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.matsumo.onenavi.core.navigation.tts.AndroidTtsEngine
import me.matsumo.onenavi.core.navigation.tts.AudioFocusManager
import me.matsumo.onenavi.core.navigation.tts.SpeechOrchestrator

class GuidanceAnnouncementManager(
    context: Context,
) {

    private val speechHistory = GuidanceSpeechHistory()
    private val contextBuilder = GuidanceContextBuilder()
    private val coordinator = GuidanceCoordinator(speechHistory)
    private val phraseComposer = JapaneseGuidancePhraseComposer()
    private val ttsEngine = AndroidTtsEngine(
        context = context,
        audioFocusManager = AudioFocusManager(context),
    )
    private val speechOrchestrator = SpeechOrchestrator(
        ttsEngine = ttsEngine,
        speechHistory = speechHistory,
    )

    private var startedAtMillis: Long = 0L
    private var currentRouteId: String = ""
    private var lastVoiceInstruction: VoiceInstructions? = null
    private var lastBannerInstruction: BannerInstructions? = null
    private var waypointKinds: List<DestinationKind> = emptyList()

    val events: SharedFlow<GuidanceEvent> = coordinator.events
    val isReady: StateFlow<Boolean> = ttsEngine.isReady

    fun start(routeId: String) {
        currentRouteId = routeId
        startedAtMillis = System.currentTimeMillis()
        speechHistory.resetForNewRoute(routeId)
        speak(coordinator.onSessionEvent(routeId, SessionGuideKind.START))
    }

    fun stop(routeId: String) {
        speak(coordinator.onSessionEvent(routeId, SessionGuideKind.STOP))
        shutdown()
    }

    fun setWaypointKinds(waypointKinds: List<DestinationKind>) {
        this.waypointKinds = waypointKinds
    }

    fun setMuted(muted: Boolean) {
        val wasMuted = speechOrchestrator.muted
        speechOrchestrator.setMuted(muted)
        if (wasMuted && !muted) {
            speak(coordinator.onSessionEvent(currentRouteId, SessionGuideKind.RESUME))
        }
    }

    fun onRouteProgress(routeProgress: RouteProgress) {
        currentRouteId = routeProgress.navigationRoute.id
        val context = contextBuilder.build(
            routeProgress = routeProgress,
            lastVoiceInstruction = lastVoiceInstruction,
            lastBannerInstruction = lastBannerInstruction,
            sessionState = GuidanceSessionState(
                startedAtMillis = startedAtMillis,
                muted = speechOrchestrator.muted,
            ),
            waypointKinds = waypointKinds,
            speechHistory = speechHistory,
        )
        coordinator.onRouteProgress(context).forEach(::speak)
    }

    fun onVoiceInstructions(voiceInstructions: VoiceInstructions) {
        lastVoiceInstruction = voiceInstructions
    }

    fun onBannerInstructions(bannerInstructions: BannerInstructions) {
        lastBannerInstruction = bannerInstructions
    }

    fun onOffRoute(isOffRoute: Boolean) {
        coordinator.onOffRoute(isOffRoute)?.let(::speak)
    }

    fun onRouteChanged(routeId: String) {
        currentRouteId = routeId
        coordinator.onRouteChanged(routeId)?.let(::speak)
    }

    fun onWaypointArrival(
        routeProgress: RouteProgress,
        finalDestination: Boolean,
    ) {
        val kind = if (finalDestination) {
            DestinationKind.DESTINATION
        } else {
            waypointKinds.getOrNull(routeProgress.currentLegProgress?.legIndex ?: 0)
                ?: DestinationKind.WAYPOINT
        }
        speak(
            coordinator.onWaypointArrival(
                routeProgress = routeProgress,
                kind = kind,
                finalDestination = finalDestination,
            ),
        )
    }

    fun shutdown() {
        contextBuilder.reset()
        coordinator.reset()
        lastVoiceInstruction = null
        lastBannerInstruction = null
        waypointKinds = emptyList()
        startedAtMillis = 0L
        currentRouteId = ""
        speechOrchestrator.shutdown()
    }

    private fun speak(event: GuidanceEvent) {
        speechOrchestrator.enqueue(
            event = event,
            text = phraseComposer.compose(event),
        )
    }

}
