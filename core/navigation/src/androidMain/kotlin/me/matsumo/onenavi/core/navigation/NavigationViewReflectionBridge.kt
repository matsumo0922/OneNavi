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
    /**
     * 直前に upper seam に流した `to.a` を保持する。
     * `vd.f.i(newState, oldState)` の oldState 引数として再利用し、
     * SDK 内部 `tn.o.i()` の delta 計算 (新旧比較 → camera fit / panel update) を
     * 正しく成立させる。最初の注入時は null。
     */
    private var lastInjectedNavUiState: Any? = null

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
            lastInjectedNavUiState = null
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
                lastInjectedNavUiState = null
                return
            }
            runCatching {
                clearRouteOverlayLocked(clearPending = true)
                lastInjectedNavUiState = null
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
            // bo.ac の async executor を logging proxy で包む。silent な NPE を炙り出す。
            NavigationViewBridgeDiagnostics.installExecutorLogging(
                resolvedHandles.overlayController,
            )
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

        // 順序が重要:
        //  1) upper seam (tn.o.i) を先に走らせる
        //     → ManeuverPanel / ETA / camera fit など SDK 内部 UI listener が起動。
        //     → tn.o.i 内部で `bb.e.a(dVarO.G())` が呼ばれ bv.h が一旦投入される。
        //  2) その後 enhanced lower seam (bp.e[] 含む) で `bb.e.a(bv.h)` を再投入。
        //     → bo.ac.q は最新の bv.h を読むので、こちらの D() 補強済みが最終勝者になる。
        //     → tn.o.i 段階では bp.e[] 空のため bo.v.a の `for (br.av : list)` が
        //       0 回 iterate して polyline 描画されないが、再投入で arrayListE に
        //       SELECTED な bs.c が入って polyline が出るはず。
        maybeApplyNavigationUiStateLocked(routes, activeHandles)

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
            // 1.5s 後に bo.ac.U を覗いて bq.ag 配列の実体を確認する。
            // 0 → build pipeline で死亡 / >0 → render pipeline 側の問題。
            NavigationViewBridgeDiagnostics.inspectRenderState(
                activeHandles.overlayController,
            )
            // 1.7s 後に各 ov.ad に対して l() を強制 invoke。
            // bq.e.b() polyline path で `adVarO2.l()` が抜けている疑いの検証。
            NavigationViewBridgeDiagnostics.forcePolylineVisibility(
                activeHandles.overlayController,
            )
            // 1.9s 後に bo.ac.q の上流ゲート (ao Optional / be.c() travelMode)
            // を inspect。polyline body 描画 (bo.ac.m) が呼ばれない原因切り分け用。
            NavigationViewBridgeDiagnostics.inspectInjectionPipelineGating(
                activeHandles.overlayController,
            )
            // 2.2s 後に bo.ac.m() を直接 reflection 呼び出し、
            // List<zd.bf>(polyline render Future 群) の size を観測。
            NavigationViewBridgeDiagnostics.directInvokeM(
                activeHandles.overlayController,
            )
            // 2.5s 後に cq.aw を自前構築して bo.ac.v() を直接呼ぶ。
            // m() が空 List 返した場合の最終手段で polyline body 描画の force-render。
            NavigationViewBridgeDiagnostics.directInvokeV(
                activeHandles.overlayController,
            )
        }.onFailure { error -> markBrokenLocked(phase = "routeOverlay", error = error) }
    }

    private fun maybeApplyNavigationUiStateLocked(
        routes: List<GoogleRoute>,
        activeHandles: ReflectionHandles,
    ) {
        val updateMethod = activeHandles.updateUiStateMethod
        val target = activeHandles.mapController
        if (updateMethod == null || target == null) {
            Napier.w(tag = TAG) {
                "[NAVDBG] bridge.navUiState skipped: " +
                    "mapController=${target != null} method=${updateMethod != null}"
            }
            return
        }

        // tn.o.i() の polyline render block (`this.h.a(dVarO.G())`) は
        // `aw().a()` でゲートされている。これは `tn.o` 親クラス `tn.q.a` (= we.bg) の
        // `boolean a` フィールドを返すもので、本来は host-started observable
        // (ng.m<Boolean>, dj.h) の値が反映される。
        //
        // bridge attach タイミングでは NavigationView の lifecycle が host-started に
        // 入っていない (= 描画用 GL surface が完全に初期化されていない) ことがあり、
        // そのままだと render block が常時 skip されて polyline が出ない。
        // 観測したら必ず true に強制する。
        ensureHostStartedFlagLocked(target)

        runCatching {
            val newState = routeOverlayFactory.buildNavigationUiState(routes)
            // tn.o.i(newState, oldState) を直接呼ぶ。te.d.i() / vd.f.i() 経由で fan-out すると
            // ur.a (ETA panel listener) が deep proto graph を要求し落ちるため、
            // route rendering を司る tn.o (NavigationMapController) 単独に絞る。
            // oldState は前回注入分 (初回は null)。
            updateMethod.invoke(target, newState, lastInjectedNavUiState)
            lastInjectedNavUiState = newState
        }.onSuccess {
            val firstRoute = routes.first()
            val vertexCount = firstRoute.geometry.size
            Napier.i(tag = TAG) {
                "[NAVDBG] bridge.navUiState applied: routes=${routes.size} " +
                    "firstRouteVertices=$vertexCount " +
                    "firstVertex=${firstRoute.geometry.firstOrNull()} " +
                    "lastVertex=${firstRoute.geometry.lastOrNull()}"
            }
        }.onFailure { error -> markBrokenLocked(phase = "navUiState", error = error) }
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

            // te.d (uiCoordinator) は private final tn.o f を持ち、これが NavigationMapController。
            // vd.f.i() / te.d.i() 経由で全 tn.s listener に通知すると ur.a (ETA panel) などが
            // proto graph を要求し ArrayIndexOutOfBoundsException で落ちるため、
            // tn.o.i() を直接呼んで route rendering 系のみ駆動する。
            val mapController = findFieldOrNull(
                targetClass = uiCoordinator.javaClass,
                typeName = MAP_CONTROLLER_TYPE,
                fallbackName = "f",
            )?.get(uiCoordinator)
            val mapControllerUpdateMethod = if (mapController != null) {
                findMethodOrNull(
                    targetClass = mapController.javaClass,
                    methodName = "i",
                    parameterCount = 2,
                )
            } else {
                null
            }

            ReflectionHandles(
                stateController = stateController,
                uiCoordinator = uiCoordinator,
                overlayController = overlayController,
                mapController = mapController,
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
                updateUiStateMethod = mapControllerUpdateMethod,
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

    /**
     * `tn.o` 親 (`tn.q`) の `private tn.r a` field を辿って `we.bg.a` (boolean) を強制 true 化する。
     *
     * `tn.o.i()` の polyline render block は `aw().a()` (= `we.bg.a()`) でゲートされていて、
     * これは本来 `ng.m<Boolean>` (= `dj.h`, host-started observable) の値で更新される。
     * bridge attach 直後はこの observable がまだ true を emit していない場合があり、
     * そのままでは `bb.e.a(dVarO.G())` 呼び出しが skip され polyline が描画されない。
     *
     * ManeuverPanel / ETA callout はこのゲートに依存しない別経路で render されるため
     * gate が false でも見える、という非対称な状況になる。
     */
    private fun ensureHostStartedFlagLocked(mapController: Any) {
        runCatching {
            // tn.o は tn.q を継承しており、tn.q.a (private tn.r) を保持する。
            // tn.r の唯一の実装は we.bg で、その `a` フィールド (boolean) を直接書く。
            val tnQClass = mapController.javaClass.superclass ?: return
            val tnQA = tnQClass.declaredFields
                .firstOrNull { it.name == "a" }
                ?: return
            tnQA.isAccessible = true
            val tnRImpl = tnQA.get(mapController) ?: return

            val flagField = tnRImpl.javaClass.declaredFields
                .firstOrNull { it.name == "a" && it.type == java.lang.Boolean.TYPE }
                ?: return
            flagField.isAccessible = true
            val previous = flagField.getBoolean(tnRImpl)
            if (!previous) {
                flagField.setBoolean(tnRImpl, true)
            }
            Napier.i(tag = TAG) {
                "[NAVDBG] bridge.hostStartedFlag previous=$previous now=true " +
                    "impl=${tnRImpl.javaClass.name}"
            }
        }.onFailure { error ->
            // gate を上げられなくても致命ではない (polyline が出ない程度)。
            // 観測のため warn だけにする。
            Napier.w(error, tag = TAG) {
                "[NAVDBG] bridge.hostStartedFlag failed; polyline may stay hidden"
            }
        }
    }

    private fun markBrokenLocked(phase: String, error: Throwable) {
        bridgeBroken = true
        _health.value = BridgeHealth.RuntimeFailure(
            phase = phase,
            causeClass = error.javaClass.name,
            message = error.message,
        )
        Napier.e(error, tag = TAG) {
            val chain = generateSequence<Throwable>(error) { it.cause }
                .toList()
                .joinToString(separator = " -> ") { link ->
                    "${link.javaClass.simpleName}(${link.message})"
                }
            "[NAVDBG] bridge.$phase failed; bridge marked broken until next detach/attach " +
                "chain=$chain"
        }
    }

    private fun findField(
        targetClass: Class<*>,
        typeName: String,
        fallbackName: String,
    ): Field {
        return findFieldOrNull(targetClass, typeName, fallbackName)
            ?: error("Field not found. type=$typeName fallbackName=$fallbackName")
    }

    private fun findFieldOrNull(
        targetClass: Class<*>,
        typeName: String,
        fallbackName: String,
    ): Field? {
        val field = targetClass.declaredFields.firstOrNull { it.type.name == typeName }
            ?: targetClass.declaredFields.firstOrNull { it.name == fallbackName }
            ?: return null
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
        val mapController: Any?,
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
                append(", mapController=")
                append(mapController?.javaClass?.name ?: "<unresolved>")
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
        private const val MAP_CONTROLLER_TYPE =
            "com.google.android.libraries.navigation.internal.tn.o"
    }
}
