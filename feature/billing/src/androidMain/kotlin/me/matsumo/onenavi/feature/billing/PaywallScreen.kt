package me.matsumo.onenavi.feature.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.billing.model.SubscriptionPlan
import me.matsumo.onenavi.core.repository.ProductInfo
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_close
import me.matsumo.onenavi.core.resource.paywall_error_no_subscription_to_restore
import me.matsumo.onenavi.core.resource.paywall_error_purchase_failed
import me.matsumo.onenavi.core.ui.screen.AsyncLoadContents
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.billing.components.PaywallFeatureList
import me.matsumo.onenavi.feature.billing.components.PaywallFooter
import me.matsumo.onenavi.feature.billing.components.PaywallHeader
import me.matsumo.onenavi.feature.billing.components.PaywallPlanSelector
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Suppress("UnusedParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaywallScreen(
    source: String,
    modifier: Modifier = Modifier,
    viewModel: PaywallViewModel = koinViewModel(),
) {
    val navBackStack = LocalNavBackStack.current
    val uriHandler = LocalUriHandler.current
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val purchaseState by viewModel.purchaseState.collectAsStateWithLifecycle()
    val selectedPlan by viewModel.selectedPlan.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val purchaseFailedMessage = stringResource(Res.string.paywall_error_purchase_failed)
    val noSubscriptionMessage = stringResource(Res.string.paywall_error_no_subscription_to_restore)

    LaunchedEffect(purchaseState) {
        when (purchaseState) {
            is PurchaseUiState.Success -> {
                navBackStack.removeLastOrNull()
            }

            is PurchaseUiState.PurchaseFailed -> {
                snackbarHostState.showSnackbar(purchaseFailedMessage)
            }

            is PurchaseUiState.NoSubscriptionToRestore -> {
                snackbarHostState.showSnackbar(noSubscriptionMessage)
            }

            is PurchaseUiState.Error -> {
                snackbarHostState.showSnackbar((purchaseState as PurchaseUiState.Error).message)
            }

            else -> {}
        }
    }

    AsyncLoadContents(
        modifier = modifier,
        screenState = screenState,
        retryAction = viewModel::fetch,
    ) { state ->
        PaywallContent(
            modifier = Modifier.fillMaxSize(),
            products = state.products,
            selectedPlan = selectedPlan,
            purchaseState = purchaseState,
            snackbarHostState = snackbarHostState,
            onPlanSelected = viewModel::selectPlan,
            onPurchaseClicked = viewModel::purchase,
            onRestoreClicked = viewModel::restore,
            onBackClicked = { navBackStack.removeLastOrNull() },
            onTermsClicked = { uriHandler.openUri("https://www.matsumo.me/application/all/team_of_service") },
            onPrivacyClicked = { uriHandler.openUri("https://www.matsumo.me/application/all/privacy_policy") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaywallContent(
    products: ImmutableList<ProductInfo>,
    selectedPlan: SubscriptionPlan,
    purchaseState: PurchaseUiState,
    snackbarHostState: SnackbarHostState,
    onPlanSelected: (SubscriptionPlan) -> Unit,
    onPurchaseClicked: () -> Unit,
    onRestoreClicked: () -> Unit,
    onBackClicked: () -> Unit,
    onTermsClicked: () -> Unit,
    onPrivacyClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLoading = purchaseState is PurchaseUiState.Loading

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PaywallHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            PaywallFeatureList(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            PaywallPlanSelector(
                modifier = Modifier.fillMaxWidth(),
                products = products,
                selectedPlan = selectedPlan,
                onPlanSelected = onPlanSelected,
            )

            PaywallFooter(
                modifier = Modifier.fillMaxWidth(),
                isLoading = isLoading,
                onPurchaseClicked = onPurchaseClicked,
                onRestoreClicked = onRestoreClicked,
                onTermsClicked = onTermsClicked,
                onPrivacyClicked = onPrivacyClicked,
            )
        }
    }
}
