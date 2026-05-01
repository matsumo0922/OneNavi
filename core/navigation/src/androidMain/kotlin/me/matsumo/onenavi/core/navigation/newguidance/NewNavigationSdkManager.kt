package me.matsumo.onenavi.core.navigation.newguidance

import android.app.Activity
import android.app.Application
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationApi.NavigatorListener
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.navigation.newguidance.NewNavigationSdkManager.Companion.TERMS_NOT_ACCEPTED_ERROR_CODE

/**
 * spec/24 (B 案 並走) で SDK と接合する新規エントリポイント。
 *
 * 既存 [me.matsumo.onenavi.core.navigation.NavigationSdkManager] と並走し、`Navigator` の
 * インスタンスを独自に保持する。Navigation SDK の `getNavigator` は singleton 設計
 * (idempotent) なので、両方が呼んでも同じ Navigator が返る — 公式 docs で確認済み。
 *
 * 並走の要点:
 * - terms accept ダイアログは旧 [NavigationSdkManager] が担当する。本クラスは accept 済みを
 *   仮定して [initialize] を呼ぶ。未 accept 時は [initializationErrorCode] に
 *   [TERMS_NOT_ACCEPTED_ERROR_CODE] をセットして終了
 * - [Navigator.setDestinations] / [Navigator.startGuidance] 系の制御は本クラス経由で
 *   [NewGuidanceManager] が行う。旧 [NavigationSdkManager] は SDK の location provider
 *   取得しか触らない (KDoc で明示済み) ので衝突しない
 */
class NewNavigationSdkManager(
    private val application: Application,
) {
    private val _navigator = MutableStateFlow<Navigator?>(null)
    private val _roadSnappedLocationProvider = MutableStateFlow<RoadSnappedLocationProvider?>(null)
    private val _initializationErrorCode = MutableStateFlow<Int?>(null)

    val navigator = _navigator.asStateFlow()
    val roadSnappedLocationProvider = _roadSnappedLocationProvider.asStateFlow()
    val initializationErrorCode = _initializationErrorCode.asStateFlow()

    private var initializing = false

    /**
     * Navigator を初期化する。terms 未 accept のときは何もせず error code をセットする。
     * 既に初期化済み or 進行中なら何もしない (idempotent)。
     */
    fun initialize(activity: Activity) {
        if (_navigator.value != null || initializing) return

        if (!NavigationApi.areTermsAccepted(activity.application)) {
            Napier.w(tag = TAG) { "Navigation SDK terms not accepted yet; waiting for legacy manager" }
            _initializationErrorCode.value = TERMS_NOT_ACCEPTED_ERROR_CODE
            return
        }

        val listener = object : NavigatorListener {
            override fun onNavigatorReady(navigator: Navigator) {
                initializing = false

                _navigator.value = navigator
                _roadSnappedLocationProvider.value = NavigationApi.getRoadSnappedLocationProvider(application)
                _initializationErrorCode.value = null

                Napier.i(tag = TAG) { "Navigator ready (new manager)" }
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                initializing = false
                _initializationErrorCode.value = errorCode

                Napier.e(tag = TAG) { "Navigator init failed (new manager): errorCode=$errorCode" }
            }
        }

        initializing = true
        NavigationApi.getNavigator(activity, listener)
    }

    companion object {
        private const val TAG = "NewNavigationSdkManager"

        /** terms 未 accept を表すローカル error code (NavigationApi の ErrorCode と被らない負値)。 */
        const val TERMS_NOT_ACCEPTED_ERROR_CODE = -1
    }
}
