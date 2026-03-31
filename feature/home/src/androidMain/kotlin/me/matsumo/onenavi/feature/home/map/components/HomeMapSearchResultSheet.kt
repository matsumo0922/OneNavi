package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.HomeMapViewEvent

@Composable
internal fun HomeMapSearchResultSheet(
    searchResults: ImmutableList<SearchResultItem>,
    onViewEvent: (HomeMapViewEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        for ((index, result) in searchResults.withIndex()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onViewEvent(HomeMapViewEvent.OnSearchResultSelected(result)) }
                    .padding(16.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HomeMapNumberedPinContent(
                    number = index + 1,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Text(
                        text = result.fullAddress ?: "${result.effectiveLatitude}, ${result.effectiveLongitude}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
