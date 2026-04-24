package me.matsumo.onenavi.core.navigation

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import me.matsumo.onenavi.core.model.GoogleRoute

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
        val routeList = invokeStatic(
            className = ROUTE_LIST_CLASS,
            methodName = "f",
            args = arrayOf(0, syntheticRoutes),
        )

        val builder = invokeStatic(
            className = OVERLAY_STATE_CLASS,
            methodName = "O",
            args = emptyArray<Any>(),
        )

        invoke(builder, "n")
        invoke(builder, "F")
        invoke(builder, "u", routeList)
        invoke(
            builder,
            "C",
            getStaticField(TEXTURE_CONFIG_CLASS, "a"),
        )
        invoke(builder, "q", true)
        invoke(builder, "p", true)
        invoke(builder, "v", true)

        return invoke(builder, "G")
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

        setField(aw, "i", buildPolyline(route))
        setField(aw, "e", getEnumConstant(ROUTE_MODE_CLASS, "DRIVE"))
        setField(aw, "k", route.id)
        setField(aw, "C", Collections.emptyList<Any>())
        setField(aw, "D", buildMinimalIj())
        setField(aw, "f", buildWaypointList())
        setField(aw, "g", buildWaypointList())

        return instantiate(
            className = AZ_CLASS,
            args = arrayOf(aw),
        )
    }

    private fun buildPolyline(route: GoogleRoute): Any {
        val points = route.geometry.ifEmpty {
            listOf(route.origin, route.destination)
        }

        val mapcorePoints = points.map { point ->
            instantiate(
                className = MAPCORE_POINT_CLASS,
                args = arrayOf(
                    toMapcoreCoordinate(point.latitude),
                    toMapcoreCoordinate(point.longitude),
                ),
            )
        }

        val encodedPoints = invokeStatic(
            className = IMMUTABLE_LIST_CLASS,
            methodName = "p",
            args = arrayOf(mapcorePoints),
        )

        return invokeStatic(
            className = MAPCORE_POLYLINE_CLASS,
            methodName = "b",
            args = arrayOf(encodedPoints),
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

    private fun buildWaypointList(): Any {
        return invokeStatic(
            className = IMMUTABLE_LIST_CLASS,
            methodName = "j",
            args = arrayOf(getStaticField(WAYPOINT_CLASS, "G")),
        )
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
    ): Any {
        val method = findMethod(
            targetClass = target.javaClass,
            methodName = methodName,
            parameterCount = args.size,
        )
        return method.invoke(target, *args)
    }

    private fun invokeStatic(
        className: String,
        methodName: String,
        args: Array<Any>,
    ): Any {
        val method = findMethod(
            targetClass = declaredClass(className),
            methodName = methodName,
            parameterCount = args.size,
        )
        return method.invoke(null, *args)
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
        private const val MAPCORE_POLYLINE_CLASS =
            "com.google.android.libraries.geo.mapcore.api.model.ai"
        private const val IMMUTABLE_LIST_CLASS =
            "com.google.android.libraries.navigation.internal.yb.er"
        private const val EMPTY_LIST_CLASS =
            "com.google.android.libraries.navigation.internal.yb.lr"
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
    }
}
