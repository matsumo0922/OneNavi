package me.matsumo.onenavi.core.ui.screen.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_reload
import me.matsumo.onenavi.core.resource.error_copy_to_clipboard
import me.matsumo.onenavi.core.resource.error_executed
import me.matsumo.onenavi.core.ui.screen.ScreenState
import me.matsumo.onenavi.core.ui.theme.bold
import me.matsumo.onenavi.core.ui.theme.center
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorView(
    title: StringResource,
    message: StringResource,
    modifier: Modifier = Modifier,
    stackTrace: String? = null,
    retryTitle: StringResource? = null,
    retryAction: (() -> Unit)? = null,
    terminate: (() -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        modifier = modifier,
        topBar = {
            if (terminate != null) {
                TopAppBar(
                    modifier = Modifier.fillMaxWidth(),
                    title = { /* nothing */ },
                    navigationIcon = {
                        IconButton(terminate) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(it)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                space = 8.dp,
                alignment = Alignment.CenterVertically,
            ),
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium.bold().center(),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium.center(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (retryAction != null) {
                Button(
                    modifier = Modifier.padding(top = 24.dp),
                    onClick = { retryAction.invoke() },
                ) {
                    Text(
                        text = stringResource(retryTitle ?: Res.string.common_reload),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            if (stackTrace != null) {
                TextButton(
                    onClick = { clipboardManager.setText(AnnotatedString(stackTrace)) },
                ) {
                    Text(
                        text = stringResource(Res.string.error_copy_to_clipboard),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorView(
    errorState: ScreenState.Error,
    retryAction: (() -> Unit)?,
    terminate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ErrorView(
        modifier = modifier,
        title = Res.string.error_executed,
        message = errorState.message,
        stackTrace = errorState.throwable?.stackTraceToString(),
        retryTitle = errorState.retryTitle,
        retryAction = retryAction,
        terminate = terminate,
    )
}
