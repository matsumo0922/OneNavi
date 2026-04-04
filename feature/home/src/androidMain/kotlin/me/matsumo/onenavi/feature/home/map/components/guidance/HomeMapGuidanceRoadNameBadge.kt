package me.matsumo.onenavi.feature.home.map.components.guidance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeMapGuidanceRoadNameBadge(
    roadName: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NavigationColors.roadNameBackground.copy(alpha = 0.85f))
            .padding(
                horizontal = 12.dp,
                vertical = 6.dp,
            ),
        text = roadName,
        color = NavigationColors.roadNameText,
        style = MaterialTheme.typography.labelMedium,
    )
}
