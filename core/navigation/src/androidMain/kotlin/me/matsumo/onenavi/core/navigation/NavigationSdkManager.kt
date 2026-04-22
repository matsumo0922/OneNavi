package me.matsumo.onenavi.core.navigation

import android.app.Activity
import android.app.Application
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationApi.NavigatorListener
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Navigation SDK の最小ラッパ。
 *
 * drive-supporter-api 移行後、turn-by-turn 案内は外部ナビ API 側が担うため、
 * Navigator の [Navigator.setDestinations] / [Navigator.startGuidance] は使用しない。
 *
 * 本クラスが維持する責務:
 * - 初回起動時に [NavigationApi.getNavigator] を呼び Navigator を用意する
 * - [NavigationApi.getRoadSnappedLocationProvider] を [roadSnappedLocationProvider]
 *   から購読可能にする (map-matched 自車位置の唯一のソース)
 *
 * 旧 `startNavigation` / `stopNavigation` / `continueToNextDestination` /
 * turn-by-turn feed 公開は撤去済み。
 */
class NavigationSdkManager(
    private val application: Application,
    private val routeManager: RouteManager,
) {

    @Suppress("unused") // Phase 2 以降で RouteManager と連携する可能性あり
    private val unusedRouteManager = routeManager

    private val _isNavigatorReady = MutableStateFlow(false)
    val isNavigatorReady: StateFlow<Boolean> = _isNavigatorReady.asStateFlow()

    private val _initializationErrorCode = MutableStateFlow<Int?>(null)
    val initializationErrorCode: StateFlow<Int?> = _initializationErrorCode.asStateFlow()

    private val _roadSnappedLocationProvider = MutableStateFlow<RoadSnappedLocationProvider?>(null)
    val roadSnappedLocationProvider: StateFlow<RoadSnappedLocationProvider?> =
        _roadSnappedLocationProvider.asStateFlow()

    private var navigator: Navigator? = null
    private var navigatorInitializing = false

    fun initialize(activity: Activity) {
        if (navigator != null || navigatorInitializing) return

        if (!NavigationApi.areTermsAccepted(activity.application)) {
            navigatorInitializing = true
            Napier.i(tag = TAG) { "Navigation SDK terms were not accepted. Showing terms dialog." }

            NavigationApi.showTermsAndConditionsDialog(activity, COMPANY_NAME) { accepted ->
                navigatorInitializing = false
                if (accepted) {
                    Napier.i(tag = TAG) { "Navigation SDK terms were accepted." }
                    initialize(activity)
                } else {
                    _initializationErrorCode.value = TERMS_NOT_ACCEPTED_ERROR_CODE
                    Napier.e(tag = TAG) { "Navigation SDK terms were not accepted." }
                }
            }
            return
        }

        navigatorInitializing = true
        NavigationApi.getNavigator(
            activity,
            object : NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    navigatorInitializing = false
                    this@NavigationSdkManager.navigator = navigator
                    _isNavigatorReady.value = true
                    _initializationErrorCode.value = null
                    val provider = NavigationApi.getRoadSnappedLocationProvider(application)
                    _roadSnappedLocationProvider.value = provider
                    Napier.i(tag = TAG) {
                        "[NAVDBG] onNavigatorReady: provider=${provider != null}"
                    }
                }

                override fun onError(errorCode: Int) {
                    navigatorInitializing = false
                    _initializationErrorCode.value = errorCode
                    Napier.e(tag = TAG) { "[NAVDBG] Navigator init failed. errorCode=$errorCode" }
                }
            },
        )
    }

    companion object {
        private const val TAG = "NavigationSdkManager"
        private const val COMPANY_NAME = "OneNavi"
        private const val TERMS_NOT_ACCEPTED_ERROR_CODE = -1
    }
}
