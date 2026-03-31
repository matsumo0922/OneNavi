package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled._360
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_share
import me.matsumo.onenavi.core.resource.home_map_bookmark
import me.matsumo.onenavi.core.resource.home_map_search_route
import me.matsumo.onenavi.core.resource.home_map_street_view
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeMapSelectedResultSheet(
    selectedResult: SearchResultItem,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TitleSection(
                modifier = Modifier.fillMaxWidth(),
                selectedResult = selectedResult,
            )
        }

        item {
            ButtonSection(
                modifier = Modifier.fillMaxWidth(),
                onRouteClicked = {},
                onFavoriteClicked = {},
                onStreetViewClicked = {},
                onShareClicked = {},
            )
        }
    }
}

@Composable
private fun TitleSection(
    selectedResult: SearchResultItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = selectedResult.name,
            style = MaterialTheme.typography.headlineSmall,
        )

        selectedResult.categories.joinToString().let {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ButtonSection(
    onRouteClicked: () -> Unit,
    onFavoriteClicked: () -> Unit,
    onStreetViewClicked: () -> Unit,
    onShareClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        ButtonItem(
            text = Res.string.home_map_search_route,
            icon = Icons.Default.Directions,
            onClick = onRouteClicked,
            isPrimary = true,
        ),
        ButtonItem(
            text = Res.string.home_map_bookmark,
            icon = Icons.Outlined.Bookmark,
            onClick = onFavoriteClicked,
        ),
        ButtonItem(
            text = Res.string.home_map_street_view,
            icon = Icons.AutoMirrored.Filled._360,
            onClick = onStreetViewClicked,
        ),
        ButtonItem(
            text = Res.string.common_share,
            icon = Icons.Filled.Share,
            onClick = onShareClicked,
        )
    )

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(items) { item ->
            val aspectRatio: Float
            val containerColor: Color
            val contentColor: Color

            if (item.isPrimary) {
                aspectRatio = 2f
                containerColor = MaterialTheme.colorScheme.inverseSurface
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            } else {
                aspectRatio = 1f
                containerColor = MaterialTheme.colorScheme.surfaceVariant
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            }

            Column(
                modifier = Modifier
                    .height(80.dp)
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = contentColor,
                )

                Text(
                    text = stringResource(item.text),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                )
            }
        }
    }
}

@Stable
private data class ButtonItem(
    val text: StringResource,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val isPrimary: Boolean = false,
)
