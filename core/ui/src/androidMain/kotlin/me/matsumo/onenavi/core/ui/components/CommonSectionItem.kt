package me.matsumo.onenavi.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CommonSectionItem(
    title: String,
    description: String?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    valueTextStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    labelTextStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    actionIcon: ImageVector? = null,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    extra: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .background(containerColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modifier = Modifier.size(iconSize),
                imageVector = icon,
                tint = contentColorFor(containerColor),
                contentDescription = null,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = valueTextStyle,
                    color = contentColorFor(containerColor),
                )

                if (description != null) {
                    Text(
                        text = description,
                        style = labelTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (actionIcon != null) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = actionIcon,
                    tint = contentColorFor(containerColor),
                    contentDescription = null,
                )
            }
        }

        if (extra != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(iconSize),
                )
                extra()
            }
        }
    }
}
