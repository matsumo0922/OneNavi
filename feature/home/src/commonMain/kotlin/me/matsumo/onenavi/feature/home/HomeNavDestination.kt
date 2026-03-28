package me.matsumo.onenavi.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.ui.graphics.vector.ImageVector
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_navigation_downloads
import me.matsumo.onenavi.core.resource.home_navigation_photos
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
                label = Res.string.home_navigation_photos,
                icon = Icons.Outlined.Photo,
                iconSelected = Icons.Filled.Photo,
                route = HomeRoute.Photos,
            ),
            HomeNavDestination(
                label = Res.string.home_navigation_downloads,
                icon = Icons.Outlined.Download,
                iconSelected = Icons.Filled.Downloading,
                route = HomeRoute.Downloads,
            ),
        )
    }
}
