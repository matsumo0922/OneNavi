package me.matsumo.onenavi.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_route_placeholder
import me.matsumo.onenavi.feature.home.map.HomeMapScreen
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeScreen(
    modifier: Modifier = Modifier,
) {
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var isNavigating by rememberSaveable { mutableStateOf(false) }
    val saveableStateHolder: SaveableStateHolder = rememberSaveableStateHolder()

    NavigationSuiteScaffold(
        modifier = modifier,
        layoutType = if (isNavigating) {
            NavigationSuiteType.None
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        },
        navigationSuiteItems = {
            for ((index, destination) in HomeNavDestination.all.withIndex()) {
                item(
                    selected = currentIndex == index,
                    onClick = { currentIndex = index },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex == index) destination.iconSelected else destination.icon,
                            contentDescription = stringResource(destination.label),
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(destination.label),
                        )
                    },
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.navigationBars,
        ) { contentPadding ->
            AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                targetState = currentIndex,
            ) { index ->
                saveableStateHolder.SaveableStateProvider(index) {
                    when (HomeNavDestination.all[index].route) {
                        HomeRoute.Map -> {
                            HomeMapScreen(
                                modifier = Modifier
                                    .padding(contentPadding)
                                    .fillMaxSize(),
                                onNavigatingChanged = { isNavigating = it },
                            )
                        }

                        HomeRoute.Route -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(Res.string.home_map_route_placeholder),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
