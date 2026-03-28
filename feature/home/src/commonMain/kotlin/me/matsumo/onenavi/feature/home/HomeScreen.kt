package me.matsumo.onenavi.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import me.matsumo.onenavi.core.resource.*
import me.matsumo.onenavi.core.ui.screen.Destination
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(currentIndex) {
        scrollBehavior.state.heightOffset = 0f
    }

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
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                HomeTopAppBar(
                    modifier = Modifier.fillMaxWidth(),
                    scrollBehavior = scrollBehavior,
                    onSettingClicked = { navBackStack.add(Destination.Setting.Root) },
                )
            },
        ) { contentPadding ->
            AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                targetState = currentIndex,
            ) { index ->
                saveableStateHolder.SaveableStateProvider(index) {
                    when (HomeNavDestination.all[index].route) {
                        HomeRoute.Map -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Map",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
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
