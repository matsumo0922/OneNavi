package me.matsumo.onenavi.core.billing.model

import androidx.compose.runtime.Stable

/**
 * 購入処理の結果を表す sealed interface。
 */
@Stable
sealed interface PurchaseResult {
    /** 購入成功 */
    data object Success : PurchaseResult

    /** ユーザーによるキャンセル */
    data object Cancelled : PurchaseResult

    /**
     * 購入エラー。
     *
     * @param message エラーメッセージ
     */
    data class Error(val message: String) : PurchaseResult
}
