package me.matsumo.onenavi.core.navigation

import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.RoutePoint
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * `NavigationView` の seam に渡す synthetic state を組み立てる。
 *
 * - lower seam (`bb.e.a(bv.h)`): [buildRouteOverlayState] で `bv.h` を組み立て、
 *   route polyline 描画パイプラインに直接ルートを流し込む
 * - upper seam (`vd.f.i(to.a, to.a)`): [buildNavigationUiState] で `to.a` を
 *   組み立て、SDK 内部の全 `tn.s` listener を駆動する (route line に加えて
 *   ManeuverPanel / ETA / callout / camera fit 等も SDK 自身に処理させる)
 */
internal class NavigationViewSyntheticRouteOverlayFactory {

    private val classCache = ConcurrentHashMap<String, Class<*>>()

    private data class RouteArtifacts(
        val azRoute: Any,
        val syntheticStep: Any,
    )

    fun buildRouteOverlayState(routes: List<GoogleRoute>): Any {
        require(routes.isNotEmpty()) { "routes must not be empty" }

        val artifacts = routes.map(::buildRouteArtifacts)
        val routeList = requireNotNull(
            invokeStatic(
                className = ROUTE_LIST_CLASS,
                methodName = "f",
                args = arrayOf(0, artifacts.map { it.azRoute }),
            ),
        ) { "RouteList factory returned null" }

        val builder = requireNotNull(
            invokeStatic(
                className = OVERLAY_STATE_CLASS,
                methodName = "O",
                args = emptyArray<Any>(),
            ),
        ) { "Overlay state builder returned null" }

        // bv.h.O() のデフォルトに加え、SDK 純正 caller (`tn.o.i()` 内 dVarO 構築) を
        // ほぼ完全に模倣する。これらが揃わないと bo.ac.q -> bo.v.a -> bq.ag pipeline 内で
        // 「route が selected として認識されない」「DRIVE モードでない」等の理由で
        // polyline 描画ジョブが skip される。
        //
        // 特に重要なのは D(yb.er<bp.e>):
        //   bo.ac.q は `((bv.b) hVar).b.get(i)` で bp.e (= SELECTED_WITH_TRAFFIC 等) を取得し、
        //   eVar.e == false なら bs.c (br.av impl) を arrayListE に add しない。
        //   その結果 bs.a.a が空 → bo.v.a の `for (br.av : list)` が 0 回 iterate
        //   → bq.ag が一切作られず polyline 描画されない。
        //   bv.h.O() default は D(lr.a) (空 er) で、tn.o.i() も D() を呼ばないため、
        //   このゲートは外部からこちらで埋める必要がある。
        invoke(builder, "u", routeList)
        invoke(builder, "C", getStaticField(TEXTURE_CONFIG_CLASS, "a"))
        invoke(builder, "k", buildShowAllSupplier())
        invoke(builder, "l", buildTimeSupplier())
        invoke(builder, "D", buildSelectedRouteEnumList(routes.size))
        invoke(builder, "m", buildEmptyImmutableList())
        invoke(builder, "s", getEnumConstant(BV_E_CLASS, "FIRST_DESTINATION"))
        invoke(builder, "r", getStaticField(OPTIONAL_ABSENT_CLASS, "a"))
        invoke(builder, "x", true)
        invoke(builder, "y", true)
        invoke(builder, "z", true)
        invoke(builder, "q", true)
        invoke(builder, "p", true)
        invoke(builder, "v", true)
        invoke(builder, "A", false)
        invoke(builder, "B", true)
        // 直接フィールド書き込み:
        // ((bv.a) dVarO).b = blVar (current step, headerStep)
        // ((bv.a) dVarO).h = anVar (Optional, polylineOverride 由来)
        // tn.o.i() は両方を直接代入していて setter は存在しない。
        setField(builder, "b", artifacts.first().syntheticStep)
        setField(builder, "h", getStaticField(OPTIONAL_ABSENT_CLASS, "a"))

        return requireNotNull(invoke(builder, "G")) {
            "Overlay state builder.G() returned null"
        }
    }

