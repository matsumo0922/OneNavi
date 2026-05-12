package me.matsumo.onenavi.core.navigation.newguidance

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState

/**
 * Guidance 期 (案内中) のマネージャ。
 *
 * 地図描画・callout・音声案内はすべて自前で行い、Google Navigation SDK の Navigator は使わない
 * (NavigationView は地図描画専用)。現状は [GuidanceState] の state machine のみを持ち、走行進捗の
 * 追跡・音声案内・リルートの実体は [me.matsumo.onenavi.core.navigation.extnav] 配下のコンポーネント
 * と接続予定。
 */
class NewGuidanceManager {

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /** 指定ルートで案内を開始する。 */
    fun startGuidance(route: RouteDetail) {
        Napier.i(tag = TAG) { "Guidance started: routeId=${route.id}" }
        _state.value = GuidanceState.Guiding
    }

    /** 案内を停止して Idle に戻す。 */
    fun stopGuidance() {
        _state.value = GuidanceState.Idle
    }

    /** Manager 破棄時に呼ぶ。 */
    fun release() {
        stopGuidance()
    }

    private companion object {
        const val TAG = "NewGuidanceManager"
    }
}
