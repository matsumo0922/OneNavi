package me.matsumo.onenavi.feature.billing.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.paywall_privacy
import me.matsumo.onenavi.core.resource.paywall_restore
import me.matsumo.onenavi.core.resource.paywall_subscribe
import me.matsumo.onenavi.core.resource.paywall_terms
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PaywallFooter(
    isLoading: Boolean,
    onPurchaseClicked: () -> Unit,
    onRestoreClicked: () -> Unit,
    onTermsClicked: () -> Unit,
    onPrivacyClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            onClick = onPurchaseClicked,
            enabled = !isLoading,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(Res.string.paywall_subscribe),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        TextButton(
            onClick = onRestoreClicked,
            enabled = !isLoading,
        ) {
            Text(text = stringResource(Res.string.paywall_restore))
        }

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onTermsClicked) {
                Text(
                    text = stringResource(Res.string.paywall_terms),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Text(
                text = "|",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            TextButton(onClick = onPrivacyClicked) {
                Text(
                    text = stringResource(Res.string.paywall_privacy),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
