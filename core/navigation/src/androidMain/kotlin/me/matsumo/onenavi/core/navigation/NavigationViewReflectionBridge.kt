package me.matsumo.onenavi.core.navigation

import com.google.android.libraries.navigation.NavigationView
import io.github.aakira.napier.Napier
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import me.matsumo.onenavi.core.model.GoogleRoute

/**
 * `NavigationView` の内部 seam を type-based reflection で掴むためのブリッジ。
 *
 * 現段階で担う責務:
 * - `NavigationView` から `vd.f` / `te.d` / `bb.e` を取得して保持する
 * - 外部 guidance session 開始時に `vd.f.a()` を呼び、NavigationView 側の
 *   navigation-started state を起こす
 * - synthetic route overlay (`bb.e.a(bv.h)`) を lower seam に注入する
 *
 * full UI state の注入 (`vd.f.i(...)`) は後続 POC で追加する。
 */
class NavigationViewReflectionBridge {

    private val lock = Any()
    private val routeOverlayFactory = NavigationViewSyntheticRouteOverlayFactory()

    private var attachedViewRef: WeakReference<NavigationView>? = null
    private var handles: ReflectionHandles? = null

    private var sessionRequested: Boolean = false
    private var sessionPrimed: Boolean = false
    private var pendingRoutes: List<GoogleRoute> = emptyList()

    fun attach(navigationView: NavigationView) {
        synchronized(lock) {
            val current = attachedViewRef?.get()
            if (current === navigationView && handles != null) return

            attachedViewRef = WeakReference(navigationView)
            handles = resolveHandles(navigationView)
            sessionPrimed = false

            Napier.i(tag = TAG) {
                "[NAVDBG] bridge.attach: ${handles?.summary() ?: "handles=unresolved"}"
            }

            maybePrimeSessionLocked()
            maybeApplyRouteOverlayLocked()
        }
    }

    fun detach(navigationView: NavigationView) {
        synchronized(lock) {
            if (attachedViewRef?.get() !== navigationView) return

            attachedViewRef = null
            handles = null
            sessionPrimed = false

            Napier.i(tag = TAG) { "[NAVDBG] bridge.detach" }
        }
    }

    fun requestGuidanceSessionStart() {
        synchronized(lock) {
            sessionRequested = true
            maybePrimeSessionLocked()
            maybeApplyRouteOverlayLocked()
        }
    }

    fun requestGuidanceSessionStop() {
        synchronized(lock) {
            clearRouteOverlayLocked(clearPending = true)
            sessionRequested = false
            sessionPrimed = false
            Napier.i(tag = TAG) { "[NAVDBG] bridge.stopRequested" }
        }
    }

    fun setRouteOverlayRoutes(routes: List<GoogleRoute>) {
        synchronized(lock) {
            pendingRoutes = routes
            maybeApplyRouteOverlayLocked()
        }
    }

    fun clearRouteOverlay() {
        synchronized(lock) {
            clearRouteOverlayLocked(clearPending = true)
        }
    }

    private fun maybePrimeSessionLocked() {
        if (!sessionRequested || sessionPrimed) return

        val activeHandles = handles
        if (activeHandles == null) {
            Napier.w(tag = TAG) {
                "[NAVDBG] bridge.prime skipped: NavigationView is not attached yet"
            }
            return
        }

        runCatching {
            activeHandles.startSessionMethod.invoke(activeHandles.stateController)
        }.onSuccess {
            sessionPrimed = true
            Napier.i(tag = TAG) {
                "[NAVDBG] bridge.prime succeeded: startMethod=${activeHandles.startSessionMethod.name}"
            }
            maybeApplyRouteOverlayLocked()
        }.onFailure { error ->
            Napier.e(error, tag = TAG) {
                "[NAVDBG] bridge.prime failed"
            }
        }
    }

    private fun maybeApplyRouteOverlayLocked() {
        if (!sessionRequested || !sessionPrimed) return

        val routes = pendingRoutes
        if (routes.isEmpty()) return

        val activeHandles = handles
        if (activeHandles == null) {
            Napier.w(tag = TAG) {
                "[NAVDBG] bridge.routeOverlay skipped: NavigationView is not attached yet"
            }
            return
        }

        runCatching {
            val overlayState = routeOverlayFactory.buildRouteOverlayState(routes)
            activeHandles.renderRouteOverlayMethod.invoke(
                activeHandles.overlayController,
                overlayState,
            )
        }.onSuccess {
            Napier.i(tag = TAG) {
                "[NAVDBG] bridge.routeOverlay applied: routes=${routes.size}"
            }
        }.onFailure { error ->
            Napier.e(error, tag = TAG) {
                "[NAVDBG] bridge.routeOverlay failed: routes=${routes.size}"
            }
        }
    }

