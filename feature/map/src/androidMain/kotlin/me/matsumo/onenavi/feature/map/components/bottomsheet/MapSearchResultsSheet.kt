package me.matsumo.onenavi.feature.map.components.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.error_no_data
import me.matsumo.onenavi.core.ui.theme.semiBold
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapSearchResultsSheet(
    query: String,
    results: ImmutableList<SearchResultItem>,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationBarHeightDp = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val visibleItemCount = results.size.coerceIn(
        minimumValue = 1,
        maximumValue = SEARCH_RESULTS_PEEK_ITEM_COUNT,
    )
    val peekHeight = SEARCH_RESULTS_HEADER_HEIGHT +
        SEARCH_RESULTS_ITEM_HEIGHT * visibleItemCount.toFloat() +
        navigationBarHeightDp

    LaunchedEffect(peekHeight) {
        onUiEvent(MapUiEvent.OnBottomSheetPeekHeightChanged(peekHeight))
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = navigationBarHeightDp + 16.dp),
    ) {
        item {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 12.dp),
                text = query,
                style = MaterialTheme.typography.titleLarge.semiBold(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (results.isEmpty()) {
            item {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    text = stringResource(Res.string.error_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        itemsIndexed(
            items = results,
            key = { index, item -> "${item.placeId}_$index" },
        ) { index, result ->
            MapSearchResultRow(
                modifier = Modifier.fillMaxWidth(),
                index = index + 1,
                result = result,
                onClicked = {
                    onUiEvent(MapUiEvent.OnSearchResultSelected(result))
                },
            )

            HorizontalDivider()
        }
    }
}

@Composable
private fun MapSearchResultRow(
    index: Int,
    result: SearchResultItem,
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val address = result.shortFormattedAddress ?: result.formattedAddress

    ListItem(
        modifier = modifier.clickable { onClicked() },
        leadingContent = {
            SearchResultIndexBadge(index = index)
        },
        headlineContent = {
            Text(
                text = result.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = address?.let {
            {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(Color.Transparent),
    )
}

@Composable
private fun SearchResultIndexBadge(
    index: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .shadow(
                elevation = 4.dp,
                shape = CircleShape,
                clip = false,
            )
            .clip(CircleShape)
            .background(SearchResultMarkerColor)
            .border(
                width = 2.dp,
                color = Color.White,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.labelLarge.semiBold(),
            color = Color.White,
            maxLines = 1,
        )
    }
}

private const val SEARCH_RESULTS_PEEK_ITEM_COUNT = 3
private val SEARCH_RESULTS_HEADER_HEIGHT = 56.dp
private val SEARCH_RESULTS_ITEM_HEIGHT = 72.dp
private val SearchResultMarkerColor = Color(0xFFD32F2F)