    private fun buildTimeSupplier(): Any {
        // xz.bv(cr.bc.TIME) — bv.h.O() がデフォルトで設定するもの (l setter, field B)。
        // mirror 完全性のため再生成して上書きする。
        return instantiate(
            className = SUPPLIER_OF_INSTANCE_CLASS,
            args = arrayOf(getEnumConstant(ROUTE_DETAIL_CLASS, "TIME")),
        )
    }

    private fun buildEmptyImmutableList(): Any {
        return getStaticField(EMPTY_IMMUTABLE_LIST_CLASS, "a")
    }

    /**
     * `bp.e.SELECTED_WITH_TRAFFIC` を 1 つ + 残りを `UNSELECTED_WITH_TRAFFIC` で埋めた
     * `er<bp.e>` を返す。selected index 0 を前提としている (be.f(0, ...) 側と整合)。
     */
    private fun buildSelectedRouteEnumList(routeCount: Int): Any {
        val selected = getEnumConstant(ROUTE_TEXTURE_TYPE_CLASS, "SELECTED_WITH_TRAFFIC")
        val unselected = getEnumConstant(ROUTE_TEXTURE_TYPE_CLASS, "UNSELECTED_WITH_TRAFFIC")
        val items = List(routeCount) { index ->
            if (index == 0) selected else unselected
        }
        return requireNotNull(
            invokeStatic(
                className = IMMUTABLE_LIST_CLASS,
                methodName = "p",
                args = arrayOf(items),
            ),
        ) { "ImmutableList.copyOf returned null for bp.e" }
    }

    /**
     * Upper seam (`vd.f.i(to.a, to.a)`) に渡す `to.a` を組み立てる。
     *
     * SDK 内部 caller (`tn.aa.n()`) と等価な構造を反射で再現することで、
     * `te.d.i()` 経由で `tn.o.i()` (NavigationMapController) を含む全
     * `tn.s` listener を駆動する。これにより lower seam 単独では届かない
     * ManeuverPanel / ETA / callout / camera fit 等の UI 要素も SDK 側の
     * 描画ロジックがカバーしてくれる。
     *
     * グラフ:
     *   to.a (= sm.h navState を持つ UI state)
     *     └ sm.h (sm.g builder 経由)
     *         └ sm.m (sm.l builder 経由)
     *             ├ br.be routes container (= [buildRouteOverlayState] と同じ az[])
     *             └ rf.b[] navGuidanceStates (各 az に 1 対 1 対応)
     *                 └ rf.b.b = az / .c = synthetic bl (current step) / .k = true (isOnRoute)
     */
    fun buildNavigationUiState(routes: List<GoogleRoute>): Any {
        require(routes.isNotEmpty()) { "routes must not be empty" }

        val artifacts = routes.map(::buildRouteArtifacts)
        val routeContainer = requireNotNull(
            invokeStatic(
                className = ROUTE_LIST_CLASS,
                methodName = "f",
                args = arrayOf(0, artifacts.map { it.azRoute }),
            ),
        ) { "RouteList factory returned null" }

        val navStatesArrayClass = declaredClass(NAV_GUIDANCE_STATE_CLASS)
        val navStatesArray = java.lang.reflect.Array.newInstance(navStatesArrayClass, artifacts.size)
        artifacts.forEachIndexed { index, artifact ->
            java.lang.reflect.Array.set(navStatesArray, index, buildNavGuidanceState(artifact))
        }

        val smL = instantiate(SM_L_CLASS, emptyArray<Any>())
        setField(smL, "a", routeContainer)
        setField(smL, "b", navStatesArray)
        // c = -1 (betterRouteIndex), d = Long.MAX_VALUE (nextGuidanceTime), e = null (acn.ko) は
        // sm.l のフィールド初期化子で十分。

        val smM = instantiate(SM_M_CLASS, arrayOf(smL))

        val smG = instantiate(SM_G_CLASS, emptyArray<Any>())
        setField(smG, "j", smM)
        // a = null (dh.o myLocation), b = null (currentRoadName), c = true (dataConnectionReady),
        // d = false (gpsReady) は sm.c のフィールド初期化子で十分。
        // e〜i (boolean group) も sm.g のデフォルト false で問題ない。

        val smH = instantiate(SM_H_CLASS, arrayOf(smG))

        val toABuilder = instantiate(TO_A_BUILDER_CLASS, emptyArray<Any>())
        // c() は sm.h を builder.i にセットし、to.b (preserved instance state) が
        // null の場合はそのまま return する。p (to.b) を渡さない設計なので問題ない。
        invoke(toABuilder, "c", smH)
        return requireNotNull(invoke(toABuilder, "a")) {
            "to.a builder.a() returned null"
        }
    }

