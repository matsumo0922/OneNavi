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
    isEnabled: Boolean = true,
) {
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
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Slider(
            modifier = Modifier.fillMaxWidth(),
            enabled = isEnabled,
            value = value.coerceIn(valueRange),
            onValueChange = onValueChanged,
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
    isEnabled: Boolean = true,
) {
    SettingSliderItem(
        modifier = modifier,
        title = stringResource(title),
        description = stringResource(description),
        valueLabel = valueLabel,
        value = value,
        onValueChanged = onValueChanged,
        valueRange = valueRange,
        steps = steps,
        isEnabled = isEnabled,
    )
}
