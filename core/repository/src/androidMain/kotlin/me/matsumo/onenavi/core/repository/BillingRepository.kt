package me.matsumo.onenavi.core.repository

import androidx.compose.runtime.Stable
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.StoreProduct
import kotlinx.coroutines.flow.Flow
import me.matsumo.onenavi.core.billing.BillingDataSource
import me.matsumo.onenavi.core.billing.model.PurchaseResult
import me.matsumo.onenavi.core.billing.model.SubscriptionPlan
import me.matsumo.onenavi.core.billing.model.SubscriptionState

class BillingRepository(
    private val billingDataSource: BillingDataSource,
    private val appSettingRepository: AppSettingRepository,
) {
    val subscriptionState: Flow<SubscriptionState> = billingDataSource.subscriptionState

    fun configure() {
        billingDataSource.configure()
    }

    suspend fun getProducts(): List<ProductInfo>? {
        val offerings = billingDataSource.getOfferings() ?: return null
        val currentOffering = offerings.current ?: return null

        return buildList {
            currentOffering.monthly?.let { pkg ->
                add(
                    ProductInfo(
                        plan = SubscriptionPlan.MONTHLY,
                        product = pkg.storeProduct,
                        packageToPurchase = pkg,
                    ),
                )
            }
            currentOffering.annual?.let { pkg ->
                add(
                    ProductInfo(
                        plan = SubscriptionPlan.YEARLY,
                        product = pkg.storeProduct,
                        packageToPurchase = pkg,
                    ),
                )
            }
            currentOffering.lifetime?.let { pkg ->
                add(
                    ProductInfo(
                        plan = SubscriptionPlan.LIFETIME,
                        product = pkg.storeProduct,
                        packageToPurchase = pkg,
                    ),
                )
            }
        }
    }

    suspend fun purchase(productInfo: ProductInfo): PurchaseResult {
        val result = billingDataSource.purchasePackage(productInfo.packageToPurchase)

        if (result == PurchaseResult.Success) {
            syncPlusMode()
        }

        return result
    }

    suspend fun restorePurchases(): PurchaseResult {
        // 返金済み購入がデバイスに残っている場合に備え、先に同期する
        billingDataSource.syncPurchases()

        val result = billingDataSource.restorePurchases()

        if (result == PurchaseResult.Success) {
            syncPlusMode()
            return result
        }

        // syncPurchases / restorePurchases が返金済みトークンで失敗しても、
        // サーバー側に有効なサブスクリプションが存在する可能性がある。
        // getCustomerInfo でサーバーの最新状態を取得して plusMode を同期する。
        billingDataSource.getCustomerInfo() ?: return result
        val isPremiumNow = billingDataSource.getCurrentSubscriptionState().isPremium
        appSettingRepository.setPlusMode(isPremiumNow)

        return if (isPremiumNow) PurchaseResult.Success else result
    }

    fun isPremium(): Boolean {
        return billingDataSource.getCurrentSubscriptionState().isPremium
    }

    fun getCurrentSubscriptionState(): SubscriptionState {
        return billingDataSource.getCurrentSubscriptionState()
    }

    /**
     * RevenueCat の最新の CustomerInfo を取得し、plusMode を同期する。
     * 失効を検出した場合は syncPurchases で Google Play の購入状態も同期する。
     *
     * @return plusMode が true から false に変わった場合は true を返す
     */
    suspend fun verifySubscriptionStatus(): Boolean {
        billingDataSource.getCustomerInfo() ?: return false

        val wasPremium = appSettingRepository.currentSetting().plusMode
        val isPremiumNow = billingDataSource.getCurrentSubscriptionState().isPremium

        appSettingRepository.setPlusMode(isPremiumNow)

        if (wasPremium && !isPremiumNow) {
            // 返金済み購入がデバイスに残るのを防ぐため、Google Play と同期する
            billingDataSource.syncPurchases()
            return true
        }

        return false
    }

    private suspend fun syncPlusMode() {
        val isPremium = billingDataSource.getCurrentSubscriptionState().isPremium
        appSettingRepository.setPlusMode(isPremium)
    }
}

/**
 * RevenueCat から取得したプロダクト情報を保持するデータクラス。
 *
 * @param plan サブスクリプションプラン
 * @param product RevenueCat のストアプロダクト
 * @param packageToPurchase 購入用パッケージ
 */
@Stable
data class ProductInfo(
    val plan: SubscriptionPlan,
    val product: StoreProduct,
    val packageToPurchase: Package,
) {
    val priceString: String get() = product.price.formatted
    val periodString: String
        get() = when (plan) {
            SubscriptionPlan.MONTHLY -> "月"
            SubscriptionPlan.YEARLY -> "年"
            SubscriptionPlan.LIFETIME -> "買い切り"
        }
}
