package me.matsumo.onenavi.feature.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingSliderItem(
    title: String,
    description: String,
    valueLabel: String,
    value: Float,
    onValueChanged: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    isEnabled: Boolean = true,
) {
    val titleColor: Color
    val descriptionColor: Color
    val valueLabelColor: Color

    if (isEnabled) {
        titleColor = MaterialTheme.colorScheme.onSurface
        descriptionColor = MaterialTheme.colorScheme.onSurfaceVariant
        valueLabelColor = MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
            .copy(alpha = 0.38f)
            .compositeOver(MaterialTheme.colorScheme.surface)
            .also {
                titleColor = it
                descriptionColor = it
                valueLabelColor = it
            }
    }

    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                )

                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = descriptionColor,
                )
            }

            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = valueLabelColor,
            )
        }

        Slider(
            modifier = Modifier.fillMaxWidth(),
            enabled = isEnabled,
            value = value.coerceIn(valueRange),
            onValueChange = onValueChanged,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
internal fun SettingSliderItem(
    title: StringResource,
    description: StringResource,
    valueLabel: String,
    value: Float,
    onValueChanged: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    isEnabled: Boolean = true,
) {
    SettingSliderItem(
        modifier = modifier,
        title = stringResource(title),
        description = stringResource(description),
        valueLabel = valueLabel,
        value = value,
        onValueChanged = onValueChanged,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        isEnabled = isEnabled,
    )
}
