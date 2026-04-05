package me.matsumo.onenavi.feature.home.map.components.navi

import androidx.compose.ui.graphics.Color

/**
 * ナビゲーション画面の配色を一括管理する object。
 * Google Maps Android Auto 版を参考にした暫定値。後で一括変更可能。
 */
object NavigationColors {
    val maneuverBackground = Color(0xFF1B5E20)
    val maneuverText = Color.White
    val maneuverDistance = Color.White

    val tripCardBackground = Color(0xFF2C2C2C)
    val tripCardText = Color.White
    val tripCardSecondary = Color(0xFFB0B0B0)

    val calloutBackground = Color(0xFF1565C0)
    val calloutText = Color.White

    val controlBackground = Color(0xFF2C2C2C)
    val controlIcon = Color.White

    val roadNameBackground = Color(0xFF2C2C2C)
    val roadNameText = Color.White

    val arrivalBackground = Color(0xFF1B5E20)
    val arrivalText = Color.White
}