    private fun shiftRouteForVerification(
        route: GoogleRoute,
        longitudeShift: Double,
    ): GoogleRoute {
        // 進行方向の真横にズラすため経度方向にシフトする。
        // 緯度シフトだと進行方向 (= 視野奥) にズレて 3D pitch 表示で見えなくなることがある。
        fun RoutePoint.shifted() =
            copy(longitude = longitude + longitudeShift)
        return route.copy(
            origin = route.origin.shifted(),
            destination = route.destination.shifted(),
            geometry = route.geometry.map { it.shifted() }.toImmutableList(),
        )
    }

    private fun buildShowAllSupplier(): Any {
        // xz.bv(cr.bb.SHOW_ALL) — Suppliers.ofInstance(SHOW_ALL) 相当。
        // bv.a の field A (xz.br = Supplier) を埋める。
        return instantiate(
            className = SUPPLIER_OF_INSTANCE_CLASS,
            args = arrayOf(getEnumConstant(ROUTE_VISIBILITY_CLASS, "SHOW_ALL")),
        )
    }

    private fun buildRouteArtifacts(originalRoute: GoogleRoute): RouteArtifacts {
        // 動作確認時は VERIFICATION_LONGITUDE_SHIFT_DEGREES != 0.0 にして、
        // 注入経路の polyline と自前 polygon を視覚的に分離する。
        // 確認後は必ず 0.0 に戻すこと。
        val route = if (VERIFICATION_LONGITUDE_SHIFT_DEGREES != 0.0) {
            shiftRouteForVerification(originalRoute, VERIFICATION_LONGITUDE_SHIFT_DEGREES)
        } else {
            originalRoute
        }

        val polylinePoints = route.geometry.ifEmpty {
            listOf(route.origin, route.destination)
        }
        val syntheticStep = buildSyntheticStep(
            destination = polylinePoints.last(),
            lastVertexIndex = polylinePoints.lastIndex,
        )
        val azRoute = buildAzRoute(route, polylinePoints, syntheticStep)

        return RouteArtifacts(azRoute = azRoute, syntheticStep = syntheticStep)
    }

