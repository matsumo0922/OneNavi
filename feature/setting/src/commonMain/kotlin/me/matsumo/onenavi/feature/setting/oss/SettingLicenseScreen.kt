package me.matsumo.onenavi.feature.setting.oss

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.rememberLibraries
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_other_open_source_license
import me.matsumo.onenavi.core.ui.screen.view.LoadingView
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.setting.oss.components.LibraryItem
import me.matsumo.onenavi.feature.setting.oss.components.LicenseDialog
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
internal fun SettingLicenseRoute(
    modifier: Modifier = Modifier,
) {
    val libs by rememberLibraries { Res.readBytes("files/aboutlibraries.json").decodeToString() }

    AnimatedContent(
        modifier = modifier,
        targetState = libs,
        transitionSpec = { fadeIn().togetherWith(fadeOut()) },
    ) {
        if (it != null) {
            SettingLicenseScreen(
                modifier = Modifier.fillMaxSize(),
                libs = it,
            )
        } else {
            LoadingView(
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingLicenseScreen(
    libs: Libs,
    modifier: Modifier = Modifier,
) {
    val navBackStack = LocalNavBackStack.current
    val state = rememberTopAppBarState()
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state)

    var selectedLibrary by remember { mutableStateOf<Library?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.fillMaxWidth(),
                title = {
                    Text(stringResource(Res.string.setting_other_open_source_license))
                },
                navigationIcon = {
                    IconButton({ navBackStack.removeAt(navBackStack.size - 1) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = behavior,
            )
        },
    ) { padding ->
        // ./gradlew exportLibraryDefinitions -PexportPath="../core/resource/src/commonMain/composeResources/files" (for Windows)
        // ./gradlew exportLibraryDefinitions -PaboutLibraries.exportPath=../core/resource/src/commonMain/composeResources/files (for Mac)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 16.dp + padding.calculateTopPadding(),
                bottom = 16.dp + padding.calculateBottomPadding(),
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(libs.libraries) { library ->
                LibraryItem(
                    modifier = Modifier.fillMaxWidth(),
                    library = library,
                    onClick = { selectedLibrary = it },
                )
            }
        }
    }

    selectedLibrary?.let { library ->
        LicenseDialog(
            library = library,
            onDismissRequest = { selectedLibrary = null },
        )
    }
}
