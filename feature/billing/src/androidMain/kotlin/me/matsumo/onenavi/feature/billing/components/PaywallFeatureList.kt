package me.matsumo.onenavi.feature.billing.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Support
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.paywall_feature_high_quality
import me.matsumo.onenavi.core.resource.paywall_feature_high_quality_desc
import me.matsumo.onenavi.core.resource.paywall_feature_no_ads
import me.matsumo.onenavi.core.resource.paywall_feature_no_ads_desc
import me.matsumo.onenavi.core.resource.paywall_feature_priority_support
import me.matsumo.onenavi.core.resource.paywall_feature_priority_support_desc
import me.matsumo.onenavi.core.resource.paywall_feature_unlimited_downloads
import me.matsumo.onenavi.core.resource.paywall_feature_unlimited_downloads_desc
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PaywallFeatureList(
    modifier: Modifier = Modifier,
) {
    val features = listOf(
        Triple(Icons.Default.Download, Res.string.paywall_feature_unlimited_downloads, Res.string.paywall_feature_unlimited_downloads_desc),
        Triple(Icons.Default.HighQuality, Res.string.paywall_feature_high_quality, Res.string.paywall_feature_high_quality_desc),
        Triple(Icons.Default.NoPhotography, Res.string.paywall_feature_no_ads, Res.string.paywall_feature_no_ads_desc),
        Triple(Icons.Default.Support, Res.string.paywall_feature_priority_support, Res.string.paywall_feature_priority_support_desc),
    )

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        features.forEach { (icon, title, desc) ->
            PaywallFeatureItem(
                icon = icon,
                title = stringResource(title),
                description = stringResource(desc),
            )
        }
    }
}

@Composable
private fun PaywallFeatureItem(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            modifier = Modifier.size(20.dp),
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