    private fun buildAzRoute(
        route: GoogleRoute,
        polylinePoints: List<RoutePoint>,
        syntheticStep: Any,
    ): Any {
        val ca = instantiate(
            className = CA_CLASS,
            args = arrayOf(
                buildKgWithOneLeg(),
                false,
            ),
        )
        val aw = instantiate(
            className = AW_CLASS,
            args = arrayOf(ca),
        )

        setField(aw, "i", buildPolylineFromPoints(polylinePoints))
        setField(aw, "e", getEnumConstant(ROUTE_MODE_CLASS, "DRIVE"))
        setField(aw, "k", route.id)
        setField(aw, "C", Collections.emptyList<Any>())
        setField(aw, "D", buildMinimalIj())
        setField(aw, "f", buildWaypointList(route, includeOrigin = true))
        setField(aw, "g", buildWaypointList(route, includeOrigin = false))
        // aw.c は az.u (volatile bh) に伝播し、cr.e.a が `((br.m) az.u).a` で読み取るため
        // 空の br.m を設定しないと downstream で NPE になる。
        setField(aw, "c", buildEmptyTrafficData())
        // aw.z は az.M (hv proto) に伝播し、az.W() などで `M.a` (bitmask) が読まれる。
        // proto の default singleton (hv.w) を入れて null 参照を回避する。
        setField(aw, "z", getStaticField(GUIDANCE_TRIP_PROTO_CLASS, "w"))
        // aw.m は az.E (br.ab abstract) に伝播し、`cq.ay.b(az)` が
        // `azVar.E.d().toSeconds()` を呼ぶ (m() の leg-loop で `aVarB.h(cq.ay.b(azVar))` 内)。
        // br.j(Duration a, Duration b) を a=ZERO, b=null で埋めて `d()=a=ZERO` で 0 秒返す。
        setField(aw, "m", instantiate(BR_J_CLASS, arrayOf(java.time.Duration.ZERO, java.time.Duration.ZERO)))
        // bo.v.a (DRIVE/TWO_WHEELER 経路) は azVar.l().length == azVar.T() - 1 を要求する。
        // azVar.l() は this.i (= aw.h) を bl.d == zo.l.DESTINATION でフィルタした結果。
        // 2 waypoints (origin + destination) なら 1 step が必要。
        // upper seam 側でも同じ step instance を rf.b.c (current step) として共有する必要があるため、
        // 単一インスタンスを array にラップしてからセットする。
        val stepArrayClass = declaredClass(STEP_CLASS)
        val stepArray = java.lang.reflect.Array.newInstance(stepArrayClass, 1)
        java.lang.reflect.Array.set(stepArray, 0, syntheticStep)
        setField(aw, "h", stepArray)

        return instantiate(
            className = AZ_CLASS,
            args = arrayOf(aw),
        )
    }

    /**
     * `rf.b` (NavGuidanceState) を 1 route 分組み立てる。
     *
     * - `rf.b.b` = 対応する `az` route
     * - `rf.b.c` = 同 route の `az.i[0]` と同一インスタンスの synthetic bl
     *   (`az.U(bl)` identity check が要求するため)
     * - `rf.b.k` = true (isOnRoute) → `sm.h.b()` が「メッセージ表示中」と判定
     *   しないようにする
     * - その他 ab フィールド (i, j) は `rf.a` のコンストラクタが
     *   Duration.ofSeconds(-1) ベースで埋めてくれる
     */
    private fun buildNavGuidanceState(artifacts: RouteArtifacts): Any {
        val rfA = instantiate(
            className = NAV_GUIDANCE_BUILDER_CLASS,
            args = arrayOf(artifacts.azRoute),
        )
        setField(rfA, "b", artifacts.syntheticStep)
        // c (rerouting bool) = false default。
        // d/e/f/g/h (各種 int = -1) はビルダー初期化子で OK。
        // k (isOnRoute) を true にしないと sm.h 経由の各種判定が「経路逸脱」扱いになる。
        findField(rfA.javaClass, "k").setBoolean(rfA, true)
        // l (routeCompletedSuccessfully) = false default。
        // m (dh.v location) = null。location なしでも descent パイプラインが許容する想定。

        return instantiate(
            className = NAV_GUIDANCE_STATE_CLASS,
            args = arrayOf(rfA),
        )
    }

    private fun buildPolylineFromPoints(points: List<me.matsumo.onenavi.core.model.RoutePoint>): Any {
        // 各 vertex を Mercator 投影された mapcore.z に変換する (詳細は buildMapcorePoint)。
        val mapcorePoints = points.map(::buildMapcorePoint)

        val encodedPoints = requireNotNull(
            invokeStatic(
                className = IMMUTABLE_LIST_CLASS,
                methodName = "p",
                args = arrayOf(mapcorePoints),
            ),
        ) { "ImmutableList.copyOf returned null" }

        return requireNotNull(
            invokeStatic(
                className = MAPCORE_POLYLINE_CLASS,
                methodName = "b",
                args = arrayOf(encodedPoints),
            ),
        ) { "Polyline factory returned null" }
    }

