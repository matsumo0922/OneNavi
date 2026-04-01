package me.matsumo.onenavi.core.datasource

import android.content.Context
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.core.MapboxNavigation
import kotlinx.coroutines.suspendCancellableCoroutine
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Mapbox Navigation SDK を使用したルート検索データソース。
 * Directions API を直接叩くのではなく、SDK 経由でルートを取得することで
 * ナビセッション中のリルート・リフレッシュが無料になる。
 *
 * @param navigationProvider MapboxNavigation インスタンスを遅延取得するプロバイダ
 */
class MapboxNavigationRouteDataSource(
    private val context: Context,
    private val navigationProvider: () -> MapboxNavigation,
) : RouteDataSource {

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<List<RouteItem>> = runCatching {
        val origin = Point.fromLngLat(originLongitude, originLatitude)
        val destination = Point.fromLngLat(destinationLongitude, destinationLatitude)

        val routeOptions = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .applyLanguageAndVoiceUnitOptions(context)
            .coordinatesList(listOf(origin, destination))
            .alternatives(true)
            .build()

        val navigation = navigationProvider()

        requestRoutes(navigation, routeOptions).map { navigationRoute ->
            val directionsRoute = navigationRoute.directionsRoute
            val geometry = directionsRoute.geometry() ?: error("Route geometry is null")

            RouteItem(
                durationSeconds = directionsRoute.duration(),
                distanceMeters = directionsRoute.distance(),
                geometry = decodeGeometry(geometry),
                summary = directionsRoute.legs()?.firstOrNull()?.summary().orEmpty(),
            )
        }
    }

    private suspend fun requestRoutes(
        navigation: MapboxNavigation,
        routeOptions: RouteOptions,
    ): List<NavigationRoute> = suspendCancellableCoroutine { continuation ->
        navigation.requestRoutes(
            routeOptions,
            object : NavigationRouterCallback {
                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    routerOrigin: String,
                ) {
                    continuation.resume(routes)
                }

                override fun onFailure(
                    reasons: List<RouterFailure>,
                    routeOptions: RouteOptions,
                ) {
                    val message = reasons.joinToString { it.message }
                    continuation.resumeWithException(RuntimeException(message))
                }

                override fun onCanceled(
                    routeOptions: RouteOptions,
                    routerOrigin: String,
                ) {
                    continuation.cancel()
                }
            },
        )
    }

    companion object {
        private fun decodeGeometry(encodedPolyline: String): List<RoutePoint> {
            val points = mutableListOf<RoutePoint>()
            var index = 0
            var latitude = 0
            var longitude = 0

            while (index < encodedPolyline.length) {
                var result = 0
                var shift = 0
                var byte: Int

                do {
                    byte = encodedPolyline[index++].code - 63
                    result = result or ((byte and 0x1F) shl shift)
                    shift += 5
                } while (byte >= 0x20)

                latitude += if (result and 1 != 0) (result shr 1).inv() else result shr 1

                result = 0
                shift = 0

                do {
                    byte = encodedPolyline[index++].code - 63
                    result = result or ((byte and 0x1F) shl shift)
                    shift += 5
                } while (byte >= 0x20)

                longitude += if (result and 1 != 0) (result shr 1).inv() else result shr 1

                points.add(
                    RoutePoint(
                        latitude = latitude / 1e5,
                        longitude = longitude / 1e5,
                    ),
                )
            }

            return points
        }
    }
}
