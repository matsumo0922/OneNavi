package me.matsumo.onenavi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCommand
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator
import me.matsumo.onenavi.core.ui.animation.NavigationTransitions
import me.matsumo.onenavi.core.ui.screen.Destination
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.core.ui.theme.LocalOneNaviDisplaySurface
import me.matsumo.onenavi.feature.billing.paywallEntry
import me.matsumo.onenavi.feature.map.mapEntry
import me.matsumo.onenavi.feature.setting.oss.settingLicenseEntry
import me.matsumo.onenavi.feature.setting.settingDeveloperOptionsEntry
import me.matsumo.onenavi.feature.setting.settingEntry
import me.matsumo.onenavi.feature.setting.settingVoiceCategoryEntry
import org.koin.compose.koinInject

@Composable
internal fun AppNavHost(
    modifier: Modifier = Modifier,
) {
    val carPhoneSessionCoordinator = koinInject<CarPhoneSessionCoordinator>()
    val phoneCommands by carPhoneSessionCoordinator.phoneCommands.collectAsStateWithLifecycle()
    val displaySurface = LocalOneNaviDisplaySurface.current
    val commandEnvelope = phoneCommands[displaySurface]
    val navBackStack = rememberNavBackStack(Destination.config, Destination.Home)

    LaunchedEffect(commandEnvelope?.id) {
        val command = commandEnvelope?.command ?: return@LaunchedEffect
        if (!command.requiresMapRouting()) {
            return@LaunchedEffect
        }

        navBackStack.clear()
        navBackStack.add(Destination.Home)
    }

    CompositionLocalProvider(
        LocalNavBackStack provides navBackStack,
    ) {
        NavDisplay(
            modifier = modifier,
            backStack = navBackStack,
            entryProvider = entryProvider {
                // homeEntry()
                mapEntry()
                paywallEntry()
                settingEntry()
                settingLicenseEntry()
                settingVoiceCategoryEntry()
                settingDeveloperOptionsEntry()
            },
            transitionSpec = { NavigationTransitions.forwardTransition },
            popTransitionSpec = { NavigationTransitions.backwardTransition },
            predictivePopTransitionSpec = { NavigationTransitions.backwardTransition },
        )
    }
}

private fun CarPhoneSessionCommand.requiresMapRouting(): Boolean {
    return when (this) {
        CarPhoneSessionCommand.OpenAddWaypointSearch,
        CarPhoneSessionCommand.OpenDestinationSearch,
        is CarPhoneSessionCommand.AddStop,
        is CarPhoneSessionCommand.NavigateTo,
        is CarPhoneSessionCommand.PreviewRoute,
        is CarPhoneSessionCommand.SearchPlaces,
        -> true
    }
}