    /**
     * `br.bl` (step) を `br.bk` builder 経由で正規に構築する。
     *
     * 当初は `Unsafe.allocateInstance` でコンストラクタを skip し最小フィールドだけ埋めて
     * いたが、upper seam (`vd.f.i` → `te.d.i` → `ur.a.i` → `tp.c.b` → `tp.a.<init>` →
     * `tp.e.<init>` → `tp.a.o`) が `bl.p` (`SpannableString`) など bk ctor でしか
     * 初期化されない field を `.toString()` で読むため、null 参照で NPE する。
     *
     * bk ctor は `q/r/s/t/u/E/F/H` を `lr.a` (空 ImmutableList) で初期化するため
     * 呼び出し側で個別に埋める必要はない。bl ctor 側で `xz.ar.q(...)` 必須なのは:
     *   a (zo.l), b (acn.hq), c (acn.hs), f (mapcore.z), i (String) と各 List 群。
     */
    private fun buildSyntheticStep(
        destination: RoutePoint,
        lastVertexIndex: Int,
    ): Any {
        val bk = instantiate(STEP_BUILDER_CLASS, emptyArray<Any>())
        // a: zo.l — bo.v.a (decoration pipeline) の `xz.ar.a(blVarArrL.length == azVar.T()-1)`
        // assertion (line 125) が DESTINATION step 必須。az.l() は bl.d == DESTINATION のみ通す。
        // bo.ac.m() (polyline body pipeline) も `azVar.j() != null` 経路に入るため、
        // kg.g に最低 1 entry の gh leg を持たせて asVarArr.length>=1 にする
        // (buildKgWithOneLeg を参照)。
        setField(bk, "a", getEnumConstant(MANEUVER_CLASS, "DESTINATION"))
        // b/c は実際の guidance では右左折情報。今回は表示用ダミーで unspecified/unknown を入れる。
        setField(bk, "b", getEnumConstant(MANEUVER_SIDE_CLASS, "SIDE_UNSPECIFIED"))
        setField(bk, "c", getEnumConstant(MANEUVER_TURN_CLASS, "TURN_UNKNOWN"))
        // f: mapcore.z — destination の lat/lng。bl.c に伝播し各種 marker placement で使われる。
        setField(bk, "f", buildMapcorePoint(destination))
        // i: String — bl.j (description) になり、bl ctor 内で SpannableString(str) として bl.p を作る。
        // 空文字でも SpannableString("") は valid (length 0)。
        setField(bk, "i", "")
        // h: int → bl.k = polyline vertex index。bo.v.a の destination marker placement が読む。
        setIntField(bk, "h", lastVertexIndex)
        // g: int → bl.i = step index。az.U(bl) の identity check で `az.i[bl.i] === bl` 比較に使われる。
        // 単一 step なので 0 (bk.g は int = 0 default で OK だが明示する)。
        setIntField(bk, "g", 0)

        return instantiate(STEP_CLASS, arrayOf(bk))
    }

    private fun buildMapcorePoint(point: RoutePoint): Any {
        // mapcore.z は Mercator 投影された int 座標 (a=mercator x, b=mercator y) を保持する。
        // `z(int, int)` コンストラクタを直接呼ぶと値が無加工で a/b に入り、レンダラ側で
        // 完全に座標系が狂う (polyline が画面外として culled される)。
        // 正しいルート: `z.c(double lat, double lng)` static factory が `s()` → `u()` で
        // Mercator 変換した上で `q(int, int)` を呼んで a/b に設定する。
        return requireNotNull(
            invokeStatic(
                className = MAPCORE_POINT_CLASS,
                methodName = "c",
                args = arrayOf(point.latitude, point.longitude),
            ),
        ) { "mapcore.z.c(lat, lng) returned null" }
    }

