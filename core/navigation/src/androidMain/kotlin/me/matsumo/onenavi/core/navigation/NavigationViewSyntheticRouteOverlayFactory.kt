package me.matsumo.onenavi.core.navigation

import me.matsumo.onenavi.core.model.GoogleRoute
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * `NavigationView` の lower seam (`bb.e.a(bv.h)`) に渡す synthetic route overlay state を組み立てる。
 *
 * 現段階では route line / alt route 表示を最優先とし、`aw` / `az` / `be` / `bv.h`
 * の最小グラフだけを反射で構築する。
 */
internal class NavigationViewSyntheticRouteOverlayFactory {

    private val classCache = ConcurrentHashMap<String, Class<*>>()

    fun buildRouteOverlayState(routes: List<GoogleRoute>): Any {
        require(routes.isNotEmpty()) { "routes must not be empty" }

        val syntheticRoutes = routes.map(::buildRoute)
        val routeList = requireNotNull(
            invokeStatic(
                className = ROUTE_LIST_CLASS,
                methodName = "f",
                args = arrayOf(0, syntheticRoutes),
            ),
        ) { "RouteList factory returned null" }

        val builder = requireNotNull(
            invokeStatic(
                className = OVERLAY_STATE_CLASS,
                methodName = "O",
                args = emptyArray<Any>(),
            ),
        ) { "Overlay state builder returned null" }

        // bv.h.O() がほぼ全 field をデフォルト値で埋めて返してくれるが、
        // route 情報 (u/be) と texture config (C/bp.f) は呼び出し側で必ず設定する必要がある。
        // また field A: xz.br と field B: xz.br (ともに k/l setter) は O() が
        // field B しか埋めないため、こちらで A 用の Supplier を補う必要がある。
        // 参考実装: rj/p.java#a()  / tn/o.java
        invoke(builder, "u", routeList)
        invoke(
            builder,
            "C",
            getStaticField(TEXTURE_CONFIG_CLASS, "a"),
        )
        invoke(builder, "k", buildShowAllSupplier())

        return requireNotNull(invoke(builder, "G")) {
            "Overlay state builder.G() returned null"
        }
    }

    private fun buildShowAllSupplier(): Any {
        // xz.bv(cr.bb.SHOW_ALL) — Suppliers.ofInstance(SHOW_ALL) 相当。
        // bv.a の field A (xz.br = Supplier) を埋める。
        return instantiate(
            className = SUPPLIER_OF_INSTANCE_CLASS,
            args = arrayOf(getEnumConstant(ROUTE_VISIBILITY_CLASS, "SHOW_ALL")),
        )
    }

    private fun buildRoute(route: GoogleRoute): Any {
        val ca = instantiate(
            className = CA_CLASS,
            args = arrayOf(
                getStaticField(KG_CLASS, "A"),
                false,
            ),
        )
        val aw = instantiate(
            className = AW_CLASS,
            args = arrayOf(ca),
        )

        val polylinePoints = route.geometry.ifEmpty {
            listOf(route.origin, route.destination)
        }

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
        // bo.v.a (DRIVE/TWO_WHEELER 経路) は azVar.l().length == azVar.T() - 1 を要求する。
        // azVar.l() は this.i (= aw.h) を bl.d == zo.l.DESTINATION でフィルタした結果。
        // 2 waypoints (origin + destination) なら 1 step が必要。
        setField(aw, "h", buildSyntheticStepArray(lastVertexIndex = polylinePoints.lastIndex))

        return instantiate(
            className = AZ_CLASS,
            args = arrayOf(aw),
        )
    }

