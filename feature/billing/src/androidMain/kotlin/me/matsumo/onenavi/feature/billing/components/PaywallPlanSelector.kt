package me.matsumo.onenavi.feature.billing.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.billing.model.SubscriptionPlan
import me.matsumo.onenavi.core.repository.ProductInfo
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.paywall_plan_lifetime
import me.matsumo.onenavi.core.resource.paywall_plan_lifetime_badge
import me.matsumo.onenavi.core.resource.paywall_plan_monthly
import me.matsumo.onenavi.core.resource.paywall_plan_monthly_badge
import me.matsumo.onenavi.core.resource.paywall_plan_yearly
import me.matsumo.onenavi.core.resource.paywall_plan_yearly_badge
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PaywallPlanSelector(
    products: ImmutableList<ProductInfo>,
    selectedPlan: SubscriptionPlan,
    onPlanSelected: (SubscriptionPlan) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        products.forEach { product ->
            PaywallPlanCard(
                modifier = Modifier.weight(1f),
                product = product,
                isSelected = selectedPlan == product.plan,
                onClick = { onPlanSelected(product.plan) },
            )
        }
    }
}

@Composable
private fun PaywallPlanCard(
    product: ProductInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "scale",
    )

    Card(
        modifier = modifier
            .scale(scale)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = when (product.plan) {
                    SubscriptionPlan.LIFETIME -> MaterialTheme.colorScheme.primary
                    SubscriptionPlan.YEARLY -> MaterialTheme.colorScheme.tertiary
                    SubscriptionPlan.MONTHLY -> MaterialTheme.colorScheme.secondary
                },
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    modifier = Modifier.padding(
                        horizontal = 6.dp,
                        vertical = 2.dp,
                    ),
                    text = when (product.plan) {
                        SubscriptionPlan.MONTHLY -> stringResource(Res.string.paywall_plan_monthly_badge)
                        SubscriptionPlan.YEARLY -> stringResource(Res.string.paywall_plan_yearly_badge)
                        SubscriptionPlan.LIFETIME -> stringResource(Res.string.paywall_plan_lifetime_badge)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (product.plan) {
                        SubscriptionPlan.LIFETIME -> MaterialTheme.colorScheme.onPrimary
                        SubscriptionPlan.YEARLY -> MaterialTheme.colorScheme.onTertiary
                        SubscriptionPlan.MONTHLY -> MaterialTheme.colorScheme.onSecondary
                    },
                )
            }

            Text(
                text = when (product.plan) {
                    SubscriptionPlan.MONTHLY -> stringResource(Res.string.paywall_plan_monthly)
                    SubscriptionPlan.YEARLY -> stringResource(Res.string.paywall_plan_yearly)
                    SubscriptionPlan.LIFETIME -> stringResource(Res.string.paywall_plan_lifetime)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )

            Text(
                text = product.priceString,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