    /**
     * `br.m` (= bh の concrete) の minimal インスタンス。
     * `aw.c` -> `az.u` に伝播し、`cr.e.a` から `.a` (er) が読まれる。
     */
    /**
     * 最小構成の `acn.kg` (DirectionsTrip proto) を生成する。
     *
     * default singleton `kg.A` は `g` (= List<gh> legs) が empty bz のため、
     * `br.ca` ctor で `b = new br.as[0]` となり `bo.ac.m()` の leg-loop が
     * 0 iterate に終わって polyline body 用の `cq.aw` が一切生成されない。
     *
     * ここでは
     *   1. private kg() ctor を reflection で叩いて新 instance を作成
     *   2. `kg.c()` を呼んで `g` を mutable bz に差し替え (= `bi.F(g)`)
     *   3. private gh() ctor で default-constructed gh (`gh.a == 0`) を 1 個作って g.add
     *
     * gh.a == 0 なので `br.as` ctor の bit-check (16/32/128 等) は全て false に転び、
     * `as.b()` も false で `bo.ac.m()` line 970 が `alVarB = azVar.g (=DRIVE)` 経路に倒れる。
     * `as.a()` は null check されて `id.m` default にフォールバックする。
     *
     * これで `kg.g.size()==1`, `asVarArr.length==1`, `listP.get(0).d() = aiVar slice` で
     * `cq.aw.b(aiVarD, DRIVE)` が呼ばれて 1 本の polyline aw が ayVar.i に積まれる。
     *
     * az ctor (`br/az.java` 149) のアサーション `d.e() == m.size() - 1` は
     * d.e()=1, m.size()=2 (origin+dest waypoints) で 1==1 で成立する。
     */
    private fun buildKgWithOneLeg(): Any {
        val kgInstance = instantiate(KG_CLASS, emptyArray<Any>())
        // kg.g (= dj.a immutable singleton) を `bi.F(g)` で mutable copy に差し替える。
        invoke(kgInstance, "c")
        val ghInstance = instantiate(GH_CLASS, emptyArray<Any>())
        @Suppress("UNCHECKED_CAST")
        val mutableLegs = findField(kgInstance.javaClass, "g")
            .get(kgInstance) as MutableList<Any>
        mutableLegs.add(ghInstance)
        return kgInstance
    }

    private fun buildEmptyTrafficData(): Any {
        val emptyEr = getStaticField(EMPTY_IMMUTABLE_LIST_CLASS, "a")
        val emptyEz = getStaticField(EMPTY_IMMUTABLE_MAP_CLASS, "b")
        return instantiate(
            className = TRAFFIC_DATA_CLASS,
            args = arrayOf(emptyEr, emptyEr, emptyEz),
        )
    }

    private fun buildMinimalIj(): Any {
        val hp = instantiate(
            className = HP_CLASS,
            args = emptyArray<Any>(),
        )
        setIntField(hp, "a", 1)

        val ij = instantiate(
            className = IJ_CLASS,
            args = emptyArray<Any>(),
        )
        setIntField(ij, "a", 1)
        setField(ij, "b", hp)

        return ij
    }

    private fun buildWaypointList(
        route: GoogleRoute,
        includeOrigin: Boolean,
    ): Any {
        val waypoints = buildList {
            if (includeOrigin) {
                add(buildWaypoint(route.origin.latitude, route.origin.longitude, route.id))
            }
            add(buildWaypoint(route.destination.latitude, route.destination.longitude, route.id))
        }
        return requireNotNull(
            invokeStatic(
                className = IMMUTABLE_LIST_CLASS,
                methodName = "p",
                args = arrayOf(waypoints),
            ),
        ) { "Waypoint ImmutableList returned null" }
    }

    private fun buildWaypoint(
        latitude: Double,
        longitude: Double,
        label: String,
    ): Any {
        val latLng = instantiate(
            className = MAPCORE_LATLNG_CLASS,
            args = arrayOf(latitude, longitude),
        )
        return requireNotNull(
            invokeStatic(
                className = WAYPOINT_CLASS,
                methodName = "M",
                args = arrayOf(label, latLng),
            ),
        ) { "Waypoint factory returned null" }
    }

    private fun instantiate(
        className: String,
        args: Array<Any>,
    ): Any {
        val constructor = declaredClass(className)
            .declaredConstructors
            .firstOrNull { it.parameterCount == args.size }
            ?: error("Constructor not found: class=$className args=${args.size}")
        constructor.isAccessible = true
        return runCatching {
            (constructor as Constructor<Any>).newInstance(*args)
        }.getOrElse { error ->
            if (error is InvocationTargetException) {
                throw IllegalStateException(
                    "Constructor failed: class=$className cause=${error.targetException.javaClass.name}: ${error.targetException.message}",
                    error.targetException,
                )
            }
            throw error
        }
    }

