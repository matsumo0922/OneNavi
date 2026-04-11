package me.matsumo.onenavi.core.navigation.guidance

import android.content.Context
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.navigation.base.trip.model.RouteProgress
import kotlinx.coroutines.flow.StateFlow
import me.matsumo.onenavi.core.navigation.tts.AndroidTtsEngine
import me.matsumo.onenavi.core.navigation.tts.AudioFocusManager
import me.matsumo.onenavi.core.navigation.tts.SpeechOrchestrator

/**
 * Mapbox Navigation の進行イベントを構造化案内イベントに変換し、TTS へ渡す管理クラス。
 */
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
    private var pendingStartRouteId: String? = null
    private var timeoutWarningSpoken = false
    private var timedOut = false
    private var lastVoiceInstruction: VoiceInstructions? = null
    private var lastBannerInstruction: BannerInstructions? = null
    private var waypointKinds: List<DestinationKind> = emptyList()

    val isReady: StateFlow<Boolean> = ttsEngine.isReady

    var onEvent: ((GuidanceEvent) -> Unit)? = null

    init {
        ttsEngine.onReadyChanged = { ready ->
            val routeId = pendingStartRouteId
            if (ready && routeId != null) {
                pendingStartRouteId = null
                speak(coordinator.onSessionEvent(routeId, SessionGuideKind.START))
            }
        }
    }

    fun start(routeId: String) {
        currentRouteId = routeId
        startedAtMillis = System.currentTimeMillis()
        speechHistory.resetForNewRoute(routeId)
        if (ttsEngine.isReady.value) {
            speak(coordinator.onSessionEvent(routeId, SessionGuideKind.START))
        } else {
            pendingStartRouteId = routeId
        }
    }

    fun stop(routeId: String) {
        val spoken = speak(
            event = coordinator.onSessionEvent(routeId, SessionGuideKind.STOP),
            onComplete = ::shutdown,
        )
        if (!spoken) {
            shutdown()
        }
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
        checkSessionTimeout(context.routeId)
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
        pendingStartRouteId = null
        timeoutWarningSpoken = false
        timedOut = false
        speechOrchestrator.shutdown()
    }

    private fun checkSessionTimeout(routeId: String) {
        if (startedAtMillis == 0L || timedOut) return

        val elapsedMillis = System.currentTimeMillis() - startedAtMillis
        if (!timeoutWarningSpoken && elapsedMillis >= SESSION_TIMEOUT_WARNING_MILLIS) {
            timeoutWarningSpoken = true
            speak(coordinator.onSessionEvent(routeId, SessionGuideKind.TIMEOUT_WARNING))
        }
        if (elapsedMillis >= SESSION_TIMEOUT_MILLIS) {
            timedOut = true
            speak(coordinator.onSessionEvent(routeId, SessionGuideKind.TIMEOUT))
            speechOrchestrator.setMuted(muted = true, stopCurrent = false)
        }
    }

    private fun speak(
        event: GuidanceEvent,
        onComplete: (() -> Unit)? = null,
    ): Boolean {
        onEvent?.invoke(event)
        return speechOrchestrator.enqueue(
            event = event,
            text = phraseComposer.compose(event),
            onComplete = onComplete,
        )
    }

    /**
     * セッション制御で使う時間定数。
     */
    private companion object {
        private const val SESSION_TIMEOUT_MILLIS = 4 * 60 * 60 * 1000L
        private const val SESSION_TIMEOUT_WARNING_MILLIS = SESSION_TIMEOUT_MILLIS - 5 * 60 * 1000L
    }
}