    private fun buildPolylineFromPoints(points: List<me.matsumo.onenavi.core.model.RoutePoint>): Any {
        val mapcorePoints = points.map { point ->
            instantiate(
                className = MAPCORE_POINT_CLASS,
                args = arrayOf(
                    toMapcoreCoordinate(point.latitude),
                    toMapcoreCoordinate(point.longitude),
                ),
            )
        }

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
     * `bo.v.a` の DRIVE/TWO_WHEELER 経路で要求される `bl[]` (steps) を 1 要素だけ合成する。
     *
     * `bl` の通常コンストラクタ (`bk` builder 経由) は zo.l, hq, hs, mapcore.z, list 多数を
     * 必須とするため、`Unsafe.allocateInstance` でコンストラクタを skip し、
     * フィルタ (`az.ac`) と marker placement で読まれる field のみを最小限埋める。
     */
    private fun buildSyntheticStepArray(lastVertexIndex: Int): Any {
        val stepClass = declaredClass(STEP_CLASS)
        val step = allocateInstanceUnsafe(stepClass)
        // az.ac() フィルタが bl.d == zo.l.DESTINATION のみ通すため必須。
        setField(step, "d", getEnumConstant(MANEUVER_CLASS, "DESTINATION"))
        // bo.v.a の marker placement で blVarArrL[i].k が listQ (= polyline vertices) の
        // インデックスとして読まれる。destination は polyline 末端の vertex。
        setIntField(step, "k", lastVertexIndex)
        // az.<init> 内で `blVar5.A.isEmpty()` が `O` flag 計算経路で呼ばれるため non-null List 必須。
        // 同じ List 型の final field (w, x, y, z, B, C, I) も後続 pipeline で
        // 参照される可能性が高いため、安全側に空リストで埋める。
        listOf("w", "x", "y", "z", "A", "B", "C", "I").forEach { fieldName ->
            setField(step, fieldName, Collections.emptyList<Any>())
        }
        // bq.ag.<init> が `((lr) blVar.K).c` で size を読むため、er 型 (Guava ImmutableList) を
        // 空インスタンス (lr.a) で埋める。
        val emptyImmutableList = getStaticField(EMPTY_IMMUTABLE_LIST_CLASS, "a")
        setField(step, "K", emptyImmutableList)

        val array = java.lang.reflect.Array.newInstance(stepClass, 1)
        java.lang.reflect.Array.set(array, 0, step)
        return array
    }

    /**
     * `br.m` (= bh の concrete) の minimal インスタンス。
     * `aw.c` -> `az.u` に伝播し、`cr.e.a` から `.a` (er) が読まれる。
     */
    private fun buildEmptyTrafficData(): Any {
        val emptyEr = getStaticField(EMPTY_IMMUTABLE_LIST_CLASS, "a")
        val emptyEz = getStaticField(EMPTY_IMMUTABLE_MAP_CLASS, "b")
        return instantiate(
            className = TRAFFIC_DATA_CLASS,
            args = arrayOf(emptyEr, emptyEr, emptyEz),
        )
    }

    private fun allocateInstanceUnsafe(targetClass: Class<*>): Any {
        val unsafeClass = declaredClass("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
        theUnsafeField.isAccessible = true
        val unsafe = requireNotNull(theUnsafeField.get(null)) {
            "sun.misc.Unsafe.theUnsafe was null"
        }
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return requireNotNull(allocateInstance.invoke(unsafe, targetClass)) {
            "Unsafe.allocateInstance returned null for ${targetClass.name}"
        }
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

    private fun toMapcoreCoordinate(degrees: Double): Int {
        return (degrees * MAPCORE_SCALE).roundToInt()
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
        return findField(
            targetClass = declaredClass(className),
            fieldName = fieldName,
        ).get(null)
    }

    private fun getEnumConstant(
        className: String,
        constantName: String,
    ): Any {
        val enumClass = declaredClass(className)
        require(enumClass.isEnum) { "Class is not enum: $className" }
        return enumClass.enumConstants.firstOrNull { constant ->
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
        private const val MAPCORE_SCALE = 10_000_000.0

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
        private const val STEP_CLASS =
            "com.google.android.libraries.navigation.internal.br.bl"
        private const val MANEUVER_CLASS =
            "com.google.android.libraries.navigation.internal.zo.l"
        private const val EMPTY_IMMUTABLE_LIST_CLASS =
            "com.google.android.libraries.navigation.internal.yb.lr"
        private const val EMPTY_IMMUTABLE_MAP_CLASS =
            "com.google.android.libraries.navigation.internal.yb.lw"
        private const val TRAFFIC_DATA_CLASS =
            "com.google.android.libraries.navigation.internal.br.m"
        private const val GUIDANCE_TRIP_PROTO_CLASS =
            "com.google.android.libraries.navigation.internal.aee.hv"
    }
}
