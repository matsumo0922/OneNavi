package me.matsumo.onenavi.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.ui.graphics.vector.ImageVector
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_navigation_map
import me.matsumo.onenavi.core.resource.home_navigation_route
import org.jetbrains.compose.resources.StringResource

internal data class HomeNavDestination(
    val label: StringResource,
    val icon: ImageVector,
    val iconSelected: ImageVector,
    val route: HomeRoute,
) {
    companion object Companion {
        val all = listOf(
            HomeNavDestination(
                label = Res.string.home_navigation_map,
                icon = Icons.Outlined.Map,
                iconSelected = Icons.Filled.Map,
                route = HomeRoute.Map,
            ),
            HomeNavDestination(
                label = Res.string.home_navigation_route,
                icon = Icons.Outlined.Route,
                iconSelected = Icons.Filled.Route,
                route = HomeRoute.Route,
            ),
        )
    }
}
