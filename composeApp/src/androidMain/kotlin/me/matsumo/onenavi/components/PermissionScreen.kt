package me.matsumo.onenavi.components

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_allow
import me.matsumo.onenavi.core.resource.common_allowed
import me.matsumo.onenavi.core.resource.permission_location_description
import me.matsumo.onenavi.core.resource.permission_location_title
import me.matsumo.onenavi.core.resource.permission_microphone_description
import me.matsumo.onenavi.core.resource.permission_microphone_title
import me.matsumo.onenavi.core.resource.permission_notification_description
import me.matsumo.onenavi.core.resource.permission_notification_title
import me.matsumo.onenavi.core.resource.permission_title
import me.matsumo.onenavi.core.ui.utils.plus
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun PermissionScreen(
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current!!

    val permissionState = rememberMultiplePermissionsStateWrapper(
        permissionList = persistentListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        ),
        onPermissionResult = { _, failedToShowDialog ->
            if (failedToShowDialog) {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        ("package:${activity.packageName}").toUri(),
                    ),
                )
            }
        },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                modifier = Modifier.fillMaxWidth(),
            )
        },
        bottomBar = {
            BottomBar(
                modifier = Modifier.fillMaxWidth(),
                permissionState = permissionState,
            )
        },
    ) {
        val data = listOf(
            Triple(
                stringResource(Res.string.permission_location_title),
                stringResource(Res.string.permission_location_description),
                Icons.Default.MyLocation,
            ),
            Triple(
                stringResource(Res.string.permission_notification_title),
                stringResource(Res.string.permission_notification_description),
                Icons.Default.Notifications,
            ),
            Triple(
                stringResource(Res.string.permission_microphone_title),
                stringResource(Res.string.permission_microphone_description),
                Icons.Default.Mic,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it + PaddingValues(horizontal = 16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((label, value, icon) in data) {
                        PermissionSectionItem(
                            modifier = Modifier.fillMaxWidth(),
                            label = value,
                            value = label,
                            icon = icon,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionSectionItem(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer

    Row(
        modifier = modifier
            .background(containerColor)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(24.dp),
            imageVector = icon,
            tint = contentColorFor(containerColor),
            contentDescription = null,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColorFor(containerColor),
            )

            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBar(
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = stringResource(Res.string.permission_title),
            )
        }
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun BottomBar(
    permissionState: MultiplePermissionsState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Button(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            onClick = permissionState::launchMultiplePermissionRequest,
            enabled = !permissionState.allPermissionsGranted,
        ) {
            Text(
                text = if (permissionState.allPermissionsGranted) {
                    stringResource(Res.string.common_allowed)
                } else {
                    stringResource(Res.string.common_allow)
                },
            )
        }
    }
}

@Suppress("UnsafeCallOnNullableType")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun rememberMultiplePermissionsStateWrapper(
    permissionList: ImmutableList<String>,
    elapsedDuration: Long = 300,
    onPermissionResult: (allGranted: Boolean, failedToShowDialog: Boolean) -> Unit = { _, _ -> },
): MultiplePermissionsState {
    val activity = LocalActivity.current!!
    var shouldShowRationaleBefore: Map<String, Boolean> by remember { mutableStateOf(emptyMap()) }
    var start by remember { mutableLongStateOf(0L) }

    val multiple = rememberMultiplePermissionsState(permissionList) { result: Map<String, Boolean> ->
        val elapsedEnoughTime = System.currentTimeMillis() - start > elapsedDuration

        val shouldShowRationaleAfter: Map<String, Boolean> =
            permissionList.associateWith { perm ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }

        val anyRationaleBefore =
            shouldShowRationaleBefore.values.any { it }
        val anyRationaleAfter =
            shouldShowRationaleAfter.values.any { it }

        val failedToShowDialog = !(anyRationaleBefore || anyRationaleAfter || elapsedEnoughTime)

        val allGranted = result.values.all { it }
        onPermissionResult(allGranted, failedToShowDialog)
    }

    return object : MultiplePermissionsState {
        override val permissions: List<PermissionState> = multiple.permissions
        override val allPermissionsGranted: Boolean = multiple.allPermissionsGranted
        override val revokedPermissions: List<PermissionState> = multiple.revokedPermissions
        override val shouldShowRationale: Boolean = multiple.shouldShowRationale

        override fun launchMultiplePermissionRequest() {
            shouldShowRationaleBefore = multiple.permissions.associate { ps ->
                ps.permission to ps.status.shouldShowRationale
            }
            start = System.currentTimeMillis()
            multiple.launchMultiplePermissionRequest()
        }
    }
}
