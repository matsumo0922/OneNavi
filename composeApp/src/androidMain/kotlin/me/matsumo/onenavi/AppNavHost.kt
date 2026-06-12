package me.matsumo.onenavi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import me.matsumo.onenavi.core.ui.animation.NavigationTransitions
import me.matsumo.onenavi.core.ui.screen.Destination
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.billing.paywallEntry
import me.matsumo.onenavi.feature.map.mapEntry
import me.matsumo.onenavi.feature.setting.oss.settingLicenseEntry
import me.matsumo.onenavi.feature.setting.settingDeveloperOptionsEntry
import me.matsumo.onenavi.feature.setting.settingEntry
import me.matsumo.onenavi.feature.setting.settingVoiceCategoryEntry

@Composable
internal fun AppNavHost(
    modifier: Modifier = Modifier,
    destinationSearchRequestId: Long? = null,
) {
    val navBackStack = rememberNavBackStack(Destination.config, Destination.Home)

    LaunchedEffect(destinationSearchRequestId) {
        if (destinationSearchRequestId == null) {
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
