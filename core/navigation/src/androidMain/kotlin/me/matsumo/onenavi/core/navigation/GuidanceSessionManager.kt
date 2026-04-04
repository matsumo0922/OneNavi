package me.matsumo.onenavi.core.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.BannerInstructionsObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.GuidanceUiState
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.TripProgressInfo
import java.util.*

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

    // --- TTS ---

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // --- 走行記録 ---

    private var sessionStartTimeMillis: Long = 0L
    private var lastRouteProgress: RouteProgress? = null

    // --- Observers ---

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        lastRouteProgress = routeProgress
        updateGuidanceUiState(routeProgress)
        cameraManager.onRouteProgressChanged(routeProgress)
        onRouteProgressForRouteLine?.invoke(routeProgress)
    }

    private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
        val announcement = voiceInstructions.announcement()
        if (announcement != null) {
            speakTts(
                generateJapaneseAnnouncement(voiceInstructions) ?: announcement,
            )
        }
    }

    private val bannerInstructionsObserver = BannerInstructionsObserver { bannerInstructions ->
        updateManeuverFromBanner(bannerInstructions)
    }

    private val offRouteObserver = OffRouteObserver { isOffRoute ->
        _guidanceUiState.value = _guidanceUiState.value.copy(isOffRoute = isOffRoute)
        if (isOffRoute) {
            Napier.d(tag = TAG) { "Off route detected, waiting for reroute..." }
        }
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onWaypointArrival(routeProgress: RouteProgress) {
            speakTts("経由地に到着しました")
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

            speakTts("目的地に到着しました")
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

        navigation.startTripSession(withForegroundService = true)

        navigation.registerRouteProgressObserver(routeProgressObserver)
        navigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
        navigation.registerBannerInstructionsObserver(bannerInstructionsObserver)
        navigation.registerOffRouteObserver(offRouteObserver)
        navigation.registerArrivalObserver(arrivalObserver)

        initializeTts()

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

        navigation.stopTripSession()

        releaseTts()

        _guidanceUiState.value = GuidanceUiState.Initial
        _arrivalInfo.value = null
        lastRouteProgress = null
        onRouteProgressForRouteLine = null
    }

    /**
     * ナビゲーション状態を Browsing に戻す（到着後 or 手動終了）。
     */
    fun returnToBrowsing() {
        _navigationState.value = NavigationState.Browsing
    }

    // --- TTS ---

    private fun initializeTts() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.JAPAN)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                _guidanceUiState.value = _guidanceUiState.value.copy(isTtsAvailable = isTtsReady)
                Napier.d(tag = TAG) { "TTS initialized: ready=$isTtsReady" }

                if (isTtsReady) {
                    speakTts(START_ANNOUNCEMENT)
                }
            } else {
                isTtsReady = false
                _guidanceUiState.value = _guidanceUiState.value.copy(isTtsAvailable = false)
                Napier.w(tag = TAG) { "TTS initialization failed: status=$status" }
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                releaseAudioFocus()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                releaseAudioFocus()
            }
        })
    }

    private fun releaseTts() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        releaseAudioFocus()
    }

    private fun speakTts(text: String) {
        if (!isTtsReady) return

        requestAudioFocus()

        tts?.stop()
        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UUID.randomUUID().toString(),
        )
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audioFocusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun releaseAudioFocus() {
        val manager = audioManager ?: return
        audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
    }

    // --- 日本語テンプレート ---

    private fun generateJapaneseAnnouncement(
        voiceInstructions: VoiceInstructions,
    ): String? = JapaneseAnnouncementGenerator.generate(voiceInstructions)

    // --- RouteProgress → GuidanceUiState ---

    private fun updateGuidanceUiState(routeProgress: RouteProgress) {
        val currentLegProgress = routeProgress.currentLegProgress
        val currentStepProgress = currentLegProgress?.currentStepProgress
        val currentStep = currentStepProgress?.step

        val currentManeuver = currentStep?.maneuver()?.let { maneuver ->
            ManeuverInfo(
                type = maneuver.type().orEmpty(),
                modifier = maneuver.modifier(),
                distanceMeters = currentStepProgress.distanceRemaining.toDouble(),
                instruction = currentStep.name().orEmpty(),
            )
        }

        val nextStep = currentLegProgress?.upcomingStep
        val nextManeuver = nextStep?.maneuver()?.let { maneuver ->
            ManeuverInfo(
                type = maneuver.type().orEmpty(),
                modifier = maneuver.modifier(),
                distanceMeters = nextStep.distance(),
                instruction = nextStep.name().orEmpty(),
            )
        }

        val tripProgress = TripProgressInfo(
            distanceRemainingMeters = routeProgress.distanceRemaining.toDouble(),
            durationRemainingSeconds = routeProgress.durationRemaining,
            estimatedArrivalTimeMillis = System.currentTimeMillis() + (routeProgress.durationRemaining * 1000).toLong(),
        )

        val currentRoadName = currentStep?.name()?.takeIf { it.isNotBlank() }

        _guidanceUiState.value = _guidanceUiState.value.copy(
            currentManeuver = currentManeuver,
            nextManeuver = nextManeuver,
            tripProgress = tripProgress,
            currentRoadName = currentRoadName,
            isLocationStale = routeProgress.stale,
        )
    }

    private fun updateManeuverFromBanner(bannerInstructions: BannerInstructions) {
        val primary = bannerInstructions.primary()
        val instruction = primary.text()

        val currentManeuver = _guidanceUiState.value.currentManeuver
        if (currentManeuver != null && instruction.isNotEmpty()) {
            _guidanceUiState.value = _guidanceUiState.value.copy(
                currentManeuver = currentManeuver.copy(instruction = instruction),
            )
        }
    }

    companion object {
        private const val TAG = "GuidanceSessionManager"
        private const val START_ANNOUNCEMENT = "音声案内を開始します。実際の交通規制に従って、走行してください。"
    }
}
