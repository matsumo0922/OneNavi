package me.matsumo.onenavi.feature.home.map.components.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.feature.home.map.RouteResult

@Composable
internal fun HomeMapRouteResultSheet(
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    onNavigationClicked: (RouteResult) -> Unit,
    onRouteResultSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(routeResults) { index, routeResult ->
            HomeMapRouteResultItem(
                modifier = Modifier.fillMaxWidth(),
                routeResult = routeResult,
                isSelected = selectedRouteIndex == index,
                onNavigationClicked = { onNavigationClicked.invoke(routeResult) },
                onRouteResultSelected = { onRouteResultSelected.invoke(index) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeMapRouteResultItem(
    routeResult: RouteResult,
    isSelected: Boolean,
    onNavigationClicked: () -> Unit,
    onRouteResultSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = remember(routeResult) { routeResult.item.durationSeconds.toInt().toString() }
    val distance = remember(routeResult) { routeResult.item.distanceMeters.toInt().toString() }

    Row(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                } else {
                    Modifier
                }
            )
            .clickable { onRouteResultSelected.invoke() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = buildAnnotatedString {
                    append("$duration min")
                    append(" ($distance m)")
                },
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                text = "${routeResult.item.viaRoadNames.joinToString(", ")} 経由",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = if (routeResult.item.hasTolls) "有料道路" else "一般道",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onNavigationClicked,
            contentPadding = ButtonDefaults.SmallContentPadding
        ) {
            Icon(
                modifier = Modifier.size(ButtonDefaults.SmallIconSize),
                imageVector = Icons.Outlined.Navigation,
                contentDescription = null,
            )

            Spacer(
                modifier = Modifier.size(ButtonDefaults.IconSpacing),
            )

            Text("ナビ開始")
        }
    }
}