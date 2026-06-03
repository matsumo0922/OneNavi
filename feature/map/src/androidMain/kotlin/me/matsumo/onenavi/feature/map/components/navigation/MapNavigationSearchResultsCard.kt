package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_close
import me.matsumo.onenavi.core.resource.error_no_data
import me.matsumo.onenavi.core.ui.theme.semiBold
import me.matsumo.onenavi.feature.map.components.bottomsheet.MapSearchResultRow
import org.jetbrains.compose.resources.stringResource

/**
 * ナビゲーション中の waypoint 追加検索結果を下部に表示するカード。
 *
 * ETA カードの代わりに表示され、案内画面状態を維持したまま候補一覧を確認できる。
 */
@Composable
internal fun MapNavigationSearchResultsCard(
    query: String,
    results: ImmutableList<SearchResultItem>,
    onCloseClicked: () -> Unit,
    onResultClicked: (SearchResultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = SearchResultsCardShape,
        ),
        shape = SearchResultsCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            MapNavigationSearchResultsHeader(
                modifier = Modifier.fillMaxWidth(),
                query = query,
                onCloseClicked = onCloseClicked,
            )

            HorizontalDivider()

            if (results.isEmpty()) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    text = stringResource(Res.string.error_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(
                        items = results,
                        key = { index, item -> "${item.placeId}_$index" },
                    ) { index, result ->
                        MapSearchResultRow(
                            modifier = Modifier.fillMaxWidth(),
                            index = index + 1,
                            result = result,
                            onClicked = {
                                onResultClicked(result)
                            },
                        )

                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MapNavigationSearchResultsHeader(
    query: String,
    onCloseClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(16.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = query,
            style = MaterialTheme.typography.titleMedium.semiBold(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        IconButton(
            onClick = onCloseClicked,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.common_close),
            )
        }
    }
}

/** 検索結果カードの角丸形状。 */
private val SearchResultsCardShape = RoundedCornerShape(16.dp)