    private fun clearRouteOverlayLocked(clearPending: Boolean) {
        if (clearPending) {
            pendingRoutes = emptyList()
        }

        val activeHandles = handles ?: return

        runCatching {
            activeHandles.clearRouteOverlayMethod.invoke(activeHandles.overlayController)
        }.onSuccess {
            Napier.i(tag = TAG) { "[NAVDBG] bridge.routeOverlay cleared" }
        }.onFailure { error ->
            Napier.e(error, tag = TAG) { "[NAVDBG] bridge.routeOverlay clear failed" }
        }
    }

    private fun resolveHandles(navigationView: NavigationView): ReflectionHandles? {
        return runCatching {
            val stateControllerField = findField(
                targetClass = navigationView.javaClass,
                typeName = STATE_CONTROLLER_TYPE,
                fallbackName = "m",
            )
            val uiCoordinatorField = findField(
                targetClass = navigationView.javaClass,
                typeName = UI_COORDINATOR_TYPE,
                fallbackName = "n",
            )
            val overlayControllerField = findField(
                targetClass = navigationView.javaClass,
                typeName = OVERLAY_CONTROLLER_TYPE,
                fallbackName = "p",
            )

            val stateController = requireNotNull(stateControllerField.get(navigationView)) {
                "stateController field was null"
            }
            val uiCoordinator = requireNotNull(uiCoordinatorField.get(navigationView)) {
                "uiCoordinator field was null"
            }
            val overlayController = requireNotNull(overlayControllerField.get(navigationView)) {
                "overlayController field was null"
            }

            ReflectionHandles(
                stateController = stateController,
                uiCoordinator = uiCoordinator,
                overlayController = overlayController,
                startSessionMethod = findMethod(
                    targetClass = stateController.javaClass,
                    methodName = "a",
                    parameterCount = 0,
                ),
                renderRouteOverlayMethod = findMethod(
                    targetClass = overlayController.javaClass,
                    methodName = "a",
                    parameterCount = 1,
                ),
                clearRouteOverlayMethod = findMethod(
                    targetClass = overlayController.javaClass,
                    methodName = "b",
                    parameterCount = 0,
                ),
                updateUiStateMethod = findMethodOrNull(
                    targetClass = stateController.javaClass,
                    methodName = "i",
                    parameterCount = 2,
                ),
            )
        }.onFailure { error ->
            Napier.e(error, tag = TAG) {
                "[NAVDBG] bridge.resolveHandles failed"
            }
        }.getOrNull()
    }

    private fun findField(
        targetClass: Class<*>,
        typeName: String,
        fallbackName: String,
    ): Field {
        val field = targetClass.declaredFields.firstOrNull { it.type.name == typeName }
            ?: targetClass.declaredFields.firstOrNull { it.name == fallbackName }
            ?: error("Field not found. type=$typeName fallbackName=$fallbackName")
        field.isAccessible = true
        return field
    }

    private fun findMethod(
        targetClass: Class<*>,
        methodName: String,
        parameterCount: Int,
    ): Method {
        return findMethodOrNull(
            targetClass = targetClass,
            methodName = methodName,
            parameterCount = parameterCount,
        ) ?: error(
            "Method not found. class=${targetClass.name} method=$methodName parameterCount=$parameterCount",
        )
    }

    private fun findMethodOrNull(
        targetClass: Class<*>,
        methodName: String,
        parameterCount: Int,
    ): Method? {
        val method = targetClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == methodName && candidate.parameterTypes.size == parameterCount
        } ?: return null
        method.isAccessible = true
        return method
    }

    private class ReflectionHandles(
        val stateController: Any,
        val uiCoordinator: Any,
        val overlayController: Any,
        val startSessionMethod: Method,
        val renderRouteOverlayMethod: Method,
        val clearRouteOverlayMethod: Method,
        val updateUiStateMethod: Method?,
    ) {
        fun summary(): String {
            return buildString {
                append("stateController=")
                append(stateController.javaClass.name)
                append(", uiCoordinator=")
                append(uiCoordinator.javaClass.name)
                append(", overlayController=")
                append(overlayController.javaClass.name)
                append(", updateUiState=")
                append(updateUiStateMethod != null)
            }
        }
    }

    companion object {
        private const val TAG = "NavViewBridge"

        private const val STATE_CONTROLLER_TYPE =
            "com.google.android.libraries.navigation.internal.vd.f"
        private const val UI_COORDINATOR_TYPE =
            "com.google.android.libraries.navigation.internal.te.d"
        private const val OVERLAY_CONTROLLER_TYPE =
            "com.google.android.libraries.navigation.internal.bb.e"
    }
}
