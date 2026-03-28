package me.matsumo.onenavi.core.billing.model

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * サブスクリプションの状態を表す sealed interface。
 * ロード中・無料・プレミアムの3状態を持つ。
 */
@Stable
@Serializable
sealed interface SubscriptionState {
    /** 課金情報を読み込み中 */
    @Serializable
    data object Loading : SubscriptionState

    /** 無料ユーザー */
    @Serializable
    data object Free : SubscriptionState

    /**
     * プレミアムユーザー。
     *
     * @param plan 契約中のプラン
     * @param expiresAt サブスクリプションの有効期限（lifetime の場合は null）
     * @param willRenew 自動更新されるか（lifetime の場合は false）
     * @param originalPurchaseDate 初回購入日
     */
    @Serializable
    data class Premium(
        val plan: SubscriptionPlan,
        val expiresAt: Instant?,
        val willRenew: Boolean,
        val originalPurchaseDate: Instant?,
    ) : SubscriptionState

    val isPremium: Boolean
        get() = this is Premium
}