    private fun invoke(
        target: Any,
        methodName: String,
        vararg args: Any,
    ): Any? {
        val method = findMethod(
            targetClass = target.javaClass,
            methodName = methodName,
            parameterCount = args.size,
        )
        return runCatching {
            method.invoke(target, *args)
        }.getOrElse { error ->
            throw unwrapInvocationError(
                description = "instance call ${target.javaClass.name}#$methodName",
                error = error,
            )
        }
    }

    private fun invokeStatic(
        className: String,
        methodName: String,
        args: Array<Any>,
    ): Any? {
        val method = findMethod(
            targetClass = declaredClass(className),
            methodName = methodName,
            parameterCount = args.size,
        )
        return runCatching {
            method.invoke(null, *args)
        }.getOrElse { error ->
            throw unwrapInvocationError(
                description = "static call $className#$methodName",
                error = error,
            )
        }
    }

    private fun unwrapInvocationError(
        description: String,
        error: Throwable,
    ): Throwable {
        val cause = if (error is InvocationTargetException) {
            error.targetException ?: error
        } else {
            error
        }
        return IllegalStateException(
            "$description failed: ${cause.javaClass.name}: ${cause.message}",
            cause,
        )
    }

    private fun getStaticField(
        className: String,
        fieldName: String,
    ): Any {
        return requireNotNull(
            findField(
                targetClass = declaredClass(className),
                fieldName = fieldName,
            ).get(null),
        ) { "Static field is null: $className#$fieldName" }
    }

    private fun getEnumConstant(
        className: String,
        constantName: String,
    ): Any {
        val enumClass = declaredClass(className)
        require(enumClass.isEnum) { "Class is not enum: $className" }
        val constants = requireNotNull(enumClass.enumConstants) {
            "Enum class has no constants: $className"
        }
        return constants.firstOrNull { constant ->
            (constant as Enum<*>).name == constantName
        } ?: error("Enum constant not found: class=$className constant=$constantName")
    }

    private fun setField(
        target: Any,
        fieldName: String,
        value: Any,
    ) {
        findField(
            targetClass = target.javaClass,
            fieldName = fieldName,
        ).set(target, value)
    }

    private fun setIntField(
        target: Any,
        fieldName: String,
        value: Int,
    ) {
        findField(
            targetClass = target.javaClass,
            fieldName = fieldName,
        ).setInt(target, value)
    }

    private fun findField(
        targetClass: Class<*>,
        fieldName: String,
    ): Field {
        var current: Class<*>? = targetClass
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        error("Field not found: class=${targetClass.name} field=$fieldName")
    }

