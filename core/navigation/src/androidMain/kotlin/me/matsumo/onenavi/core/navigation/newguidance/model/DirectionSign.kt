package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable

/**
 * 方面看板 UI に表示する情報。
 *
 * @param primary 主文言
 * @param secondary 補助文言
 * @param imageKey 画像キャッシュキー
 */
@Immutable
data class DirectionSign(
    val primary: String,
    val secondary: String?,
    val imageKey: String?,
)
