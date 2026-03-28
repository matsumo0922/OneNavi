package me.matsumo.onenavi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import me.matsumo.onenavi.core.ui.animation.NavigationTransitions
import me.matsumo.onenavi.core.ui.screen.Destination
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.billing.paywallEntry
import me.matsumo.onenavi.feature.home.homeEntry
import me.matsumo.onenavi.feature.setting.oss.settingLicenseEntry
import me.matsumo.onenavi.feature.setting.settingEntry

@Composable
internal fun AppNavHost(
    modifier: Modifier = Modifier,
) {
    val navBackStack = rememberNavBackStack(Destination.config, Destination.Home)

    CompositionLocalProvider(
        LocalNavBackStack provides navBackStack,
    ) {
        NavDisplay(
            modifier = modifier,
            backStack = navBackStack,
            entryProvider = entryProvider {
                homeEntry()
                paywallEntry()
                settingEntry()
                settingLicenseEntry()
            },
            transitionSpec = { NavigationTransitions.forwardTransition },
            popTransitionSpec = { NavigationTransitions.backwardTransition },
            predictivePopTransitionSpec = { NavigationTransitions.backwardTransition },
        )
    }
}