    private fun findMethod(
        targetClass: Class<*>,
        methodName: String,
        parameterCount: Int,
    ): Method {
        val method = targetClass.methods.firstOrNull { candidate ->
            candidate.name == methodName && candidate.parameterTypes.size == parameterCount
        } ?: targetClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == methodName && candidate.parameterTypes.size == parameterCount
        } ?: error(
            "Method not found: class=${targetClass.name} method=$methodName args=$parameterCount",
        )
        method.isAccessible = true
        return method
    }

    private fun declaredClass(className: String): Class<*> {
        return classCache.getOrPut(className) { Class.forName(className) }
    }

    companion object {
        /**
         * 動作確認用: bridge 経由で注入する route 全体をこの度数だけ東西にシフトする。
         *
         * 0.0 で無効。0.001 で約 80m (日本付近)、0.005 で約 400m シフト。
         * 自前 polyline と bridge 経由 polyline を真横に並べて視覚的に区別するためのデバッグ用。
         * **本番では必ず 0.0 に戻すこと。**
         */
        private const val VERIFICATION_LONGITUDE_SHIFT_DEGREES = 0.0

        private const val CA_CLASS =
            "com.google.android.libraries.navigation.internal.br.ca"
        private const val AW_CLASS =
            "com.google.android.libraries.navigation.internal.br.aw"
        private const val AZ_CLASS =
            "com.google.android.libraries.navigation.internal.br.az"
        private const val ROUTE_LIST_CLASS =
            "com.google.android.libraries.navigation.internal.br.be"
        private const val OVERLAY_STATE_CLASS =
            "com.google.android.libraries.navigation.internal.bv.h"
        private const val TEXTURE_CONFIG_CLASS =
            "com.google.android.libraries.navigation.internal.bs.d"
        private const val MAPCORE_POINT_CLASS =
            "com.google.android.libraries.geo.mapcore.api.model.z"
        private const val MAPCORE_LATLNG_CLASS =
            "com.google.android.libraries.geo.mapcore.api.model.r"
        private const val MAPCORE_POLYLINE_CLASS =
            "com.google.android.libraries.geo.mapcore.api.model.ai"
        private const val IMMUTABLE_LIST_CLASS =
            "com.google.android.libraries.navigation.internal.yb.er"
        private const val KG_CLASS =
            "com.google.android.libraries.navigation.internal.acn.kg"
        private const val GH_CLASS =
            "com.google.android.libraries.navigation.internal.acn.gh"
        private const val BR_J_CLASS =
            "com.google.android.libraries.navigation.internal.br.j"
        private const val IJ_CLASS =
            "com.google.android.libraries.navigation.internal.aee.ij"
        private const val HP_CLASS =
            "com.google.android.libraries.navigation.internal.aee.hp"
        private const val ROUTE_MODE_CLASS =
            "com.google.android.libraries.navigation.internal.acu.al"
        private const val WAYPOINT_CLASS =
            "com.google.android.libraries.navigation.internal.br.ce"
        private const val SUPPLIER_OF_INSTANCE_CLASS =
            "com.google.android.libraries.navigation.internal.xz.bv"
        private const val ROUTE_VISIBILITY_CLASS =
            "com.google.android.libraries.navigation.internal.cr.bb"
        private const val ROUTE_DETAIL_CLASS =
            "com.google.android.libraries.navigation.internal.cr.bc"
        private const val ROUTE_TEXTURE_TYPE_CLASS =
            "com.google.android.libraries.navigation.internal.bp.e"
        private const val BV_E_CLASS =
            "com.google.android.libraries.navigation.internal.bv.e"
        private const val OPTIONAL_ABSENT_CLASS =
            "com.google.android.libraries.navigation.internal.xz.a"
        private const val STEP_CLASS =
            "com.google.android.libraries.navigation.internal.br.bl"
        private const val STEP_BUILDER_CLASS =
            "com.google.android.libraries.navigation.internal.br.bk"
        private const val MANEUVER_CLASS =
            "com.google.android.libraries.navigation.internal.zo.l"
        private const val MANEUVER_SIDE_CLASS =
            "com.google.android.libraries.navigation.internal.acn.hq"
        private const val MANEUVER_TURN_CLASS =
            "com.google.android.libraries.navigation.internal.acn.hs"
        private const val EMPTY_IMMUTABLE_LIST_CLASS =
            "com.google.android.libraries.navigation.internal.yb.lr"
        private const val EMPTY_IMMUTABLE_MAP_CLASS =
            "com.google.android.libraries.navigation.internal.yb.lw"
        private const val TRAFFIC_DATA_CLASS =
            "com.google.android.libraries.navigation.internal.br.m"
        private const val GUIDANCE_TRIP_PROTO_CLASS =
            "com.google.android.libraries.navigation.internal.aee.hv"
        private const val NAV_GUIDANCE_BUILDER_CLASS =
            "com.google.android.libraries.navigation.internal.rf.a"
        private const val NAV_GUIDANCE_STATE_CLASS =
            "com.google.android.libraries.navigation.internal.rf.b"
        private const val SM_L_CLASS =
            "com.google.android.libraries.navigation.internal.sm.l"
        private const val SM_M_CLASS =
            "com.google.android.libraries.navigation.internal.sm.m"
        private const val SM_G_CLASS =
            "com.google.android.libraries.navigation.internal.sm.g"
        private const val SM_H_CLASS =
            "com.google.android.libraries.navigation.internal.sm.h"
        private const val TO_A_BUILDER_CLASS =
            "com.google.android.libraries.navigation.internal.to.a\$a"
    }
}
