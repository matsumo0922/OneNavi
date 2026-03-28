package me.matsumo.onenavi.core.billing

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.PurchasesConfiguration
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.Offerings
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.StoreProduct
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import me.matsumo.onenavi.core.billing.model.PurchaseResult
import me.matsumo.onenavi.core.billing.model.SubscriptionPlan
import me.matsumo.onenavi.core.billing.model.SubscriptionState
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.model.Platform
import me.matsumo.onenavi.core.model.currentPlatform
import kotlin.coroutines.resume
import kotlin.time.Instant

class BillingDataSource(
    private val appConfig: AppConfig,
) {
    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo: Flow<CustomerInfo?> = _customerInfo.asStateFlow()

    private val _subscriptionState = MutableStateFlow<SubscriptionState>(SubscriptionState.Loading)
    val subscriptionState: Flow<SubscriptionState> = _subscriptionState.asStateFlow()

    private var isConfigured = false

    fun configure() {
        if (isConfigured) return

        val apiKey = when (currentPlatform) {
            Platform.Android -> appConfig.purchaseAndroidApiKey
            Platform.IOS -> appConfig.purchaseIosApiKey
        }

        if (apiKey.isNullOrBlank()) {
            _subscriptionState.value = SubscriptionState.Free
            return
        }

        Purchases.configure(
            configuration = PurchasesConfiguration(apiKey = apiKey),
        )

        isConfigured = true

        Purchases.sharedInstance.getCustomerInfo(
            onSuccess = { customerInfo ->
                _customerInfo.value = customerInfo
                _subscriptionState.value = customerInfo.toSubscriptionState()
            },
            onError = {
                Napier.e("Failed to fetch customer info: ${it.message}")
                _subscriptionState.value = SubscriptionState.Free
            },
        )
    }

    suspend fun getOfferings(): Offerings? {
        if (!isConfigured) return null

        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.getOfferings(
                onSuccess = { offerings -> cont.resume(offerings) },
                onError = {
                    Napier.e("Failed to fetch offerings: ${it.message}")
                    cont.resume(null)
                },
            )
        }
    }

    suspend fun getCustomerInfo(): CustomerInfo? {
        if (!isConfigured) return null

        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.getCustomerInfo(
                onSuccess = { customerInfo ->
                    _customerInfo.value = customerInfo
                    _subscriptionState.value = customerInfo.toSubscriptionState()
                    cont.resume(customerInfo)
                },
                onError = {
                    Napier.e("Failed to fetch customer info: ${it.message}")
                    cont.resume(null)
                },
            )
        }
    }

    suspend fun purchase(storeProduct: StoreProduct): PurchaseResult {
        if (!isConfigured) return PurchaseResult.Error("Billing not configured")

        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.purchase(
                storeProduct = storeProduct,
                onSuccess = { _, customerInfo ->
                    _customerInfo.value = customerInfo
                    _subscriptionState.value = customerInfo.toSubscriptionState()
                    cont.resume(PurchaseResult.Success)
                },
                onError = { error, userCancelled ->
                    Napier.e("Failed to purchase product: ${error.message}")
                    if (userCancelled) {
                        cont.resume(PurchaseResult.Cancelled)
                    } else {
                        cont.resume(PurchaseResult.Error(error.message))
                    }
                },
            )
        }
    }

    suspend fun purchasePackage(packageToPurchase: Package): PurchaseResult {
        if (!isConfigured) return PurchaseResult.Error("Billing not configured")

        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.purchase(
                packageToPurchase = packageToPurchase,
                onSuccess = { _, customerInfo ->
                    _customerInfo.value = customerInfo
                    _subscriptionState.value = customerInfo.toSubscriptionState()
                    cont.resume(PurchaseResult.Success)
                },
                onError = { error, userCancelled ->
                    Napier.e("Failed to purchase package: ${error.message}")
                    if (userCancelled) {
                        cont.resume(PurchaseResult.Cancelled)
                    } else {
                        cont.resume(PurchaseResult.Error(error.message))
                    }
                },
            )
        }
    }

    suspend fun restorePurchases(): PurchaseResult {
        if (!isConfigured) return PurchaseResult.Error("Billing not configured")

        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.restorePurchases(
                onSuccess = { customerInfo ->
                    _customerInfo.value = customerInfo
                    _subscriptionState.value = customerInfo.toSubscriptionState()
                    cont.resume(PurchaseResult.Success)
                },
                onError = { error ->
                    Napier.e("Failed to restore purchases: ${error.message}")
                    cont.resume(PurchaseResult.Error(error.message))
                },
            )
        }
    }

    suspend fun syncPurchases(): CustomerInfo? {
        if (!isConfigured) return null

        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.syncPurchases(
                onSuccess = { customerInfo ->
                    _customerInfo.value = customerInfo
                    _subscriptionState.value = customerInfo.toSubscriptionState()
                    cont.resume(customerInfo)
                },
                onError = { error ->
                    Napier.e("Failed to sync purchases: ${error.message}")
                    cont.resume(null)
                },
            )
        }
    }

    fun getCurrentSubscriptionState(): SubscriptionState {
        return _subscriptionState.value
    }

    private fun CustomerInfo.toSubscriptionState(): SubscriptionState {
        val entitlement = entitlements.active[SubscriptionPlan.ENTITLEMENT_PRO]
            ?: return SubscriptionState.Free

        val id = listOfNotNull(entitlement.productIdentifier, entitlement.productPlanIdentifier).joinToString(":")
        val plan = SubscriptionPlan.fromProductId(id)
            ?: return SubscriptionState.Free

        return SubscriptionState.Premium(
            plan = plan,
            expiresAt = entitlement.expirationDateMillis?.let { Instant.fromEpochMilliseconds(it) },
            willRenew = entitlement.willRenew,
            originalPurchaseDate = entitlement.originalPurchaseDateMillis?.let { Instant.fromEpochMilliseconds(it) },
        )
    }
}
