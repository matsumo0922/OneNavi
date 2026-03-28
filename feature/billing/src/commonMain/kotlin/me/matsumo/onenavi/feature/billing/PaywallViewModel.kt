package me.matsumo.onenavi.feature.billing

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.billing.model.PurchaseResult
import me.matsumo.onenavi.core.billing.model.SubscriptionPlan
import me.matsumo.onenavi.core.common.suspendRunCatching
import me.matsumo.onenavi.core.repository.BillingRepository
import me.matsumo.onenavi.core.repository.ProductInfo
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.error_network
import me.matsumo.onenavi.core.ui.screen.ScreenState

class PaywallViewModel(
    private val billingRepository: BillingRepository,
) : ViewModel() {

    private val _screenState = MutableStateFlow<ScreenState<PaywallUiState>>(ScreenState.Loading())
    val screenState: StateFlow<ScreenState<PaywallUiState>> = _screenState.asStateFlow()

    private val _purchaseState = MutableStateFlow<PurchaseUiState>(PurchaseUiState.Idle)
    val purchaseState: StateFlow<PurchaseUiState> = _purchaseState.asStateFlow()

    private val _selectedPlan = MutableStateFlow(SubscriptionPlan.YEARLY)
    val selectedPlan: StateFlow<SubscriptionPlan> = _selectedPlan.asStateFlow()

    private var products: List<ProductInfo> = emptyList()

    init {
        fetch()
    }

    fun fetch() {
        viewModelScope.launch {
            _screenState.value = ScreenState.Loading()
            _screenState.value = suspendRunCatching {
                val fetchedProducts = billingRepository.getProducts() ?: emptyList()
                products = fetchedProducts
                PaywallUiState(products = fetchedProducts.toImmutableList())
            }.fold(
                onSuccess = { ScreenState.Idle(it) },
                onFailure = { ScreenState.Error(Res.string.error_network) },
            )
        }
    }

    fun selectPlan(plan: SubscriptionPlan) {
        _selectedPlan.value = plan
    }

    fun purchase() {
        val product = products.find { it.plan == _selectedPlan.value } ?: return

        viewModelScope.launch {
            _purchaseState.value = PurchaseUiState.Loading
            when (val result = billingRepository.purchase(product)) {
                PurchaseResult.Success -> {
                    if (billingRepository.isPremium()) {
                        _purchaseState.value = PurchaseUiState.Success
                    } else {
                        _purchaseState.value = PurchaseUiState.PurchaseFailed
                    }
                }

                PurchaseResult.Cancelled -> {
                    _purchaseState.value = PurchaseUiState.Idle
                }

                is PurchaseResult.Error -> {
                    _purchaseState.value = PurchaseUiState.Error(result.message)
                }
            }
        }
    }

    fun restore() {
        viewModelScope.launch {
            _purchaseState.value = PurchaseUiState.Loading
            when (val result = billingRepository.restorePurchases()) {
                PurchaseResult.Success -> {
                    if (billingRepository.isPremium()) {
                        _purchaseState.value = PurchaseUiState.Success
                    } else {
                        _purchaseState.value = PurchaseUiState.NoSubscriptionToRestore
                    }
                }

                PurchaseResult.Cancelled -> {
                    _purchaseState.value = PurchaseUiState.Idle
                }

                is PurchaseResult.Error -> {
                    _purchaseState.value = PurchaseUiState.Error(result.message)
                }
            }
        }
    }
}

@Stable
data class PaywallUiState(
    val products: ImmutableList<ProductInfo>,
)

@Stable
sealed interface PurchaseUiState {
    data object Idle : PurchaseUiState
    data object Loading : PurchaseUiState
    data object Success : PurchaseUiState
    data object PurchaseFailed : PurchaseUiState
    data object NoSubscriptionToRestore : PurchaseUiState
    data class Error(val message: String) : PurchaseUiState
}
