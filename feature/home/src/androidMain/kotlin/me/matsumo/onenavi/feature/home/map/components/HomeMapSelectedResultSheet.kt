package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.model.SearchResultItem

@Composable
internal fun HomeMapSelectedResultSheet(
    selectedResult: SearchResultItem,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.padding(16.dp),
        text = selectedResult.name,
        style = MaterialTheme.typography.titleMedium,
    )
}
