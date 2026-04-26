package me.matsumo.onenavi.core.navigation

import com.google.android.libraries.navigation.NavigationView
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.GoogleRoute
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * `NavigationViewReflectionBridge` のヘルス状態。
 *
 * UI / debug screen からは [NavigationViewReflectionBridge.health] 経由で観測する。
 * production path 上の動作分岐には使わず、観測専用とする。
 */
sealed interface BridgeHealth {
    /** kill switch (BuildKonfig) で無効化されている。bridge は完全な no-op。 */
    data object Disabled : BridgeHealth

    /** NavigationView 未 attach、または detach 済み。 */
    data object NotAttached : BridgeHealth

    /**
     * attach はされたが内部 seam の handle 解決に失敗した。
     * SDK バージョン不整合・難読化マッピング変更の疑いが強い。
     */
    data class HandlesUnresolved(
        val viewClass: String,
        val cause: String?,
    ) : BridgeHealth

    /** 全 handle 解決済み・正常動作中。 */
    data object Healthy : BridgeHealth

    /**
     * 動作中にランタイム例外が発生し、以降のセッション中は no-op に縮退している。
     * detach -> attach で復帰を試行できる。
     */
    data class RuntimeFailure(
        val phase: String,
        val causeClass: String,
        val message: String?,
    ) : BridgeHealth
}

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
 *
 * 安全装置:
 * - [enabled] = false の場合は全メソッドが no-op
 * - 任意の reflection 呼び出しが throw した時点で `bridgeBroken` を立て、
 *   そのセッション中は no-op に縮退する。既存の自前 polyline 描画は影響を受けない
 * - [detach] によって `bridgeBroken` をリセットし、次回 attach で再試行できる
 */
