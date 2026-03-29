package me.matsumo.onenavi.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.google_sans_bold
import me.matsumo.onenavi.core.resource.google_sans_medium
import me.matsumo.onenavi.core.resource.google_sans_regular
import me.matsumo.onenavi.core.resource.home_title
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.home.map.HomeMapScreen
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val navBackStack = LocalNavBackStack.current

    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    val saveableStateHolder: SaveableStateHolder = rememberSaveableStateHolder()

    NavigationSuiteScaffold(
        modifier = modifier,
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
                            )
                        }

                        HomeRoute.Route -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Route",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopAppBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val googleSansFamily = FontFamily(
        Font(Res.font.google_sans_regular, FontWeight.Normal),
        Font(Res.font.google_sans_medium, FontWeight.Medium),
        Font(Res.font.google_sans_bold, FontWeight.Bold),
    )

    TopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        title = {
            Text(
                text = stringResource(Res.string.home_title),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = googleSansFamily,
                fontWeight = FontWeight.SemiBold,
            )
        },
        actions = {
            IconButton(onClick = onSettingClicked) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                )
            }
        },
    )
}
