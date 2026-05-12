package me.matsumo.onenavi.core.ui.screen.view

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> SegmentedTabRow(
    items: ImmutableList<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerHeight: Dp = 56.dp,
    containerShape: Shape = RoundedCornerShape(50),
    indicatorShape: Shape = RoundedCornerShape(50),
    indicatorColor: Color = MaterialTheme.colorScheme.primaryContainer,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    unselectedContentColor: Color = MaterialTheme.colorScheme.primary,
    itemContent: @Composable (item: T, isSelected: Boolean) -> Unit = { item, _ ->
        Text(
            text = item.toString(),
            style = MaterialTheme.typography.labelLarge,
        )
    },
) {
    BoxWithConstraints(
        modifier = modifier
            .height(containerHeight)
            .clip(containerShape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = containerShape,
            ),
    ) {
        val totalWidth = maxWidth
        val tabWidth: Dp = totalWidth / items.size
        val indicatorLeft = tabWidth * selectedIndex

        val animatedLeft by animateDpAsState(
            targetValue = indicatorLeft,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "indicatorLeft",
        )

        Box(
            Modifier
                .offset(x = animatedLeft)
                .width(tabWidth)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(indicatorShape)
                .background(indicatorColor)
                .zIndex(-1f),
        )

        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { idx, item ->
                val isSel = idx == selectedIndex
                val textColor by animateColorAsState(
                    if (isSel) selectedContentColor else unselectedContentColor,
                    animationSpec = tween(200),
                    label = "textColor",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(4.dp)
                        .clip(indicatorShape)
                        .clickable { onSelect(idx) },
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides textColor) {
                        itemContent(item, isSel)
                    }
                }
            }
        }
    }
}