class NavigationViewReflectionBridge(
    private val enabled: Boolean,
) {

    private val lock = Any()
    private val routeOverlayFactory = NavigationViewSyntheticRouteOverlayFactory()

    private var attachedViewRef: WeakReference<NavigationView>? = null
    private var handles: ReflectionHandles? = null

    private var sessionRequested: Boolean = false
    private var sessionPrimed: Boolean = false
    private var pendingRoutes: List<GoogleRoute> = emptyList()
    private var bridgeBroken: Boolean = false

    private val _health = MutableStateFlow<BridgeHealth>(
        if (enabled) BridgeHealth.NotAttached else BridgeHealth.Disabled,
    )
    val health: StateFlow<BridgeHealth> = _health.asStateFlow()

    fun attach(navigationView: NavigationView) {
        if (!enabled) return
        synchronized(lock) {
            if (bridgeBroken) return
            runCatching { attachLocked(navigationView) }
                .onFailure { error -> markBrokenLocked(phase = "attach", error = error) }
        }
    }

    fun detach(navigationView: NavigationView) {
        // detach は disabled / broken 状態でも安全に呼べるようにする (idempotent)。
        // 例外は握りつぶして必ず内部 state をクリアする。
        synchronized(lock) {
            if (attachedViewRef?.get() !== navigationView) return
            attachedViewRef = null
            handles = null
            sessionPrimed = false
            sessionRequested = false
            pendingRoutes = emptyList()
            // detach は state リセットなので、次回 attach で再試行できるよう broken も降ろす。
            bridgeBroken = false
            if (enabled) {
                _health.value = BridgeHealth.NotAttached
            }
            Napier.i(tag = TAG) { "[NAVDBG] bridge.detach" }
        }
    }

    fun requestGuidanceSessionStart() {
        if (!enabled) return
        synchronized(lock) {
            if (bridgeBroken) return
            runCatching {
                sessionRequested = true
                maybePrimeSessionLocked()
                maybeApplyRouteOverlayLocked()
            }.onFailure { error -> markBrokenLocked(phase = "sessionStart", error = error) }
        }
    }

    fun requestGuidanceSessionStop() {
        if (!enabled) return
        synchronized(lock) {
            if (bridgeBroken) {
                // broken でも内部 state はクリアしておく。
                sessionRequested = false
                sessionPrimed = false
                pendingRoutes = emptyList()
                return
            }
            runCatching {
                clearRouteOverlayLocked(clearPending = true)
                sessionRequested = false
                sessionPrimed = false
                Napier.i(tag = TAG) { "[NAVDBG] bridge.stopRequested" }
            }.onFailure { error -> markBrokenLocked(phase = "sessionStop", error = error) }
        }
    }

    fun setRouteOverlayRoutes(routes: List<GoogleRoute>) {
        if (!enabled) return
        synchronized(lock) {
            if (bridgeBroken) return
            runCatching {
                pendingRoutes = routes
                maybeApplyRouteOverlayLocked()
            }.onFailure { error -> markBrokenLocked(phase = "setRoutes", error = error) }
        }
    }

    fun clearRouteOverlay() {
        if (!enabled) return
        synchronized(lock) {
            if (bridgeBroken) return
            runCatching { clearRouteOverlayLocked(clearPending = true) }
                .onFailure { error -> markBrokenLocked(phase = "clearOverlay", error = error) }
        }
    }

    private fun attachLocked(navigationView: NavigationView) {
        val current = attachedViewRef?.get()
        if (current === navigationView && handles != null) return

        attachedViewRef = WeakReference(navigationView)
        handles = resolveHandles(navigationView)
        sessionPrimed = false

        val resolvedHandles = handles
        Napier.i(tag = TAG) {
            "[NAVDBG] bridge.attach: ${resolvedHandles?.summary() ?: "handles=unresolved"}"
        }

        if (resolvedHandles == null) {
            _health.value = BridgeHealth.HandlesUnresolved(
                viewClass = navigationView.javaClass.name,
                cause = "fields or methods not resolved",
            )
        } else {
            _health.value = BridgeHealth.Healthy
        }

        maybePrimeSessionLocked()
        maybeApplyRouteOverlayLocked()
    }

    private fun maybePrimeSessionLocked() {
        if (!sessionRequested || sessionPrimed) return

        val activeHandles = ensureHandlesLocked(reason = "prime")
        if (activeHandles == null) {
            Napier.w(tag = TAG) {
                "[NAVDBG] bridge.prime skipped: handles are not ready yet"
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
        }.onFailure { error -> markBrokenLocked(phase = "prime", error = error) }
    }

    private fun maybeApplyRouteOverlayLocked() {
        if (!sessionRequested || !sessionPrimed) return

        val routes = pendingRoutes
        if (routes.isEmpty()) return

        val activeHandles = ensureHandlesLocked(reason = "routeOverlay")
        if (activeHandles == null) {
            Napier.w(tag = TAG) {
                "[NAVDBG] bridge.routeOverlay skipped: handles are not ready yet"
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
        }.onFailure { error -> markBrokenLocked(phase = "routeOverlay", error = error) }
    }

    private fun clearRouteOverlayLocked(clearPending: Boolean) {
        if (clearPending) {
            pendingRoutes = emptyList()
        }

        val activeHandles = ensureHandlesLocked(reason = "clearOverlay") ?: return

        runCatching {
            activeHandles.clearRouteOverlayMethod.invoke(activeHandles.overlayController)
        }.onSuccess {
            Napier.i(tag = TAG) { "[NAVDBG] bridge.routeOverlay cleared" }
        }.onFailure { error -> markBrokenLocked(phase = "clearOverlayInner", error = error) }
    }

    private fun ensureHandlesLocked(reason: String): ReflectionHandles? {
        val existing = handles
        if (existing != null) return existing

        val navigationView = attachedViewRef?.get() ?: return null
        val resolved = resolveHandles(navigationView)
        handles = resolved

        if (resolved != null) {
            Napier.i(tag = TAG) {
                "[NAVDBG] bridge.handles resolved on retry: reason=$reason ${resolved.summary()}"
            }
            _health.value = BridgeHealth.Healthy
        }

        return resolved
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
            val message = "[NAVDBG] bridge.resolveHandles failed: " +
                "view=${navigationView.javaClass.name} " +
                "fields=${describeDeclaredFields(navigationView)}"
            if (error is IllegalArgumentException && error.message?.endsWith("field was null") == true) {
                Napier.w(tag = TAG) { message }
            } else {
                Napier.e(error, tag = TAG) { message }
            }
            _health.value = BridgeHealth.HandlesUnresolved(
                viewClass = navigationView.javaClass.name,
                cause = "${error.javaClass.simpleName}: ${error.message}",
            )
        }.getOrNull()
    }

    private fun markBrokenLocked(phase: String, error: Throwable) {
        bridgeBroken = true
        _health.value = BridgeHealth.RuntimeFailure(
            phase = phase,
            causeClass = error.javaClass.name,
            message = error.message,
        )
        Napier.e(error, tag = TAG) {
            "[NAVDBG] bridge.$phase failed; bridge marked broken until next detach/attach"
        }
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
        var current: Class<*>? = targetClass
        while (current != null) {
            current.declaredMethods.firstOrNull { candidate ->
                candidate.name == methodName && candidate.parameterTypes.size == parameterCount
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    private fun describeDeclaredFields(target: Any): String {
        return target.javaClass.declaredFields.joinToString(
            prefix = "[",
            postfix = "]",
        ) { field ->
            runCatching {
                field.isAccessible = true
                "${field.name}:${field.type.name}=${field.get(target) != null}"
            }.getOrElse {
                "${field.name}:${field.type.name}=<inaccessible>"
            }
        }
    }

    /**
     * NavigationView 内部 seam の reflection handle 群。
     *
     * SDK バージョン依存が強いため、解決失敗時は null として扱い、
     * 上位で health=HandlesUnresolved に倒す。
     */
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
